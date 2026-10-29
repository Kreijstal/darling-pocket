# Darling Pocket

An Android app that runs unmodified arm64 iOS apps on an arm64 phone, on
[Darling](https://github.com/Kreijstal/darling) (branch `ios`). The plan is
Darling's `docs/ios-pocket.md`; this is its phase 4, the APK.

**Status: built, not yet run on a phone.** The APK builds, signs and passes
`make verify`. Its parts were tested off the phone:

- the display protocol and its server, on Linux (`ios-pocket-display`, 15/15
  in an arm64 VM);
- Darling started the way the app starts it, with the host programs only in a
  separate directory under `lib*.so` names (15/15 again);
- the runtime installer and the IPA installer, on a desktop JVM against the
  real runtime archive and real IPAs.

## How it fits together

- **Host programs:** Darling's launcher, `mldr` and `darlingserver`, built with
  the NDK.
  - They ship as `lib/arm64-v8a/libdarling.so`, `libmldr.so` and
    `libdarlingserver.so`, because Android executes only an app's native
    libraries.
  - `DARLING_MLDR_PATH` and `DARLING_SERVER_PATH` point Darling at them.
- **Host libraries:** FreeType, fontconfig, libpng, libjpeg, libtiff and giflib.
  Darling's Mach-O wrappers `dlopen()` them by these file names, and
  `LD_LIBRARY_PATH` points the guest's processes at them.
- **The runtime:** Darling's Mach-O tree (`libexec/darling`, the same as for
  arm64 Linux), packed as `darling-runtime.tar.gz`.
  - The APK accepts only the archive whose sha256 it was built with.
  - The app unpacks it into `files/darling`. The container is `files/p`, kept
    short for darlingserver's socket path.
- **The display:** `native/pocket-display.c`, a server for AppKit's Pocket
  backend (`PocketProtocol.h`). It shares Darling's
  `tools/pocket-host/pocket-server.c` with the Linux host.
  - **Frames:** apps hand it shared memory windows, and each frame is composed
    into a `SurfaceView`.
  - **Input:** touches and keys go to the apps.
  - **Keyboard:** while an app edits text, the view takes the soft keyboard. It
    types composing text as it changes.
  - **Safe area:** the status bar, the navigation bar and the cutout become the
    apps' safe area.
  - **Scale:** the density is rounded to a whole scale, since iOS apps bring
    @2x and @3x images.
- **The service:** `PocketService` is a foreground service. It owns the
  display and the running app (`darling shell env ... /Applications/X.app/X`),
  and writes each app's output to `files/logs/X.log`.
- **The manager:** `MainActivity` installs the runtime and IPAs and lists the
  apps. `ScreenActivity` is the screen. Back sends the screen to the
  background, like the Home button, and the app keeps running.

## Building

Needs the NDK (r27c), a JDK, and the source-built APK tools aosp-pocket uses
(`../helloworldandroid`, plus `r8.jar`; see the top of the `Makefile`).

```sh
tools/build-host-programs.sh      # Darling's host programs (Darling tree: ../vibedarling-darling)
tools/build-host-libs.sh          # FreeType, fontconfig, ...
make runtime RUNTIME_ROOT=<arm64 install>/usr/local
make verify                       # out/darling-pocket.apk, checked by tools/check-apk.py
```

`RUNTIME_ROOT` is an arm64 Darling build installed with
`DESTDIR=<dir> ninja install`: `<dir>/usr/local`.

## Trying it on a phone

```sh
adb install -r out/darling-pocket.apk
adb shell mkdir -p /sdcard/Android/data/dev.kreijstal.darlingpocket/files
adb push out/darling-runtime.tar.gz /sdcard/Android/data/dev.kreijstal.darlingpocket/files/
```

Then open the app and tap **Install the runtime**. That unpacks the runtime
and creates the container, which takes minutes the first time. **Install an
IPA** picks an `.ipa`. The app is debuggable for now, so
`adb shell run-as dev.kreijstal.darlingpocket cat files/logs/<App>.log` shows
an app's output, and `adb logcat -s DarlingPocket` shows the display's.

## Not yet

- **Never run on Android.** In particular, darlingserver's cross-process
  memory calls haven't been tried under the `untrusted_app` SELinux domain.
- **No downloads:** the runtime archive comes by adb or a file picker.
- **One app at a time:** starting another one restarts the container.
- **Keyboard:** apps aren't told about it, so it may cover a text field.
- **Compositing:** whole frames are composed on the CPU, with no damage
  tracking.
- **Old-SDK apps** (linked against iOS 6) draw their bars under the status
  bar.
