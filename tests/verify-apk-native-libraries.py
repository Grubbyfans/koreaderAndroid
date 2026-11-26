"""Verify that distribution APKs use system TTS without bundled Sherpa/ONNX libraries."""

import argparse
from pathlib import Path
from zipfile import ZipFile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, nargs="+")
    args = parser.parse_args()
    violations = []
    for apk in args.apk:
        with ZipFile(apk) as archive:
            native_libraries = [
                entry for entry in archive.namelist()
                if entry.startswith("lib/") and entry.endswith(".so")
            ]
        found = [
            entry for entry in native_libraries
            if Path(entry).name.lower().startswith(("libsherpa", "libonnxruntime"))
        ]
        violations.extend(f"{apk}: {entry}" for entry in found)
        if not found:
            print(f"{apk}: no Sherpa/ONNX native libraries")
    if violations:
        parser.exit(1, "\n".join(violations) + "\n")


if __name__ == "__main__":
    main()
