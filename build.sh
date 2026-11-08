#!/usr/bin/env bash
# Build JuiceSSH Community from a user-supplied original APK.
#
# This repository ships patches + scripts only. It does NOT contain
# the original APK, any rebuilt APK, or signing keys.
#
# Requirements:
#   - JDK 17+            (java, keytool)
#   - apktool 3.x        (set APKTOOL=/path/to/apktool.jar)
#   - apksig 8.x+        (set APKSIG=/path/to/apksig.jar)
#   - Android framework-res.apk for a MODERN API level (see FRAMEWORK below)
#   - patch, unzip, zip
#
# FRAMEWORK (important)
#   apktool's bundled android-framework.jar is API 29 and does not know
#   android:pageSizeCompat or android:foregroundServiceType. Compiling
#   against it silently produces a wrong attribute encoding, and the
#   resulting APK fails to install with:
#     INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION: For input string: "enabled"
#   You must therefore register a real framework-res.apk from a device
#   running Android 14/15/16:
#     adb pull //system/framework/framework-res.apk ./framework-res.apk
#   and pass it as the 2nd argument (or set FRAMEWORK=...).
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC_APK="${1:-}"
FRAMEWORK="${2:-${FRAMEWORK:-}}"

if [[ -z "$SRC_APK" || ! -f "$SRC_APK" ]]; then
  cat >&2 <<'USAGE'
usage: ./build.sh /path/to/JuiceSSH+-+SSH+Client_3.2.2_APKPure.apk [framework-res.apk]

  framework-res.apk must come from an Android 14/15/16 device:
      adb pull //system/framework/framework-res.apk ./framework-res.apk
USAGE
  exit 64
fi

WORK="$ROOT/work"
DEC="$WORK/dec"
OUT="$ROOT/dist"

JAVA_BIN="${JAVA_BIN:-java}"
APKTOOL="${APKTOOL:-$ROOT/tools/apktool.jar}"
APKSIG="${APKSIG:-$ROOT/tools/apksig.jar}"
KS="${KEYSTORE:-$ROOT/keystore/community.jks}"
KS_ALIAS="${KS_ALIAS:-community}"
KS_PASS="${KS_PASS:-community}"

# apktool insists on a writable HOME for its framework cache.
export HOME="$ROOT/.home" USERPROFILE="$ROOT/.home" \
       APPDATA="$ROOT/.home" LOCALAPPDATA="$ROOT/.home"
mkdir -p "$HOME" "$WORK" "$OUT"

echo "==> [1/5] decoding $SRC_APK"
rm -rf "$DEC"
"$JAVA_BIN" -Xmx4g -jar "$APKTOOL" d -f -o "$DEC" "$SRC_APK" >/dev/null

echo "==> [2/5] installing framework-res.apk"
if [[ -n "$FRAMEWORK" && -f "$FRAMEWORK" ]]; then
  "$JAVA_BIN" -jar "$APKTOOL" if "$FRAMEWORK" >/dev/null
  echo "    registered $FRAMEWORK"
else
  echo "    !! FRAMEWORK not given - pageSizeCompat/foregroundServiceType"
  echo "    !! will be mis-encoded and the APK will fail to install."
  echo "    !! Re-run with a framework-res.apk from Android 14/15/16."
fi

echo "==> [3/5] applying patches"
for p in "$ROOT"/patches/*.patch; do
  echo "    $(basename "$p")"
  ( cd "$DEC" && patch -p1 --binary -s -i "$p" )
done

echo "==> [4/5] assembling"
UNSIGNED="$WORK/unsigned.apk"
"$JAVA_BIN" -Xmx4g -jar "$APKTOOL" b -f -o "$UNSIGNED" "$DEC" >/dev/null

echo "==> [5/5] signing"
if [[ ! -f "$KS" ]]; then
  echo "    creating keystore $KS"
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=JuiceSSH Community, O=Community Build, C=CN"
fi
"$JAVA_BIN" -cp "$APKSIG" "$ROOT/scripts/Sign.java" \
  "$KS" "$KS_ALIAS" "$KS_PASS" "$KS_PASS" \
  "$UNSIGNED" "$OUT/JuiceSSH-3.2.2-community.apk"

echo
echo "built: $OUT/JuiceSSH-3.2.2-community.apk"
echo "install: adb install -r dist/JuiceSSH-3.2.2-community.apk"
