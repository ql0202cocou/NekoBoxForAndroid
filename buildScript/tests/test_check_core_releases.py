import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class CheckCoreReleasesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="core-releases-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        scripts = self.root / "buildScript/lib"
        scripts.mkdir(parents=True)
        shutil.copyfile(ROOT / "buildScript/lib/check_core_releases.sh", scripts / "check_core_releases.sh")
        (scripts / "plugins.sh").write_text('XRAY_VERSION="v1.0.0"\n')
        version = self.root / "libcore/sing-box/constant/version.go"
        version.parent.mkdir(parents=True)
        version.write_text('var Version = "1.0.0-neko-1"\n')
        self.bin = self.root / "bin"
        self.bin.mkdir()
        # 只替换网络边界，真实执行主脚本、jq 和分页逻辑；凭据不传入子进程。
        curl = self.bin / "curl"
        curl.write_text(f"#!{sys.executable}\n" + '''
import json
import os
from pathlib import Path
import sys
from urllib.parse import parse_qs, urlparse

root = Path(os.environ["CORE_RELEASES_TEST_ROOT"])
settings = json.loads((root / "settings.json").read_text())
args = sys.argv[1:]
url = urlparse(args[-1])
with (root / "requests.jsonl").open("a") as log:
    log.write(json.dumps(url.path + "?" + url.query) + "\\n")
status = 200
if url.path == "/repos/XTLS/Xray-core/releases":
    body = ([{"tag_name": "v1.0.1", "draft": False, "prerelease": True,
              "published_at": "2026-01-01T00:00:00Z"}] if settings["newer"] else [])
elif url.path == "/repos/SagerNet/sing-box/releases":
    body = []
elif url.path == "/repos/XTLS/Xray-core/compare/v1.0.0...v1.0.1":
    query = parse_qs(url.query)
    page = int(query["page"][0])
    # 模拟服务端将超出上限的请求限制为 100 条。
    size = min(int(query["per_page"][0]), 100)
    total = settings["total"]
    body = {"total_commits": total, "commits": [
        {"sha": f"{i:040x}", "commit": {"committer": {"date": "2026-01-01T00:00:00Z"},
         "message": f"VLESS regression-marker-{i:04d}\\nextra body"}}
        for i in range((page - 1) * size, min(page * size, total))
    ]}
    if page == settings["fail_page"]:
        status, body = 503, {"message": "fixture unavailable"}
else:
    raise SystemExit("unexpected URL: " + args[-1])
Path(args[args.index("-o") + 1]).write_text(json.dumps(body))
print(status, end="")
''')
        curl.chmod(0o755)

    def run_check(self, total=0, newer=True, fail_page=0):
        (self.root / "settings.json").write_text(json.dumps({
            "total": total, "newer": newer, "fail_page": fail_page,
        }))
        env = {
            "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
            "CORE_RELEASES_TEST_ROOT": str(self.root),
        }
        result = subprocess.run(
            ["bash", str(self.root / "buildScript/lib/check_core_releases.sh")],
            env=env, capture_output=True, text=True, timeout=15,
        )
        requests = [json.loads(line) for line in (self.root / "requests.jsonl").read_text().splitlines()]
        pages = [request for request in requests if "/compare/" in request]
        return result, requests, pages

    def test_reads_all_pages_in_order_without_duplicates(self):
        result, _, pages = self.run_check(total=205)
        self.assertEqual(result.returncode, 1, result.stderr)
        markers = [line.split("regression-marker-")[1] for line in result.stdout.splitlines()
                   if "regression-marker-" in line]
        self.assertEqual(markers, [f"{i:04d}" for i in range(205)])
        self.assertEqual([page.split("?")[1] for page in pages],
                         [f"per_page=100&page={i}" for i in (1, 2, 3)])

    def test_exact_multiple_stops_at_total(self):
        result, _, pages = self.run_check(total=200)
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(len(pages), 2)
        self.assertIn("regression-marker-0199", result.stdout)

    def test_short_page_finishes(self):
        result, _, pages = self.run_check(total=7)
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(len(pages), 1)
        self.assertIn("regression-marker-0006", result.stdout)

    def test_empty_comparison_finishes(self):
        result, _, pages = self.run_check()
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(len(pages), 1)

    def test_no_new_release_skips_comparison(self):
        result, _, pages = self.run_check(newer=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(pages, [])

    def test_later_page_failure_returns_query_failure(self):
        result, requests, pages = self.run_check(total=205, fail_page=2)
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertEqual(len(pages), 2)
        self.assertIn("HTTP 503", result.stderr)
        self.assertTrue(any("/SagerNet/sing-box/releases" in request for request in requests))


if __name__ == "__main__":
    unittest.main()
