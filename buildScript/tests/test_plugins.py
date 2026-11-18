import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]


class PluginInstallTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'buildScript/init').mkdir(parents=True)
        shutil.copy2(ROOT / 'buildScript/init/verify_sha256.sh', self.root / 'buildScript/init')
        (self.root / 'buildScript/lib').mkdir()
        shutil.copy2(ROOT / 'buildScript/lib/replace-assets.sh', self.root / 'buildScript/lib')
        script = (ROOT / 'buildScript/lib/plugins.sh').read_text()
        self.new = {}
        self.downloads = self.root / 'downloads'
        self.downloads.mkdir()
        for abi, archive in [('arm64-v8a', 'arm64-v8a'), ('x86_64', 'amd64')]:
            data = b'\x7fELFsynthetic-' + abi.encode()
            self.new[abi] = data
            digest = hashlib.sha256(data).hexdigest()
            script = re.sub(rf'XRAY_SHA256_{abi.replace("-", "_")}=[0-9a-f]+',
                            f'XRAY_SHA256_{abi.replace("-", "_")}={digest}', script)
            name = f'Xray-android-{archive}.zip'
            with zipfile.ZipFile(self.downloads / name, 'w') as archive_file:
                archive_file.writestr('xray', data)
            zip_digest = hashlib.sha256((self.downloads / name).read_bytes()).hexdigest()
            (self.downloads / f'{name}.dgst').write_text(f'SHA2-256= {zip_digest}\n')
        (self.root / 'plugins.sh').write_text(script)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        curl = self.bin / 'curl'
        curl.write_text('''#!/bin/bash
url="${@: -1}"
name="${url##*/}"
if [ "$name" = "${FAIL_DOWNLOAD:-}" ]; then exit 22; fi
if [[ "$name" = *.dgst ]]; then cat "$DOWNLOADS/$name"; else cp "$DOWNLOADS/$name" "$name"; fi
''')
        curl.chmod(0o755)
        self.installed = self.root / 'app/executableSo'
        for abi in self.new:
            path = self.installed / abi
            path.mkdir(parents=True)
            (path / 'libxray.so').write_bytes(b'\x7fELFold-' + abi.encode())
        (self.installed / '.versions').write_text('old-stamp\n')

    def snapshot(self):
        return {str(p.relative_to(self.installed)): p.read_bytes()
                for p in self.installed.rglob('*') if p.is_file()}

    def run_install(self, failure=''):
        env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                   DOWNLOADS=str(self.downloads), FAIL_DOWNLOAD=failure)
        return subprocess.run(['bash', 'plugins.sh'], cwd=self.root, env=env,
                              capture_output=True, text=True)

    def test_second_download_failure_preserves_entire_install(self):
        before = self.snapshot()
        result = self.run_install('Xray-android-amd64.zip')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, self.snapshot())
        self.assertEqual([], list((self.root / 'app').glob('.asset-replacement.*')))

    def test_second_hash_failure_preserves_entire_install(self):
        before = self.snapshot()
        (self.downloads / 'Xray-android-amd64.zip.dgst').write_text('SHA2-256= ' + '0' * 64)
        self.assertNotEqual(0, self.run_install().returncode)
        self.assertEqual(before, self.snapshot())

    def test_publish_failure_restores_old_directory_and_stamp(self):
        before = self.snapshot()
        mv = self.bin / 'mv'
        mv.write_text('''#!/bin/bash
if [[ "$1" = */new ]]; then exit 1; fi
exec /bin/mv "$@"
''')
        mv.chmod(0o755)
        self.assertNotEqual(0, self.run_install().returncode)
        self.assertEqual(before, self.snapshot())
        self.assertEqual([], list((self.root / 'app').glob('.asset-replacement.*')))

    def test_success_installs_both_and_cache_is_rechecked(self):
        result = self.run_install()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        for abi, expected in self.new.items():
            self.assertEqual(expected, (self.installed / abi / 'libxray.so').read_bytes())
        before = self.snapshot()
        self.assertEqual(0, self.run_install('Xray-android-arm64-v8a.zip').returncode)
        self.assertEqual(before, self.snapshot())
        (self.installed / 'arm64-v8a/libxray.so').write_bytes(b'\x7fELFcorrupt')
        self.assertNotEqual(0, self.run_install('Xray-android-arm64-v8a.zip').returncode)


if __name__ == '__main__':
    unittest.main()
