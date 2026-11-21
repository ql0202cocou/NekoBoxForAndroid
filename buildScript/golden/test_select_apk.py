import json
import tempfile
import unittest
from pathlib import Path

from select_apk import select_apk


class SelectApkTest(unittest.TestCase):
    def test_exact_abi_then_universal(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            exact = directory / "test [,]{arm}.apk"
            universal = directory / "all.apk"
            metadata = directory / "output-metadata.json"
            exact.touch()
            universal.touch()
            arm = [{"value": "arm64-v8a", "filterType": "ABI"}]

            def entry(file, filters):
                return dict(outputFile=file.name, versionCode=123, filters=filters)

            elements = [
                entry(universal, []),
                entry(exact, [*arm, {"filterType": "DENSITY", "value": "hdpi"}]),
                entry(exact, arm),
            ]
            metadata.write_text(json.dumps(dict(elements=elements)))
            self.assertEqual((123, exact), select_apk(metadata, "arm64-v8a"))
            self.assertEqual((123, universal), select_apk(metadata, "x86_64"))
            exact.unlink()
            with self.assertRaisesRegex(ValueError, "does not exist"):
                select_apk(metadata, "arm64-v8a")
            metadata.write_text(json.dumps(dict(elements=elements[1:])))
            with self.assertRaisesRegex(ValueError, "no APK"):
                select_apk(metadata, "x86_64")
            metadata.write_text(json.dumps(dict(elements=[elements[0], elements[0]])))
            with self.assertRaisesRegex(ValueError, "ambiguous"):
                select_apk(metadata, "x86_64")


if __name__ == "__main__":
    unittest.main()
