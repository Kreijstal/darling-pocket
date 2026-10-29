#!/usr/bin/env python3
"""Adds the native libraries and assets to the base APK (manifest + dex)."""
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    base, packed = Path(sys.argv[1]), Path(sys.argv[2])
    libs = sorted((ROOT / "out/lib/arm64-v8a").glob("lib*.so"))
    assets = [p for p in sorted((ROOT / "runtime").rglob("*")) if p.is_file()]
    with zipfile.ZipFile(base) as source, zipfile.ZipFile(packed, "w", zipfile.ZIP_DEFLATED) as apk:
        for entry in source.infolist():
            apk.writestr(entry, source.read(entry.filename))
        for lib in libs:
            apk.write(lib, "lib/arm64-v8a/" + lib.name)
        for asset in assets:
            apk.write(asset, "assets/" + asset.relative_to(ROOT / "runtime").as_posix())
    print("packed", packed, "with", ", ".join(l.name for l in libs))


if __name__ == "__main__":
    main()
