#!/usr/bin/env bash
# A-12 + size gate: inspects the built release APK's merged manifest.
#   usage: tools/check-apk.sh path/to/app-release.apk
set -euo pipefail
APK="$1"
AAPT2=$(ls -d "${ANDROID_HOME:-$ANDROID_SDK_ROOT}"/build-tools/*/aapt2 | sort -V | tail -1)
fail() { echo "CHECK FAILED: $*" >&2; exit 1; }

# Permissions: INTERNET, plus what Media3/AndroidX add on their own.
raw=$("$AAPT2" dump permissions "$APK")
echo "aapt2 dump permissions:"; echo "$raw" | sed 's/^/  | /'
# lines look like: uses-permission: name='android.permission.INTERNET'  (older aapt2: uses-permission: android.permission.INTERNET)
perms=$(echo "$raw" | sed -nE "s/^uses-permission(-sdk-23)?: *(name=')?([^' ]+).*/\3/p" | sort -u)
echo "permissions:"; echo "$perms" | sed 's/^/  /'
echo "$perms" | grep -qx "android.permission.INTERNET" || fail "INTERNET missing"
extra=$(echo "$perms" | grep -vx "android.permission.INTERNET" \
  | grep -vx "android.permission.ACCESS_NETWORK_STATE" \
  | grep -v "\.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION$" || true)
[ -z "$extra" ] || fail "unexpected permissions: $extra"

xml=$("$AAPT2" dump xmltree --file AndroidManifest.xml "$APK")
echo "$xml" | grep -q 'android:allowBackup.*=false' || fail "allowBackup is not false"
echo "$xml" | grep -q 'android.intent.category.LEANBACK_LAUNCHER' || fail "no LEANBACK_LAUNCHER"
echo "$xml" | grep -q 'android:banner' || fail "no banner"
echo "$xml" | grep -q 'android.software.leanback' || fail "no leanback feature"

size=$(stat -c %s "$APK")
echo "release APK size: $size bytes"
[ "$size" -le $((6 * 1024 * 1024)) ] || fail "APK larger than 6 MB"
echo "APK checks passed"
