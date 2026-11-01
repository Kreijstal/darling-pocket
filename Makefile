# Darling Pocket: iOS apps on an Android phone, on Darling.
#
#   make                 the APK (out/darling-pocket.apk)
#   make runtime RUNTIME_ROOT=<arm64 install>/usr/local
#                        the runtime archive the APK accepts, with Cydia
#                        (make cydia: tools/make-cydia.sh) in it
#
# The APK pins the runtime archive's sha256, so build the archive first.
DARLING ?= $(abspath ../vibedarling-darling)
NDK ?= $(HOME)/.local/share/android-neutral-test/sdk/ndk/android-ndk-r27c
# The source-built APK tools aosp-pocket uses (manifest encoder, APK builder, signer).
TOOLCHAIN ?= $(abspath ../helloworldandroid)
ANDROID_JAR ?= $(TOOLCHAIN)/vendor/android-platforms/android-36/android.jar
R8 ?= $(abspath ../s20fe-hardware-lab/artifacts/lineage/source/prebuilts/r8/r8.jar)
LLVM := $(NDK)/toolchains/llvm/prebuilt/linux-x86_64/bin
CC_ANDROID := $(LLVM)/aarch64-linux-android28-clang
TOOLS := $(TOOLCHAIN)/build/tools
SIGNER := $(TOOLCHAIN)/build/apksigner.jar
LIB := out/lib/arm64-v8a
APK := out/darling-pocket.apk
JAVA := $(shell find app/src -name '*.java')
POCKET := $(DARLING)/tools/pocket-host
VERSION_CODE := 5
VERSION_NAME := 0.1.4
# Where the app downloads its runtime archive: the release of this version.
RUNTIME_URL ?= https://github.com/Kreijstal/darling-pocket/releases/download/v$(VERSION_NAME)/darling-runtime.tar.gz
.DELETE_ON_ERROR:
.PHONY: all runtime cydia host-programs host-libs verify clean

all: $(APK)

runtime: cydia
	tools/make-runtime.sh $(RUNTIME_ROOT) $(CURDIR)/out/cydia/root

cydia:
	DARLING=$(DARLING) tools/make-cydia.sh

out/darling-runtime.sha256:
	@echo "Build the runtime archive first: make runtime RUNTIME_ROOT=<arm64 install>/usr/local"; exit 1

# Darling's host programs and the host libraries are built by their scripts,
# which skip what is up to date.
host-programs:
	tools/build-host-programs.sh

host-libs:
	tools/build-host-libs.sh

$(LIB)/libpocketdisplay.so: native/pocket-display.c $(POCKET)/pocket-server.c $(POCKET)/pocket-server.h
	mkdir -p $(@D)
	$(CC_ANDROID) -O2 -Wall -Wextra -Wno-missing-field-initializers -fPIC -shared -I$(POCKET) \
		native/pocket-display.c $(POCKET)/pocket-server.c -landroid -llog -Wl,-z,max-page-size=16384 -o $@
	$(LLVM)/llvm-strip $@

out/gen/dev/kreijstal/darlingpocket/BuildConfig.java: out/darling-runtime.sha256 Makefile
	mkdir -p $(@D)
	printf 'package dev.kreijstal.darlingpocket;\n\nfinal class BuildConfig {\n    static final String VERSION_NAME = "%s";\n    /** The runtime archive this APK accepts. */\n    static final String RUNTIME_SHA256 = "%s";\n    /** Where it downloads it from. */\n    static final String RUNTIME_URL = "%s";\n\n    private BuildConfig() {\n    }\n}\n' \
		"$(VERSION_NAME)" "$$(cat $<)" "$(RUNTIME_URL)" > $@

out/classes.dex: $(JAVA) out/gen/dev/kreijstal/darlingpocket/BuildConfig.java
	rm -rf out/classes out/dex
	mkdir -p out/classes out/dex
	javac --release 8 -Xlint:-options -classpath $(ANDROID_JAR) -d out/classes $(JAVA) \
		out/gen/dev/kreijstal/darlingpocket/BuildConfig.java
	jar cf out/classes.jar -C out/classes .
	java -cp $(R8) com.android.tools.r8.D8 --lib $(ANDROID_JAR) --min-api 28 --output out/dex out/classes.jar
	cp out/dex/classes.dex $@

out/AndroidManifest.xml: app/AndroidManifest.xml Makefile
	mkdir -p out
	java -cp $(TOOLS) com.example.build.BinaryXmlEncoder $< $@ 28 28 $(VERSION_CODE) $(VERSION_NAME)

out/base.apk: out/AndroidManifest.xml out/classes.dex
	java -cp $(TOOLS) com.example.build.ApkBuilder out/AndroidManifest.xml out/classes.dex $@

out/packed.apk: out/base.apk $(LIB)/libpocketdisplay.so $(wildcard $(LIB)/*.so) $(shell find runtime -type f) tools/pack.py
	@for lib in libdarling libmldr libdarlingserver libfreetype libfontconfig libpng libjpeg libtiff libgif; do \
		test -f $(LIB)/$$lib.so || { echo "$(LIB)/$$lib.so is missing: make host-programs host-libs"; exit 1; }; done
	python3 tools/pack.py out/base.apk $@

out/debug.keystore:
	mkdir -p out
	keytool -genkeypair -keystore $@ -alias debug -keyalg RSA -keysize 2048 -validity 3650 -storepass android \
		-keypass android -dname 'CN=Darling Pocket Development'

$(APK): out/packed.apk out/debug.keystore
	java -cp $(SIGNER) com.android.apksigner.ApkSignerTool sign --ks out/debug.keystore --ks-pass pass:android \
		--key-pass pass:android --out $@ $<

verify: $(APK)
	java -cp $(SIGNER) com.android.apksigner.ApkSignerTool verify --verbose $(APK)
	python3 tools/check-apk.py $(APK) $(LLVM) out/darling-runtime.tar.gz

# Keeps the signing key, so a rebuilt APK updates the installed one.
clean:
	find out -mindepth 1 -maxdepth 1 ! -name debug.keystore ! -name 'darling-runtime.*' -exec rm -rf {} +
