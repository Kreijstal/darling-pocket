#!/usr/bin/env bash
# Packs Darling's Mach-O tree for the app: out/darling-runtime.tar.gz and its
# sha256 (out/darling-runtime.sha256), which the APK is built to accept.
#
#   make-runtime.sh INSTALL_ROOT [OVERLAY_ROOT...]
#
# INSTALL_ROOT is an arm64 Darling install (`DESTDIR=... ninja install` of an
# arm64 build, then .../usr/local): the archive is its libexec/darling. That
# tree is the same for Linux and Android. Its host programs (mldr) are left
# out: the APK ships Android builds of them. Each OVERLAY_ROOT's
# libexec/darling is added over it (Cydia's, from make-cydia.sh).
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
ROOT=${1:?usage: make-runtime.sh INSTALL_ROOT [OVERLAY_ROOT...]}
shift
OVERLAYS=()
for overlay in "$@"; do
	[ -d "$overlay/libexec/darling" ] || { echo "no libexec/darling under $overlay" >&2; exit 1; }
	OVERLAYS+=(-C "$overlay" libexec/darling)
done
[ -d "$ROOT/libexec/darling/System" ] || { echo "no libexec/darling/System under $ROOT" >&2; exit 1; }
mkdir -p "$HERE/out"
# Darling links /usr/share/zoneinfo to the host's, and Android has none (its
# zones are one tzdata file): the archive carries this machine's instead.
# The app gives the phone's zone as $TZ.
ZONES=${ZONEINFO:-/usr/share/zoneinfo}
[ -f "$ZONES/Europe/Berlin" ] || { echo "no time zones at $ZONES (set ZONEINFO)" >&2; exit 1; }
TZROOT=$HERE/out/tz
rm -rf "$TZROOT"
mkdir -p "$TZROOT/libexec/darling/usr/share"
cp -a "$ZONES" "$TZROOT/libexec/darling/usr/share/zoneinfo"
rm -rf "$TZROOT/libexec/darling/usr/share/zoneinfo/posix" "$TZROOT/libexec/darling/usr/share/zoneinfo/right"
ARCHIVE=$HERE/out/darling-runtime.tar.gz
# Reproducible: sorted, no owners, no times. The zones go in by a second
# pass, as the exclusion of the host link would leave them out too.
TAR=(tar --format=gnu --sort=name --owner=0 --group=0 --numeric-owner --mtime=@0)
"${TAR[@]}" -C "$ROOT" \
	--exclude=libexec/darling/bin/mldr --exclude=libexec/darling/usr/libexec/darling/mldr \
	--exclude=libexec/darling/usr/share/zoneinfo \
	-cf "$ARCHIVE.tar" libexec/darling "${OVERLAYS[@]}"
"${TAR[@]}" -C "$TZROOT" -rf "$ARCHIVE.tar" libexec/darling/usr/share/zoneinfo
gzip -n -6 < "$ARCHIVE.tar" > "$ARCHIVE.part"
rm -f "$ARCHIVE.tar"
mv "$ARCHIVE.part" "$ARCHIVE"
sha256sum "$ARCHIVE" | cut -d' ' -f1 > "$HERE/out/darling-runtime.sha256"
ls -l "$ARCHIVE"
cat "$HERE/out/darling-runtime.sha256"
