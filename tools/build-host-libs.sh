#!/usr/bin/env bash
# Build the host ELF libraries that Darling's Mach-O wrappers dlopen() by
# file name (see vibedarling-darling/src/native/CMakeLists.txt, wrap_elf(...)),
# for Android arm64, as shared libraries an APK can ship in lib/arm64-v8a.
#
# Every library is named lib<name>.so with SONAME lib<name>.so (Android's
# packager keeps only lib*.so), and inter-library NEEDED entries use exactly
# those names. zlib is the NDK's public system libz.so.
#
#   libfreetype.so   FreeType 2 (PNG + zlib; no harfbuzz/brotli/bzip2)
#   libfontconfig.so fontconfig (expat linked in statically, symbols hidden)
#   libpng.so        libpng 1.6
#   libjpeg.so       libjpeg-turbo (libjpeg API)
#   libtiff.so       libtiff 4 (jpeg + zlib only)
#   libgif.so        giflib 5
#
# Layout:
#   $ROOT/src/dl/<tarball>          downloads (sha256-verified)
#   $ROOT/src/<pkg>-<ver>/          each tarball extracted into its own dir
#   $ROOT/build/<name>/             build trees
#   $ROOT/sysroot/{include,lib}     install prefix
#   <repo>/out/lib/arm64-v8a/       final stripped .so files
#
# Idempotent: a package whose build stamp exists (and whose library is in the
# sysroot) is skipped. Delete $ROOT/build/<name> to rebuild one package.
set -euo pipefail

# ---------------------------------------------------------------- config ---
NDK=${NDK:-$HOME/.local/share/android-neutral-test/sdk/ndk/android-ndk-r27c}
API=28
ABI=arm64-v8a
TARGET=aarch64-linux-android$API
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
CC=$TC/bin/$TARGET-clang
AR=$TC/bin/llvm-ar
STRIP=$TC/bin/llvm-strip
READELF=$TC/bin/llvm-readelf

ROOT=${DARLING_ANDROID_CACHE:-$HOME/.cache/darling-android}
SRC=$ROOT/src
DL=$SRC/dl
BUILD=$ROOT/build
SYSROOT=$ROOT/sysroot

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUT=$REPO/out/lib/$ABI

# On-device paths (app id dev.kreijstal.darlingpocket).
APP_DATA=/data/data/dev.kreijstal.darlingpocket
FC_SYSCONFDIR=$APP_DATA/files/etc
FC_CACHEDIR=$APP_DATA/cache/fontconfig
FC_DATADIR=$APP_DATA/files/share

JOBS=${JOBS:-$(nproc)}

CFLAGS_COMMON="-O2 -fPIC"
LDFLAGS_COMMON="-Wl,-z,max-page-size=16384 -Wl,--build-id=sha1"

# ------------------------------------------------------------- packages ---
# name|version|url|sha256
FREETYPE_VER=2.15.0
FONTCONFIG_VER=2.16.0
EXPAT_VER=2.9.0
LIBPNG_VER=1.6.59
LIBJPEG_TURBO_VER=3.2.1
TIFF_VER=4.7.2
GIFLIB_VER=5.2.2

declare -A URL SHA
URL[freetype]=https://download.savannah.gnu.org/releases/freetype/freetype-$FREETYPE_VER.tar.xz
SHA[freetype]=93cd47eb719c325107f2d3cf087d06299e944c3b48a40b55537302304dea1dd5
URL[fontconfig]=https://www.freedesktop.org/software/fontconfig/release/fontconfig-$FONTCONFIG_VER.tar.xz
SHA[fontconfig]=6a33dc555cc9ba8b10caf7695878ef134eeb36d0af366041f639b1da9b6ed220
URL[expat]=https://github.com/libexpat/libexpat/releases/download/R_${EXPAT_VER//./_}/expat-$EXPAT_VER.tar.xz
SHA[expat]=1e6371862cc31999b368c3b89b49994f0677e1bab5f1b2b85ae3741f5d803051
URL[libpng]=https://download.sourceforge.net/libpng/libpng-$LIBPNG_VER.tar.xz
SHA[libpng]=d80dd2a38a37f803cb9b6ac7b14bd6e74ddc3b654780a8380bdf93523fdb4389
URL[libjpeg-turbo]=https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/$LIBJPEG_TURBO_VER/libjpeg-turbo-$LIBJPEG_TURBO_VER.tar.gz
SHA[libjpeg-turbo]=c27ad72a68da151177929aa969ae773a2da5ac2af9ce946079314fdac8cece7f
URL[tiff]=https://download.osgeo.org/libtiff/tiff-$TIFF_VER.tar.xz
SHA[tiff]=4996f0c4f93094719b1ca5c6279b20e588773ba8a247533e486416fb662ddb88
URL[giflib]=https://download.sourceforge.net/giflib/giflib-$GIFLIB_VER.tar.gz
SHA[giflib]=be7ffbd057cadebe2aa144542fd90c6838c6a083b5e8a9048b8ee3b66b29d5fb

