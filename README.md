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
- **Alarms and notifications:** Darling's `usernotificationsd` writes the
  apps' scheduled notifications to `files/alarms.txt`
  (`$USERNOTIFICATIONS_SCHEDULE`). `Alarms` sets the first one with
  `AlarmManager`, as an alarm clock for alarms and timers.
  - When it goes off and the home screen runs, `Alarms` keeps the phone awake
    for a minute, and SpringBoard shows it.
  - Otherwise it is posted as an Android notification that opens the home
    screen. An alarm rings until it is dismissed.
  - The alarm is set again when the phone restarts and when the app is
    updated.
- **Time zones:** the runtime archive carries a zoneinfo tree, because
  Android has no `/usr/share/zoneinfo`. The phone's zone reaches the apps as
  `$TZ`.
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

Each published version pairs its APK with exactly one runtime archive. Use the APK
and archive from the same release; local rebuilds of `0.1` may pin different runtime
hashes even though Android displays the same version. Version `0.1.1` uses a new
version code and release URL, and the manager shows its version. `make verify` now
also hashes the runtime archive and requires that hash in the APK's dex; it rejects
mixed APK/runtime pairs before publication. Keep existing release assets immutable.

## Trying it on a phone

```sh
adb install -r out/darling-pocket.apk
adb shell mkdir -p /sdcard/Android/data/dev.kreijstal.darlingpocket/files
adb push out/darling-runtime.tar.gz /sdcard/Android/data/dev.kreijstal.darlingpocket/files/
```

Then open the app. Without a runtime, it downloads the archive it was built
for from this version's release (`RUNTIME_URL`, about 150 MB), unless adb
pushed one as above; **Install the runtime from a file** picks one. Either
way it checks the archive's sha256, unpacks it and creates the container,
which takes minutes the first time. **Install an
IPA** picks an `.ipa`. The app is debuggable for now, so
`adb shell run-as dev.kreijstal.darlingpocket cat files/logs/<App>.log` shows
an app's output, and `adb logcat -s DarlingPocket` shows the display's.

## Debugging from Termux

Without adb, Termux on the phone can run commands as the app: its logcat,
its files and logs, and Darling's container. In the manager, **Turn on Termux
debugging**, **Save the Termux client** (`pocketctl.py`, also
`runtime/termux/pocketctl.py`) and **Copy the connection token**. Then, in
Termux:

```sh
python pocketctl.py pair                 # paste the token
python pocketctl.py status
python pocketctl.py logcat               # the app's log, crashes too
python pocketctl.py shell -c 'tail files/logs/home.log'
python pocketctl.py darling ls /Applications
python pocketctl.py pull files/logs/home.log home.log
```

It is aosp-pocket's owner console: it listens on `127.0.0.1:2229` only, and a
client has to prove it holds the token (HMAC-SHA256 both ways). It stays on
across the app's restarts, so it outlives a crash, until it is turned off;
**Revoke the connection token** makes a new one.

## Not yet

- **Never run on Android.** In particular, darlingserver's cross-process
  memory calls haven't been tried under the `untrusted_app` SELinux domain.
- **One app at a time:** starting another one restarts the container.
- **Compositing:** whole frames are composed on the CPU, with no damage
  tracking.
- **Old-SDK apps** (linked against iOS 6) draw their bars under the status
  bar.
