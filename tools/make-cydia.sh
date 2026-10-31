#!/usr/bin/env bash
# Cydia for the runtime archive: out/cydia/root/libexec/darling, laid out as
# the container's root, which make-runtime.sh adds to the archive. A new
# container is a copy of that tree, and cydia/setup finishes it on its first
# start.
#
#   make-cydia.sh
#
# What it holds:
#   /var/jb                   Procursus's rootless packages (cydia/debs.txt)
#                             unpacked: dpkg, APT and the tools Cydia runs.
#   /usr/share/darling-pocket/debs
#                             Cydia, its translations, mobilesubstrate (from
#                             Darling, $DARLING) and placeholders for what
#                             Cydia depends on that the runtime provides.
#   /usr/libexec/darling-pocket
#                             cydia/'s scripts: the setup, the home screen
#                             command, and stand-ins for Cydia's helpers.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
DARLING=${DARLING:-$HERE/../vibedarling-darling}
OUT=$HERE/out/cydia
CACHE=$OUT/cache
ROOT=$OUT/root/libexec/darling
WORK=$OUT/work
JB=$ROOT/private/var/jb
DEBS=$ROOT/usr/share/darling-pocket/debs
LIBEXEC=$ROOT/usr/libexec/darling-pocket

rm -rf "$OUT/root" "$WORK"
mkdir -p "$CACHE" "$JB" "$DEBS" "$LIBEXEC" "$WORK"

# Downloads, checked against their sha256.
fetch() { # url sha256 -> path
	local file=$CACHE/${1##*/}
	if ! echo "$2  $file" | sha256sum -c --quiet >/dev/null 2>&1; then
		curl -fsSL -o "$file.part" "$1"
		echo "$2  $file.part" | sha256sum -c --quiet >/dev/null 2>&1 \
			|| { echo "$1: not the sha256 in cydia/debs.txt" >&2; exit 1; }
		mv "$file.part" "$file"
	fi
	echo "$file"
}

# A deb's files, into a fresh directory.
unpack() { # deb dir
	rm -rf "$2"
	mkdir -p "$2/ar" "$2/data"
	(cd "$2/ar" && ar x "$1")
	tar -C "$2/data" --no-same-owner -xf "$2/ar"/data.tar.*
}

while read -r url sha kind; do
	case "$url" in ''|'#'*) continue ;; esac
	file=$(fetch "$url" "$sha")
	case "$kind" in
	procursus)
		# Rootless packages keep everything under /var/jb.
		unpack "$file" "$WORK/deb"
		tar -C "$WORK/deb/data/var/jb" -cf - . | tar -C "$JB" -xf -
		;;
	deb)
		cp "$file" "$DEBS/"
		;;
	*)
		echo "cydia/debs.txt: unknown kind $kind" >&2
		exit 1
		;;
	esac
done < "$HERE/cydia/debs.txt"

# dpkg's database is made by cydia/setup in the container itself: a container
# only overlays the runtime's directories, and dpkg backs its status file up
# with a hard link, which fails for a file of the runtime's.
rm -rf "$JB/Library/dpkg"

# Cydia hands GNU options to the tools it runs through cydo; GNU tar is here.
mkdir -p "$JB/gnu"
ln -sfn /var/jb/usr/bin/tar "$JB/gnu/tar"

# dpkg runs a package's scripts through libiosexec, which finds their
# interpreter under /var/jb: #!/bin/sh, which most use, needs an sh there.
# Procursus's comes with dash, not shipped; bash is.
ln -sfn /var/jb/usr/bin/bash "$JB/bin/sh"
ln -sfn bash "$JB/usr/bin/sh"

# Packages Cydia depends on whose parts the runtime already has: only so dpkg
# knows they are there.
placeholder() { # package version
	local dir=$WORK/placeholder
	rm -rf "$dir"
	mkdir -p "$dir/control" "$dir/data"
	cat > "$dir/control/control" <<CONTROL
Package: $1
Version: $2
Architecture: iphoneos-arm
Section: System
Priority: required
Maintainer: Darling Pocket
Description: provided by Darling Pocket's runtime
 Darling Pocket's runtime has what this package holds on iOS; this one only
 tells dpkg so.
CONTROL
	echo 2.0 > "$dir/debian-binary"
	tar -C "$dir/control" --owner=0 --group=0 -czf "$dir/control.tar.gz" .
	tar -C "$dir/data" --owner=0 --group=0 -czf "$dir/data.tar.gz" .
	(cd "$dir" && ar rc "$DEBS/$1_$2_darling.deb" debian-binary control.tar.gz data.tar.gz)
}
placeholder apt7-key 0.7.25.3-3
placeholder apt7-lib 0.7.25.3-16
placeholder darwintools 1-5
placeholder debianutils 3.3.3ubuntu1-1p
placeholder dpkg 1.22.6
placeholder org.thebigboss.repo.icons 1.0
placeholder sed 4.1.5-8
placeholder shell-cmds 118-7
placeholder system-cmds 433.8-14
placeholder uikittools 1.1.12
# What WinterBoard and other tweaks depend on: Darwin's killall and find,
# Darling's PreferenceLoader. pincrush (which crushes PNGs for old devices)
# has nothing to do here.
placeholder killall 1.0
placeholder findutils 4.6.0
placeholder preferenceloader 2.2.6
placeholder net.howett.pincrush 0.0.1-23

# Darling's own Substrate, as the package tweaks depend on.
sh "$DARLING/src/CydiaSubstrate/make-deb.sh" "$DEBS/mobilesubstrate_darling.deb"

# Cydia's firmware.sh, fixed for Darling.
cydia_deb=$(ls "$DEBS"/cydia_*.deb)
unpack "$cydia_deb" "$WORK/cydia"
sed -f "$HERE/cydia/firmware.sed" "$WORK/cydia/data/usr/libexec/cydia/firmware.sh" > "$LIBEXEC/firmware.sh"
[ "$(grep -c -e iOSSupportVersion -e 'version=0$' "$LIBEXEC/firmware.sh")" = 2 ] \
	|| { echo "cydia/firmware.sed no longer fits Cydia's firmware.sh" >&2; exit 1; }

install -m 755 "$HERE"/cydia/{setup,home,cydo,gssc,cfversion,setnsfpn,du} "$LIBEXEC/"
chmod 755 "$LIBEXEC/firmware.sh"
rm -rf "$WORK"
du -sh "$OUT/root"