# -------------------------------------------------------------- helpers ---
log() { printf '\n=== %s\n' "$*" >&2; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

[[ -x $CC ]] || die "NDK clang not found: $CC"
for t in curl sha256sum tar cmake ninja meson pkg-config; do
	command -v "$t" >/dev/null || die "missing host tool: $t"
done

# fetch <name>: download (if needed) and verify; prints the tarball path.
fetch() {
	local name=$1 url=${URL[$1]} sha=${SHA[$1]}
	local file=$DL/${url##*/}
	mkdir -p "$DL"
	if [[ -f $file ]] && echo "$sha  $file" | sha256sum -c --status; then
		echo "$file"; return
	fi
	log "download $url"
	curl -fL --retry 3 -o "$file.part" "$url"
	echo "$sha  $file.part" | sha256sum -c --status \
		|| die "sha256 mismatch for $url (got $(sha256sum "$file.part" | cut -d' ' -f1))"
	mv "$file.part" "$file"
	echo "$file"
}

# unpack <name>: fresh extraction into $SRC/<base>/ ; prints the source dir.
unpack() {
	local file; file=$(fetch "$1")
	local base=${file##*/}; base=${base%.tar.*}
	local dir=$SRC/$base
	if [[ ! -f $dir/.extracted-${SHA[$1]} ]]; then
		[[ $dir == "$SRC"/?* ]] || die "bad source dir $dir"
		rm -rf -- "$dir"
		mkdir -p "$dir"
		tar -xf "$file" -C "$dir"
		touch "$dir/.extracted-${SHA[$1]}"
	fi
	[[ -d $dir/$base ]] || die "unexpected tarball layout in $file"
	echo "$dir/$base"
}

# done_stamp <name> <lib>: true if already built and installed.
built() { [[ -f $BUILD/$1/.built && -e $SYSROOT/lib/$2 ]]; }
mark_built() { touch "$BUILD/$1/.built"; }

fresh_builddir() {
	local d=$BUILD/$1
	[[ $d == "$BUILD"/?* ]] || die "bad build dir $d"
	rm -rf -- "$d"
	mkdir -p "$d"
	echo "$d"
}

CMAKE_COMMON=(
	-G Ninja
	-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
	-DANDROID_ABI=$ABI
	-DANDROID_PLATFORM=android-$API
	-DANDROID_STL=none
	-DCMAKE_BUILD_TYPE=Release
	-DCMAKE_INSTALL_PREFIX="$SYSROOT"
	-DCMAKE_INSTALL_LIBDIR=lib
	-DCMAKE_INSTALL_INCLUDEDIR=include
	-DCMAKE_FIND_ROOT_PATH="$SYSROOT"
	-DCMAKE_PREFIX_PATH="$SYSROOT"
	-DCMAKE_POSITION_INDEPENDENT_CODE=ON
	-DCMAKE_C_FLAGS="$CFLAGS_COMMON"
	-DCMAKE_SHARED_LINKER_FLAGS="$LDFLAGS_COMMON"
	-DCMAKE_EXE_LINKER_FLAGS="$LDFLAGS_COMMON"
)

export PKG_CONFIG_LIBDIR=$SYSROOT/lib/pkgconfig
export PKG_CONFIG_PATH=
export PKG_CONFIG_SYSROOT_DIR=

mkdir -p "$SRC" "$BUILD" "$SYSROOT/include" "$SYSROOT/lib" "$OUT"

# ------------------------------------------------------------- libjpeg ----
build_libjpeg() {
	built libjpeg libjpeg.so && return
	local s b; s=$(unpack libjpeg-turbo); b=$(fresh_builddir libjpeg)
	log "libjpeg-turbo $LIBJPEG_TURBO_VER"
	# On Android CMake drops versioned SONAMEs, so this is libjpeg.so/libjpeg.so.
	cmake -S "$s" -B "$b" "${CMAKE_COMMON[@]}" \
		-DENABLE_SHARED=ON -DENABLE_STATIC=OFF \
		-DWITH_TURBOJPEG=OFF -DWITH_TOOLS=OFF -DWITH_TESTS=OFF \
		-DWITH_SIMD=ON -DREQUIRE_SIMD=ON
	cmake --build "$b" -j "$JOBS"
	cmake --install "$b"
	mark_built libjpeg
}

# -------------------------------------------------------------- libpng ----
build_libpng() {
	built libpng libpng.so && [[ ! -L $SYSROOT/lib/libpng.so ]] && return
	local s b; s=$(unpack libpng); b=$(fresh_builddir libpng)
	log "libpng $LIBPNG_VER"
	# libpng's CMake always names the library png16 (libpng16.so). lld uses
	# the last -soname it sees, and CMAKE_C_STANDARD_LIBRARIES is appended
	# after CMake's own -soname, so this sets SONAME libpng.so. The file is
	# renamed to libpng.so after install (replacing the libpng.so symlink).
	cmake -S "$s" -B "$b" "${CMAKE_COMMON[@]}" \
		-DPNG_SHARED=ON -DPNG_STATIC=OFF -DPNG_FRAMEWORK=OFF \
		-DPNG_TESTS=OFF -DPNG_TOOLS=OFF -DPNG_EXECUTABLES=OFF \
		-DPNG_HARDWARE_OPTIMIZATIONS=ON \
		-DCMAKE_C_STANDARD_LIBRARIES="-lm -Wl,-soname,libpng.so"
	cmake --build "$b" -j "$JOBS"
	cmake --install "$b"
	local L=$SYSROOT/lib
	rm -f -- "$L/libpng.so"
	mv -- "$L/libpng16.so" "$L/libpng.so"
	# Drop CMake package files that point at libpng16.so; fix pkg-config.
	rm -rf -- "$L/cmake/PNG" "$L/libpng"
	sed -i 's/-lpng16/-lpng/' "$L/pkgconfig/libpng16.pc"
	[[ "$($READELF -d "$L/libpng.so" | sed -n 's/.*(SONAME).*\[\(.*\)\]/\1/p')" == libpng.so ]] \
		|| die "libpng SONAME override did not take effect"
	mark_built libpng
}

# ------------------------------------------------------------ freetype ----
build_freetype() {
	built freetype libfreetype.so && return
	local s b; s=$(unpack freetype); b=$(fresh_builddir freetype)
	log "freetype $FREETYPE_VER"
	cmake -S "$s" -B "$b" "${CMAKE_COMMON[@]}" \
		-DBUILD_SHARED_LIBS=ON \
		-DFT_REQUIRE_ZLIB=ON -DFT_REQUIRE_PNG=ON \
		-DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON -DFT_DISABLE_BZIP2=ON \
		-DPNG_LIBRARY="$SYSROOT/lib/libpng.so" \
		-DPNG_PNG_INCLUDE_DIR="$SYSROOT/include"
	cmake --build "$b" -j "$JOBS"
	cmake --install "$b"
	mark_built freetype
}

# --------------------------------------------------------------- expat ----
build_expat() {
	built expat libexpat.a && return
	local s b; s=$(unpack expat); b=$(fresh_builddir expat)
	log "expat $EXPAT_VER (static, PIC, for fontconfig)"
	cmake -S "$s" -B "$b" "${CMAKE_COMMON[@]}" \
		-DEXPAT_SHARED_LIBS=OFF -DBUILD_SHARED_LIBS=OFF \
		-DEXPAT_BUILD_TOOLS=OFF -DEXPAT_BUILD_EXAMPLES=OFF \
		-DEXPAT_BUILD_TESTS=OFF -DEXPAT_BUILD_DOCS=OFF \
		-DEXPAT_BUILD_FUZZERS=OFF -DEXPAT_BUILD_PKGCONFIG=ON
	cmake --build "$b" -j "$JOBS"
	cmake --install "$b"
	mark_built expat
}

# ---------------------------------------------------------- fontconfig ----
build_fontconfig() {
	built fontconfig libfontconfig.so && return
	local s b; s=$(unpack fontconfig); b=$(fresh_builddir fontconfig)
	log "fontconfig $FONTCONFIG_VER"
	# freetype2.pc and libpng16.pc say "Requires.private: zlib"; zlib is the
	# NDK's system libz.so (found by the compiler driver), so describe it.
	local zver
	zver=$(sed -n 's/^#define ZLIB_VERSION "\(.*\)".*/\1/p' "$TC/sysroot/usr/include/zlib.h")
	mkdir -p "$SYSROOT/lib/pkgconfig"
	printf 'Name: zlib\nDescription: zlib (Android NDK system library)\nVersion: %s\nLibs: -lz\nCflags:\n' \
		"$zver" > "$SYSROOT/lib/pkgconfig/zlib.pc"
	cat > "$b/cross.ini" <<-EOF
	[binaries]
	c = '$CC'
	ar = '$AR'
	strip = '$STRIP'
	pkg-config = '$(command -v pkg-config)'

	[built-in options]
	c_args = ['-O2', '-fPIC']
	c_link_args = ['-Wl,-z,max-page-size=16384', '-Wl,--build-id=sha1', '-Wl,--exclude-libs,ALL']

	[properties]
	pkg_config_libdir = '$SYSROOT/lib/pkgconfig'
	sys_root = ''

	[host_machine]
	system = 'android'
	subsystem = 'android'
	kernel = 'linux'
	cpu_family = 'aarch64'
	cpu = 'armv8-a'
	endian = 'little'
	EOF
	# --exclude-libs,ALL keeps expat's symbols (from the static libexpat.a)
	# out of libfontconfig.so's dynamic symbol table. Meson builds an
	# unversioned lib<name>.so (SONAME lib<name>.so) for host system android.
	meson setup "$b/build" "$s" \
		--cross-file "$b/cross.ini" \
		--wrap-mode=nofallback \
		--buildtype=release \
		--default-library=shared \
		--prefix="$SYSROOT" --libdir=lib --includedir=include \
		--sysconfdir="$FC_SYSCONFDIR" \
		--localstatedir="$APP_DATA/files/var" \
		-Dcache-dir="$FC_CACHEDIR" \
		-Dbaseconfig-dir="$FC_SYSCONFDIR/fonts" \
		-Dconfig-dir="$FC_SYSCONFDIR/fonts/conf.d" \
		-Dtemplate-dir="$FC_DATADIR/fontconfig/conf.avail" \
		-Dxml-dir="$FC_DATADIR/xml/fontconfig" \
		-Ddefault-fonts-dirs="/system/fonts,$APP_DATA/files/darling/libexec/darling/System/Library/Fonts,$APP_DATA/files/darling/libexec/darling/Library/Fonts" \
		-Dadditional-fonts-dirs=no \
		-Dxml-backend=expat \
		-Diconv=disabled -Dnls=disabled -Ddoc=disabled \
		-Dtests=disabled -Dtools=disabled -Dcache-build=disabled
	meson compile -C "$b/build" -j "$JOBS"
	# sysconfdir/datadir are on-device paths, so install into a DESTDIR and
	# copy only the library, headers and pkg-config file into the sysroot.
	DESTDIR="$b/destdir" meson install -C "$b/build" --no-rebuild
	cp -a "$b/destdir$SYSROOT/include/fontconfig" "$SYSROOT/include/"
	cp -a "$b/destdir$SYSROOT/lib/libfontconfig.so" "$SYSROOT/lib/"
	mkdir -p "$SYSROOT/lib/pkgconfig"
	cp -a "$b/destdir$SYSROOT/lib/pkgconfig/fontconfig.pc" "$SYSROOT/lib/pkgconfig/"
	mark_built fontconfig
}

# ---------------------------------------------------------------- tiff ----
build_tiff() {
	built tiff libtiff.so && return
	local s b; s=$(unpack tiff); b=$(fresh_builddir tiff)
	log "libtiff $TIFF_VER"
	cmake -S "$s" -B "$b" "${CMAKE_COMMON[@]}" \
		-DBUILD_SHARED_LIBS=ON -Dtiff-static=OFF \
		-Dtiff-tools=OFF -Dtiff-tests=OFF -Dtiff-contrib=OFF -Dtiff-docs=OFF \
		-Dtiff-cxx=OFF -Dtiff-opengl=OFF -Dsphinx=OFF \
		-Dzlib=ON -Dpixarlog=ON -Djpeg=ON -Dold-jpeg=ON -Djpeg12=OFF \
		-Dlibdeflate=OFF -Djbig=OFF -Dlerc=OFF -Dlzma=OFF -Dzstd=OFF -Dwebp=OFF \
		-DJPEG_LIBRARY="$SYSROOT/lib/libjpeg.so" \
		-DJPEG_INCLUDE_DIR="$SYSROOT/include" \
		-DCMAKE_DISABLE_FIND_PACKAGE_Deflate=ON \
		-DCMAKE_DISABLE_FIND_PACKAGE_JBIG=ON \
		-DCMAKE_DISABLE_FIND_PACKAGE_LERC=ON \
		-DCMAKE_DISABLE_FIND_PACKAGE_LibLZMA=ON \
		-DCMAKE_DISABLE_FIND_PACKAGE_ZSTD=ON \
		-DCMAKE_DISABLE_FIND_PACKAGE_WebP=ON \
		-DCMAKE_DISABLE_FIND_PACKAGE_OpenGL=ON \
		-DCMAKE_DISABLE_FIND_PACKAGE_GLUT=ON
	cmake --build "$b" -j "$JOBS"
	cmake --install "$b"
	mark_built tiff
}

# -------------------------------------------------------------- giflib ----
build_giflib() {
	built giflib libgif.so && return
	local s b; s=$(unpack giflib); b=$(fresh_builddir giflib)
	log "giflib $GIFLIB_VER"
	# giflib's Makefile builds in the source tree and hard-codes the SONAME
	# libgif.so.7, so compile its library sources (the Makefile's SOURCES
	# list) directly in the build dir.
	local srcs=(dgif_lib.c egif_lib.c gifalloc.c gif_err.c gif_font.c gif_hash.c openbsd-reallocarray.c)
	local objs=() f
	for f in "${srcs[@]}"; do
		"$CC" -std=gnu99 $CFLAGS_COMMON -Wall -I"$s" -c "$s/$f" -o "$b/${f%.c}.o"
		objs+=("$b/${f%.c}.o")
	done
	"$CC" -shared $LDFLAGS_COMMON -Wl,--no-undefined -Wl,-soname,libgif.so \
		-o "$b/libgif.so" "${objs[@]}"
	install -m 644 "$s/gif_lib.h" "$SYSROOT/include/gif_lib.h"
	install -m 755 "$b/libgif.so" "$SYSROOT/lib/libgif.so"
	mark_built giflib
}

build_libjpeg
build_libpng
build_freetype
build_expat
build_fontconfig
build_tiff
build_giflib

# ----------------------------------------------------------------- output --
log "copy stripped libraries to $OUT"
LIBS=(libfreetype.so libfontconfig.so libpng.so libjpeg.so libtiff.so libgif.so)
ALLOWED=" libc.so libm.so libdl.so libz.so liblog.so ${LIBS[*]} "
for l in "${LIBS[@]}"; do
	f=$SYSROOT/lib/$l
	[[ -f $f && ! -L $f ]] || die "$f missing or a symlink"
	soname=$("$READELF" -d "$f" | sed -n 's/.*(SONAME).*\[\(.*\)\]/\1/p')
	[[ $soname == "$l" ]] || die "$l has SONAME '$soname'"
	while read -r n; do
		[[ $ALLOWED == *" $n "* ]] || die "$l has unexpected NEEDED $n"
	done < <("$READELF" -d "$f" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
	"$STRIP" --strip-unneeded -o "$OUT/$l.tmp" "$f"
	mv -f "$OUT/$l.tmp" "$OUT/$l"
done

# ---------------------------------------------------------- fonts.conf -----
FONTS_CONF=$REPO/runtime/etc/fonts/fonts.conf
mkdir -p "${FONTS_CONF%/*}"
cat > "$FONTS_CONF.tmp" <<EOF
<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">
<!-- fontconfig configuration for darling-pocket.
     Installed on device as $FC_SYSCONFDIR/fonts/fonts.conf (the compiled-in
     default); FONTCONFIG_FILE overrides it at runtime. -->
<fontconfig>
	<dir>/system/fonts</dir>
	<dir>$APP_DATA/files/darling/libexec/darling/System/Library/Fonts</dir>
	<dir>$APP_DATA/files/darling/libexec/darling/Library/Fonts</dir>

	<cachedir>$FC_CACHEDIR</cachedir>

	<include ignore_missing="yes">conf.d</include>

	<config>
		<rescan><int>30</int></rescan>
	</config>
</fontconfig>
EOF
mv -f "$FONTS_CONF.tmp" "$FONTS_CONF"

log "done"
ls -l "$OUT"
