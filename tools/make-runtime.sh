#!/usr/bin/env bash
# Packs Darling's Mach-O tree for the app: out/darling-runtime.tar.gz and its
# sha256 (out/darling-runtime.sha256), which the APK is built to accept.
#
#   make-runtime.sh INSTALL_ROOT
#
# INSTALL_ROOT is an arm64 Darling install (`DESTDIR=... ninja install` of an
# arm64 build, then .../usr/local): the archive is its libexec/darling. That
# tree is the same for Linux and Android. Its host programs (mldr) are left
# out: the APK ships Android builds of them.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
ROOT=${1:?usage: make-runtime.sh INSTALL_ROOT}
[ -d "$ROOT/libexec/darling/System" ] || { echo "no libexec/darling/System under $ROOT" >&2; exit 1; }
mkdir -p "$HERE/out"
ARCHIVE=$HERE/out/darling-runtime.tar.gz
# Reproducible: sorted, no owners, no times.
tar -C "$ROOT" --format=gnu --sort=name --owner=0 --group=0 --numeric-owner --mtime=@0 \
	--exclude=libexec/darling/bin/mldr --exclude=libexec/darling/usr/libexec/darling/mldr \
	-cf - libexec/darling | gzip -n -6 > "$ARCHIVE.part"
mv "$ARCHIVE.part" "$ARCHIVE"
sha256sum "$ARCHIVE" | cut -d' ' -f1 > "$HERE/out/darling-runtime.sha256"
ls -l "$ARCHIVE"
cat "$HERE/out/darling-runtime.sha256"
