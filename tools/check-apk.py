#!/usr/bin/env python3
"""Checks a built APK without a phone:

- the manifest, the dex and every library the app needs are there;
- each library is arm64, has 16 KB pages, and needs only libraries in the
  APK or Android's public NDK libraries;
- every native method PocketDisplay declares has a JNI symbol.
- the runtime archive matches the SHA-256 pinned in the APK's dex.

    check-apk.py APK NDK_LLVM_BIN [RUNTIME_ARCHIVE]
"""
import hashlib
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PUBLIC = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so", "libEGL.so",
          "libGLESv2.so", "libGLESv3.so", "libvulkan.so", "libjnigraphics.so", "libaaudio.so",
          "libOpenSLES.so", "libmediandk.so", "libnativewindow.so", "libsync.so"}
REQUIRED = ["libdarling.so", "libmldr.so", "libdarlingserver.so", "libpocketdisplay.so", "libfreetype.so",
            "libfontconfig.so", "libpng.so", "libjpeg.so", "libtiff.so", "libgif.so"]


def main():
    apk, llvm = Path(sys.argv[1]), Path(sys.argv[2])
    runtime = Path(sys.argv[3]) if len(sys.argv) > 3 else ROOT / "out/darling-runtime.tar.gz"
    failures = []

    def check(ok, what):
        print(("ok   " if ok else "FAIL ") + what)
        if not ok:
            failures.append(what)

    with zipfile.ZipFile(apk) as z, tempfile.TemporaryDirectory() as tmp:
        names = set(z.namelist())
        check("AndroidManifest.xml" in names, "manifest")
        check("classes.dex" in names, "classes.dex")
        check("assets/etc/fonts/fonts.conf" in names, "fontconfig's fonts.conf")
        check("assets/termux/pocketctl.py" in names, "Termux debugging's client")
        libs = sorted(n[len("lib/arm64-v8a/"):] for n in names if n.startswith("lib/arm64-v8a/"))
        for lib in REQUIRED:
            check(lib in libs, "ships " + lib)
        for lib in libs:
            path = Path(tmp) / lib
            path.write_bytes(z.read("lib/arm64-v8a/" + lib))
            headers = subprocess.run([str(llvm / "llvm-readelf"), "-h", "-l", "-d", str(path)],
                                     capture_output=True, text=True).stdout
            check("AArch64" in headers, lib + " is arm64")
            aligns = re.findall(r"^\s+LOAD\s+.*\s(0x[0-9a-f]+)\s*$", headers, re.M)
            check(aligns and all(int(a, 16) >= 0x4000 for a in aligns), lib + " has 16 KB pages")
            needed = re.findall(r"\(NEEDED\)\s+Shared library: \[(.+?)\]", headers)
            missing = [n for n in needed if n not in PUBLIC and n not in libs]
            check(not missing, lib + " needs only what Android has" + (": missing " + ", ".join(missing) if missing else ""))
            if lib in ("libdarling.so", "libmldr.so", "libdarlingserver.so"):
                check("program interpreter: /system/bin/linker64" in headers and "Type:                              DYN" in headers,
                      lib + " is an Android executable")

        dex = z.read("classes.dex")
        check(runtime.is_file(), "runtime archive exists")
        if runtime.is_file():
            with runtime.open("rb") as archive:
                digest = hashlib.file_digest(archive, "sha256").hexdigest()
            check(digest.encode("ascii") in dex, "runtime archive matches the APK's pinned SHA-256")
        check(b"Ldev/kreijstal/darlingpocket/PocketDisplay;" in dex, "the dex has PocketDisplay")
        display = Path(tmp) / "libpocketdisplay.so"
        if display.exists():
            exported = subprocess.run([str(llvm / "llvm-nm"), "-D", "--defined-only", str(display)],
                                      capture_output=True, text=True).stdout
            source = (ROOT / "app/src/dev/kreijstal/darlingpocket/PocketDisplay.java").read_text()
            for method in re.findall(r"static native \w+ (\w+)\(", source):
                symbol = "Java_dev_kreijstal_darlingpocket_PocketDisplay_" + method
                check(symbol in exported, "JNI " + method)
            check("JNI_OnLoad" in exported, "JNI_OnLoad")
    print("%d failures" % len(failures))
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
