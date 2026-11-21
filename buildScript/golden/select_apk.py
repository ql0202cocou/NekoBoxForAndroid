"""从 AGP 产物清单选出供当前设备安装的 APK。"""
import json
import sys
from pathlib import Path


def select_apk(metadata, abi):
    elements = json.loads(metadata.read_text(encoding="utf-8"))["elements"]
    for filters in ([{"filterType": "ABI", "value": abi}], []):
        matches = [item for item in elements if item["filters"] == filters]
        if not matches:
            continue
        if len(matches) != 1:
            raise ValueError("ambiguous APK entries")
        item = matches[0]
        code, name = item["versionCode"], item["outputFile"]
        if type(code) is not int or code <= 0:
            raise ValueError("invalid APK versionCode")
        if not isinstance(name, str) or Path(name).name != name or not name or not name.isprintable():
            raise ValueError("invalid APK outputFile")
        apk = metadata.parent / name
        if not apk.is_file():
            raise ValueError(f"listed APK does not exist: {apk}")
        return code, apk
    raise ValueError(f"no APK for ABI {abi} and no universal APK")


if __name__ == "__main__":
    try:
        print(*select_apk(Path(sys.argv[1]), sys.argv[2]), sep="\n")
    except (OSError, KeyError, TypeError, ValueError) as error:
        sys.exit(f"{sys.argv[1]}: {error}")
