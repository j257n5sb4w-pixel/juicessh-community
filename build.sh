#!/usr/bin/env bash
# Build JuiceSSH Community from a user-supplied original APK.
#
# Requirements: JDK 17, apktool 3.x, apksig (or use apksigner), a keystore.
# Nothing here redistributes the original APK or the built one.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC_APK="${1:-}"
if [[ -z "$SRC_APK" || ! -f "$SRC_APK" ]]; then
  echo "usage: $0 /path/to/JuiceSSH+-+SSH+Client_3.2.2_APKPure.apk" >&2
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

echo "==> [2/5] applying patches"
for p in "$ROOT"/patches/*.patch; do
  echo "    $(basename "$p")"
  ( cd "$DEC" && patch -p1 --binary -s -i "$p" )
done

echo "==> [3/5] assembling"
UNSIGNED="$WORK/unsigned.apk"
"$JAVA_BIN" -Xmx4g -jar "$APKTOOL" b -f -o "$UNSIGNED" "$DEC" >/dev/null

echo "==> [4/5] repairing android:pageSizeCompat"
FIXED="$WORK/fixed.apk"
"${PYTHON:-python3}" "$ROOT/scripts/repack_manifest.py" "$UNSIGNED" "$FIXED"

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
  "$KS" "$KS_ALIAS" "$KS_PASS" "$KS_PASS" "$FIXED" "$OUT/JuiceSSH-3.2.2-community.apk"

echo
echo "built: $OUT/JuiceSSH-3.2.2-community.apk"
