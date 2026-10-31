# Cydia's firmware.sh, for Darling (tools/make-cydia.sh applies this).
# The iOS version comes from SystemVersion.plist's iOSSupportVersion: sw_vers
# gives macOS's. The model's version comes from hw.machine's digits (iPhone12,1
# gives 12.1); Darling's is Linux's "aarch64", and an empty Version breaks dpkg.
s|^version=$(sw_vers -productVersion)$|version=$(sed -n '/<key>iOSSupportVersion<\\/key>/{n;s/.*<string>\\(.*\\)<\\/string>.*/\\1/p;}' /System/Library/CoreServices/SystemVersion.plist)\n[[ -n ${version} ]] \|\| version=$(sw_vers -productVersion)|
s|^\( *\)version=${version/,/.}$|\1version=${version/,/.}\n\1[[ -n ${version} ]] \|\| version=0|
