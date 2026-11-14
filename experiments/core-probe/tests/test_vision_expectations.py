"""用生成清单和实际日志形状验证 Vision 状态断言，避免通用拷贝日志的误判。"""

import csv
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SHELL = os.environ.get("PROBE_TEST_SHELL", "/bin/sh")
# 日志形状取自固定 Xray 26.11.9；连接编号、长度与 padding 数值不参与状态判读。
PADDED = """[Debug] [101] proxy: XtlsPadding 17 218 0
[Debug] [102] proxy: Xtls Unpadding new block, content 17 padding 218 command 0
[Debug] [102] proxy: XtlsPadding 16 76 0
[Debug] [101] proxy: Xtls Unpadding new block, content 16 padding 76 command 0
"""
TLS13 = "[Debug] [101] proxy: XtlsFilterTls found tls 1.3! 1163 TLS_AES_128_GCM_SHA256\n"
SENT_DIRECT = "[Debug] [101] proxy: XtlsPadding 107 911 2\n"
RECEIVED_DIRECT = "[Debug] [102] proxy: Xtls Unpadding new block, content 107 padding 911 command 2\n"
READV = "[Debug] [102] proxy: CopyRawConn (maybe) readv\n"
SPLICE = "[Debug] [102] proxy: CopyRawConn splice\n"


class VisionExpectationsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="vision-expectations-")
        cls.addClassCleanup(cls.tmp.cleanup)
        root = Path(cls.tmp.name)
        # 生成器会替换输出目录，只在临时副本执行，保留工作区已有探测结果。
        shutil.copytree(ROOT / "fixtures", root / "fixtures")
        vision = root / "xray/vision"
        vision.mkdir(parents=True)
        shutil.copyfile(ROOT / "xray/probe_common.py", vision.parent / "probe_common.py")
        shutil.copyfile(ROOT / "xray/vision/gen-vision.py", vision / "gen-vision.py")
        subprocess.run([sys.executable, str(vision / "gen-vision.py")], check=True, capture_output=True, text=True)
        with (vision / "loopback/manifest.tsv").open() as file:
            cls.cases = {row["id"]: row["expect"] for row in csv.DictReader(file, delimiter="\t")}
        cls.log = root / "case.log"

    def check(self, ids, log, verdict, probe="TCP-REPLY=TARGET-REPLY-OK"):
        self.log.write_text(log)
        for case_id in ids:
            with self.subTest(case=case_id, log=log, probe=probe):
                output = subprocess.check_output(
                    [SHELL, "-c", '. "$1"; eval_expect check_traffic "$2" "$3" "$4"; printf "%s\\n" "$verdict"',
                     "vision-test", str(ROOT / "xray/runner-lib.sh"), self.cases[case_id], probe, str(self.log)],
                    text=True,
                )
                self.assertEqual(output.strip(), verdict)

    def test_plaintext_allows_generic_copy_logs(self):
        for generic in ("", READV, SPLICE):
            self.check(("XV-01", "XV-03"), generic + PADDED, "PASS")

    def test_plaintext_rejects_either_direct_command(self):
        for direct in (SENT_DIRECT, RECEIVED_DIRECT, SENT_DIRECT + RECEIVED_DIRECT):
            self.check(("XV-01", "XV-03"), READV + PADDED + direct, "MISMATCH")

    def test_plaintext_requires_padding_observations(self):
        for incomplete in ("", READV, PADDED.splitlines()[0] + "\n", PADDED.splitlines()[1] + "\n"):
            self.check(("XV-01", "XV-03"), incomplete, "MISMATCH")

    def test_tls_direct_requires_protocol_evidence_not_generic_copy(self):
        for generic in ("", READV, SPLICE):
            self.check(("XV-02", "XV-04", "XV-05"), TLS13 + SENT_DIRECT + RECEIVED_DIRECT + generic, "PASS")
        for incomplete in (TLS13 + READV, SENT_DIRECT + RECEIVED_DIRECT,
                           TLS13 + SENT_DIRECT, TLS13 + RECEIVED_DIRECT):
            self.check(("XV-02", "XV-04", "XV-05"), incomplete, "MISMATCH")

    def test_command_matches_exact_vision_record(self):
        unrelated = "[Debug] other: command 2\n[Debug] other: XtlsPadding 107 911 2\n"
        self.check(("XV-01", "XV-03"), PADDED + unrelated, "PASS")
        self.check(("XV-02", "XV-04", "XV-05"), TLS13 + unrelated, "MISMATCH")
        self.check(("XV-02", "XV-04", "XV-05"),
                   TLS13 + SENT_DIRECT.replace("911 2", "911 20") + RECEIVED_DIRECT, "MISMATCH")

    def test_log_evidence_does_not_replace_successful_traffic(self):
        self.check(("XV-01", "XV-03"), PADDED, "MISMATCH", probe="TCP-NOREPLY")
        self.check(("XV-02", "XV-04", "XV-05"), TLS13 + SENT_DIRECT + RECEIVED_DIRECT,
                   "MISMATCH", probe="TCP-NOREPLY")


if __name__ == "__main__":
    unittest.main()
