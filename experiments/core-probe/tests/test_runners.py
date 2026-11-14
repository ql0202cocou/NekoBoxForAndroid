"""用独立临时目录与可控子进程验证执行器，不依赖外核或固定端口。"""

import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[1] / "xray"
SHELL = os.environ.get("PROBE_TEST_SHELL", "/bin/sh")
FAKE = r'''
import os, signal, sys, time
from pathlib import Path
role = Path(sys.argv[0]).name
if role == "probe":
    role = sys.argv[1]
fd = os.open(os.environ["PID_LOG"], os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
os.write(fd, f"{os.getpid()} {role}\n".encode())
os.close(fd)
if role == "sleep":
    time.sleep(float(sys.argv[1]) / 10)
    sys.exit(0)
if "-test" in sys.argv or "convert" in sys.argv:
    print(os.environ.get("CONFIG_OUTPUT", "Configuration OK."), flush=True)
    sys.exit(int(os.environ.get("CONFIG_EXIT", "0")))
if os.environ.get("FAIL_ROLE") == role:
    print("ERR=fixture startup failure", flush=True)
    sys.exit(1)
if role in ("tcp", "udp", "hs"):
    if os.environ.get("HANG_PROBE"):
        signal.pause()
    print(os.environ.get("PROBE_OUTPUT", "TCP-NOREPLY"), flush=True)
    sys.exit(int(os.environ.get("PROBE_EXIT", "0")))
if os.environ.get("IGNORE_TERM"):
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
ready = {
    "tcpserve": "TCP-LISTEN=127.0.0.1:40000", "echo": "ECHO-LISTEN=127.0.0.1:40053",
    "tlshello": "TLS-LISTEN=127.0.0.1:32000", "httpdump": "HTTPDUMP-LISTEN=127.0.0.1:32000",
    "xray": "[Warning] core: Xray fixture started", "singbox": "sing-box started (0.00s)",
}
if os.environ.get("NO_READY_ROLE") != role:
    print(ready[role], flush=True)
extra = {"tlshello": "HELLO_OUTPUT", "httpdump": "DUMP_OUTPUT", "xray": "CORE_OUTPUT"}.get(role)
if extra:
    print(os.environ.get(extra, ""), flush=True)
if os.environ.get("CRASH_ROLE") == role:
    time.sleep(0.3)
    sys.exit(1)
while True:
    signal.pause()
'''


class RunnersTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="core-probe-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.xray = self.root / "xray"
        for rel in ("runner-lib.sh", "run-test.sh", "run-lb.sh", "xhttp/run-test.sh", "xhttp/run-lb.sh", "vision/run-transports.sh"):
            dst = self.xray / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / rel, dst)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for name in ("xray", "singbox", "probe", "httpdump", "sleep"):
            file = self.bin / name
            file.write_text(f"#!{sys.executable}\n" + FAKE)
            file.chmod(0o755)
        self.env = dict(os.environ, PATH=f"{self.bin}:{os.environ['PATH']}",
                        XRAY=str(self.bin / "xray"), SINGBOX=str(self.bin / "singbox"),
                        PROBE=str(self.bin / "probe"), HTTPDUMP=str(self.bin / "httpdump"),
                        PID_LOG=str(self.root / "pids"))
        self.addCleanup(self.kill_survivors)

    def pids(self):
        file = self.root / "pids"
        return [line.split() for line in file.read_text().splitlines()] if file.exists() else []

    def alive(self):
        alive = []
        for pid, role in self.pids():
            try:
                os.kill(int(pid), 0)
                alive.append((int(pid), role))
            except ProcessLookupError:
                pass
        return alive

    def kill_survivors(self):
        for pid, _ in self.alive():
            try:
                os.kill(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass

    def assert_reaped(self):
        deadline = time.monotonic() + 2
        while self.alive() and time.monotonic() < deadline:
            time.sleep(0.02)
        self.assertEqual(self.alive(), [], "执行器遗留子进程")

    def suite(self, runner, expect="FAIL", kind="tcp", rows=1):
        if runner == "vision":
            target = self.xray / "vision/transports"
            (target / "VT-01").mkdir(parents=True, exist_ok=True)
            for name in ("xray", "sing-box"):
                (target / "VT-01" / f"{name}.json").write_text("{}")
            header = "id\tkind\targs\texpect\tnote\n"
            row = f"VT-01\tsb-{kind}\t-\t{expect}\tfixture\n"
            script = self.xray / "vision/run-transports.sh"
        else:
            base = self.xray / "xhttp" if runner == "xhttp" else self.xray
            target = base / "loopback"
            target.mkdir(exist_ok=True)
            (target / "case.json").write_text("{}")
            header = "id\tfile\tkind\targs\texpect\titem\tsrc\tnote\n"
            row = f"L-01\tcase.json\t{kind}\t-\t{expect}\t1\tfixture\tfixture\n"
            script = base / "run-lb.sh"
        (target / "manifest.tsv").write_text(header + row * rows)
        return script

    def run_suite(self, runner, expect="FAIL", kind="tcp", **env):
        script = self.suite(runner, expect, kind)
        result = subprocess.run([SHELL, str(script)], env=dict(self.env, **env),
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=12)
        self.assert_reaped()
        return result.stdout

    def test_config_requires_successful_conversion(self):
        for runner in ("", "xhttp"):
            base = self.xray / runner
            target = base / "test"
            target.mkdir()
            (target / "manifest.tsv").write_text(
                "id\tfile\tcmd\texpect\titem\tsrc\tnote\n"
                "T-1\tcase.json\tpb\tPB:-forbidden\t1\tfixture\tfixture\n"
                "T-2\tcase.json\tpb\tPB:+present\t1\tfixture\tfixture\n")
            for code in ("0", "1", "126", "127"):
                with self.subTest(runner=runner, code=code):
                    out = subprocess.check_output([SHELL, str(base / "run-test.sh")],
                        env=dict(self.env, CONFIG_EXIT=code, CONFIG_OUTPUT="present"), text=True)
                    verdict = "PASS" if code == "0" else "MISMATCH"
                    self.assertEqual(out.count(f"] {verdict} exit={code}"), 2, out)

    def test_traffic_infrastructure_failures_are_errors(self):
        for runner in ("general", "xhttp", "vision"):
            for env in ({"FAIL_ROLE": "xray"}, {"FAIL_ROLE": "tcpserve"},
                        {"NO_READY_ROLE": "xray"}, {"PROBE_EXIT": "1", "PROBE_OUTPUT": "ERR=connection refused"},
                        {"PROBE_EXIT": "1", "PROBE_OUTPUT": "TCP-NOREPLY"},
                        {"PROBE_OUTPUT": ""}, {"PROBE_OUTPUT": "ERR=broken"},
                        {"CRASH_ROLE": "xray"}):
                with self.subTest(runner=runner, env=env):
                    out = self.run_suite(runner, **env)
                    self.assertIn("] ERROR ", out)
                    self.assertNotIn("] PASS ", out)

    def test_valid_traffic_and_protocol_rejection(self):
        for runner in ("general", "xhttp", "vision"):
            for expect, output, kind in (("PASS", "TCP-REPLY=TARGET-REPLY-OK", "tcp"),
                                         ("FAIL", "TCP-NOREPLY", "tcp"),
                                         # 对端接受后复位：探针多报一行撞上的阶段，仍是「无回复」
                                         ("FAIL", "TCP-RESET=dial\nTCP-NOREPLY", "tcp"),
                                         ("UDP:TIMEOUT", "R1 TIMEOUT", "udp"),
                                         ("UDP:REPLY", "R1 REPLY=ECHO:ok", "udp")):
                with self.subTest(runner=runner, expect=expect):
                    out = self.run_suite(runner, expect, kind, PROBE_OUTPUT=output,
                                         CORE_OUTPUT="initialize vision: vision: not a valid supported TLS connection")
                    self.assertIn("] PASS ", out)
            self.assertIn("] MISMATCH ", self.run_suite(runner, "PASS", PROBE_OUTPUT="TCP-REPLY=TARGET-REPLY-OK-extra"))
            self.assertIn("] MISMATCH ", self.run_suite(runner, "PASS", PROBE_OUTPUT="TCP-RESET=write\nTCP-NOREPLY"))

    def test_absence_assertions_require_observation(self):
        self.assertIn("] ERROR ", self.run_suite("general", "hello:-X25519MLKEM768", "hello"))
        self.assertIn("] PASS ", self.run_suite("general", "hello:-X25519MLKEM768", "hello",
                                                HELLO_OUTPUT="HELLO sni=example.com curves=[X25519]"))
        self.assertIn("] ERROR ", self.run_suite("general", "log:-forbidden", FAIL_ROLE="xray"))
        self.assertIn("] ERROR ", self.run_suite("xhttp", "dump:-HOST=", "dump", FAIL_ROLE="httpdump"))
        self.assertIn("] ERROR ", self.run_suite("xhttp", "dump:-HOST=", "dump", DUMP_OUTPUT="HTTPDUMP-ERROR=EOF"))
        # 无请求本身是合法观测：收集器已就绪，核心明确拒绝构造请求。
        self.assertIn("] PASS ", self.run_suite("xhttp", "dump:-HOST= && log:+invalid port", "dump",
                                                CORE_OUTPUT="invalid port"))
        self.assertIn("] PASS ", self.run_suite("xhttp", "dump:+ECHO-GOT && dump:-RAW|", "dumpudp",
                                                DUMP_OUTPUT="ECHO-GOT from=127.0.0.1"))

    def test_record_modes_do_not_hide_errors(self):
        self.assertIn("] ERROR ", self.run_suite("general", "ANY", FAIL_ROLE="xray"))
        self.assertIn("] ERROR ", self.run_suite("vision", "-", FAIL_ROLE="singbox"))
        self.assertIn("] ANY passes=5/5 ", self.run_suite("general", "ANY", PROBE_OUTPUT="TCP-REPLY=TARGET-REPLY-OK"))

    def test_singbox_server(self):
        script = self.suite("vision")
        case = self.xray / "vision/transports/VT-01"
        (case / "xray.json").unlink()
        (case / "sing-box-server.json").write_text("{}")
        for extra, verdict in (({}, "PASS"), ({"FAIL_ROLE": "singbox"}, "ERROR")):
            with self.subTest(extra=extra):
                out = subprocess.check_output([SHELL, str(script)], env=dict(self.env, **extra), text=True, timeout=12)
                self.assertIn(f"] {verdict} ", out)
                self.assert_reaped()

    def test_normal_completion_cleans_every_case(self):
        for runner in ("general", "xhttp", "vision"):
            with self.subTest(runner=runner):
                script = self.suite(runner, rows=2)
                out = subprocess.check_output([SHELL, str(script)], env=self.env, text=True, timeout=12)
                self.assertEqual(out.count("] PASS "), 2, out)
                self.assert_reaped()

    def test_interrupt_reaps_helpers_cores_and_probe(self):
        for runner in ("general", "xhttp", "vision"):
            for sig in (signal.SIGTERM, signal.SIGINT):
                for phase in ("sleep", "tcp"):
                    with self.subTest(runner=runner, signal=sig, phase=phase):
                        self.interrupt(runner, sig, phase)

    def interrupt(self, runner, sig, phase):
        # XHTTP 启动两个 dump 帮助进程；Vision 启动两个核心；让进程拒绝 TERM 验证兜底回收。
        script = self.suite(runner, kind="dumpudp" if runner == "xhttp" else "tcp")
        before = len(self.pids())
        proc = subprocess.Popen([SHELL, str(script)], env=dict(self.env, HANG_PROBE="1", IGNORE_TERM="1"),
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        try:
            deadline = time.monotonic() + 5
            while not any(role == phase for _, role in self.pids()[before:]):
                self.assertIsNone(proc.poll(), "执行器提前退出")
                self.assertLess(time.monotonic(), deadline, "未进入指定阶段")
                time.sleep(0.02)
            proc.send_signal(sig)
            out, _ = proc.communicate(timeout=8)
            self.assertEqual(proc.returncode, 128 + sig, out)
            self.assert_reaped()
        finally:
            if proc.poll() is None:
                proc.kill()
                self.kill_survivors()
                proc.communicate(timeout=3)


if __name__ == "__main__":
    unittest.main()
