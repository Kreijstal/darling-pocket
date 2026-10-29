#!/usr/bin/env bash
# Builds Darling's host programs for Android arm64 with the NDK, and copies
# them into out/lib/arm64-v8a as the APK ships them: an app may execute only
# its native libraries, so each program is named lib<name>.so.
#
#   libdarling.so        the launcher (src/startup/darling.c)
#   libmldr.so           the Mach-O loader every guest process runs in
#   libdarlingserver.so  the kernel server
#
# The Mach-O side is not built here: it is the same for Linux and Android
# (see make-runtime.sh). The Darling tree is $DARLING (../vibedarling-darling),
# its Android build directory build-android.
set -euo pipefail

HERE=$(cd "$(dirname "$0")/.." && pwd)
DARLING=${DARLING:-$HERE/../vibedarling-darling}
NDK=${NDK:-$HOME/.local/share/android-neutral-test/sdk/ndk/android-ndk-r27c}
CACHE=${DARLING_ANDROID_CACHE:-$HOME/.cache/darling-android}
LLVM=$NDK/toolchains/llvm/prebuilt/linux-x86_64
OUT=$HERE/out/lib/arm64-v8a
PACKAGE=dev.kreijstal.darlingpocket
mkdir -p "$CACHE" "$OUT"

# darlingserver switches contexts with getcontext/swapcontext, which Bionic
# lacks; libucontext has them.
UCONTEXT_COMMIT=49e671dd52ff6791295d8161ad3b6da7dc5f6f9d
if [ ! -f "$CACHE/libucontext/libucontext.a" ]; then
	[ -d "$CACHE/libucontext" ] || git clone -q https://github.com/kaniini/libucontext.git "$CACHE/libucontext"
	git -C "$CACHE/libucontext" checkout -q "$UCONTEXT_COMMIT"
	make -s -C "$CACHE/libucontext" ARCH=aarch64 CC="$LLVM/bin/aarch64-linux-android28-clang" \
		AR="$LLVM/bin/llvm-ar" libucontext.a
fi

cat > "$CACHE/toolchain.cmake" <<TOOLCHAIN
set(ANDROID TRUE)
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)
set(CMAKE_SYSROOT $LLVM/sysroot)
set(CMAKE_C_COMPILER $LLVM/bin/clang)
set(CMAKE_CXX_COMPILER $LLVM/bin/clang++)
set(CMAKE_ASM_COMPILER $LLVM/bin/clang)
set(CMAKE_C_COMPILER_TARGET aarch64-linux-android28)
set(CMAKE_CXX_COMPILER_TARGET aarch64-linux-android28)
set(CMAKE_ASM_COMPILER_TARGET aarch64-linux-android28)
set(CMAKE_EXE_LINKER_FLAGS_INIT "-fuse-ld=lld -static-libstdc++ -Wl,-z,max-page-size=16384")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "-fuse-ld=lld -static-libstdc++ -Wl,-z,max-page-size=16384")
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
TOOLCHAIN

# The install prefix is where the app unpacks the runtime; the launcher also
# takes it from DARLING_INSTALL_PREFIX. COMPONENTS=core: only the host
# programs are built from this tree, and the GUI parts want X11 to configure.
BUILD=$DARLING/build-android
if [ ! -f "$BUILD/build.ninja" ]; then
	cmake -S "$DARLING" -B "$BUILD" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$CACHE/toolchain.cmake" \
		-DTARGET_ARM64=ON -DTARGET_x86_64=OFF -DTARGET_i386=OFF -DCOMPONENTS=core \
		-DHOST_CXX=/usr/bin/clang++ -DCMAKE_INSTALL_PREFIX=/data/data/$PACKAGE/files/darling \
		-DLIBUCONTEXT="$CACHE/libucontext/libucontext.a"
fi
ninja -C "$BUILD" darling mldr darlingserver

for program in src/startup/darling:libdarling.so src/startup/mldr/mldr:libmldr.so \
		src/external/darlingserver/darlingserver:libdarlingserver.so; do
	"$LLVM/bin/llvm-strip" -o "$OUT/${program#*:}" "$BUILD/${program%%:*}"
done
ls -l "$OUT"/libdarling.so "$OUT"/libmldr.so "$OUT"/libdarlingserver.so
