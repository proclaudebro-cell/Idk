#!/usr/bin/env bash
# Offline FloatTok build without the Android Gradle Plugin:
#   aapt2 (resources) -> kotlinc (code) -> R8 (dex + shrink) -> zipalign -> apksigner.
# Used where Google's Maven repository isn't reachable. CI builds the same sources with Gradle.
#
# Inputs (override with environment variables):
#   ANDROID_JAR_COMPILE  android.jar used to compile code (API 36)
#   ANDROID_JAR_LINK     android.jar used by aapt2 to link resources (aapt2 14 can't read API 35+ tables)
#   KOTLIN_DIR           directory with kotlin-compiler-embeddable and its dependency jars
#   R8_JAR               r8lib.jar
#   KEYSTORE / KEYSTORE_PROPS  signing key and its properties file (storePassword, keyAlias, keyPassword)
#   BUILD_TYPE           release (R8 shrinking) or debug (D8, debuggable)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
ANDROID_JAR_COMPILE="${ANDROID_JAR_COMPILE:-/root/dl-android36/android.jar}"
ANDROID_JAR_LINK="${ANDROID_JAR_LINK:-/root/dl-android34/android.jar}"
KOTLIN_DIR="${KOTLIN_DIR:-/root/toolchain/kotlin}"
R8_JAR="${R8_JAR:-/root/toolchain/r8/r8lib.jar}"
KEYSTORE="${KEYSTORE:-$ROOT/keystore/floattok.jks}"
KEYSTORE_PROPS="${KEYSTORE_PROPS:-$ROOT/keystore/keystore.properties}"
BUILD_TYPE="${BUILD_TYPE:-release}"
OUT="$ROOT/build-local/$BUILD_TYPE"

VERSION_CODE=$(grep -oP 'versionCode\s*=\s*\K[0-9]+' "$APP/build.gradle.kts")
VERSION_NAME=$(grep -oP 'versionName\s*=\s*"\K[^"]+' "$APP/build.gradle.kts")
MIN_SDK=$(grep -oP 'minSdk\s*=\s*\K[0-9]+' "$APP/build.gradle.kts")
TARGET_SDK=$(grep -oP 'targetSdk\s*=\s*\K[0-9]+' "$APP/build.gradle.kts")
STDLIB="$(ls "$KOTLIN_DIR"/kotlin-stdlib-[0-9]*.jar | head -1)"
KCP="$(ls "$KOTLIN_DIR"/*.jar | tr '\n' ':')"

echo "FloatTok $VERSION_NAME ($VERSION_CODE) $BUILD_TYPE  min=$MIN_SDK target=$TARGET_SDK"
rm -rf "$OUT"
mkdir -p "$OUT"/{res,gen,classes,dex}

echo "== aapt2 compile/link"
aapt2 compile --dir "$APP/src/main/res" -o "$OUT/res.zip"
DEBUG_FLAG=()
[ "$BUILD_TYPE" = debug ] && DEBUG_FLAG=(--debug-mode)
# AGP takes the package from `namespace`; standalone aapt2 needs it in the manifest.
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.floattok">|' \
  "$APP/src/main/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
aapt2 link -o "$OUT/base.apk" -I "$ANDROID_JAR_LINK" \
  --manifest "$OUT/AndroidManifest.xml" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$OUT/gen" --proguard "$OUT/aapt-rules.pro" --auto-add-overlay "${DEBUG_FLAG[@]}" \
  "$OUT/res.zip"

echo "== javac R"
javac -nowarn --release 17 -d "$OUT/classes" $(find "$OUT/gen" -name '*.java') 2>&1 | grep -v '^Picked up' || true

echo "== kotlinc"
java -Xmx2g -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-jdk -no-stdlib -no-reflect -jvm-target 17 -Xjsr305=strict \
  -classpath "$ANDROID_JAR_COMPILE:$STDLIB:$OUT/classes" \
  -d "$OUT/classes" "$APP/src/main/java" 2>&1 | { grep -v '^Picked up' || true; } | tee "$OUT/kotlinc.log"
if grep -q '^error:\|: error:' "$OUT/kotlinc.log"; then echo "kotlinc failed"; exit 1; fi
(cd "$OUT/classes" && jar cf "$OUT/classes.jar" .)

if [ "$BUILD_TYPE" = release ]; then
  echo "== R8"
  java -Xmx2g -cp "$R8_JAR" com.android.tools.r8.R8 --release --min-api "$MIN_SDK" \
    --lib "$ANDROID_JAR_COMPILE" --output "$OUT/dex" \
    --pg-conf "$OUT/aapt-rules.pro" --pg-conf "$APP/proguard-rules.pro" --pg-conf "$ROOT/tools/r8-extra.pro" \
    --pg-map-output "$OUT/mapping.txt" \
    "$OUT/classes.jar" "$STDLIB" 2>&1 | grep -v '^Picked up' || true
else
  echo "== D8"
  java -cp "$R8_JAR" com.android.tools.r8.D8 --debug --min-api "$MIN_SDK" --lib "$ANDROID_JAR_COMPILE" \
    --output "$OUT/dex" "$OUT/classes.jar" "$STDLIB" 2>&1 | grep -v '^Picked up' || true
fi
[ -f "$OUT/dex/classes.dex" ] || { echo "dex step failed"; exit 1; }

echo "== package"
cp "$OUT/base.apk" "$OUT/unaligned.apk"
(cd "$OUT/dex" && zip -q -X "$OUT/unaligned.apk" classes*.dex)
zipalign -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

echo "== sign"
if [ ! -f "$KEYSTORE" ]; then
  echo "No keystore at $KEYSTORE; creating a new signing key"
  mkdir -p "$(dirname "$KEYSTORE")"
  PASS=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)
  keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -storepass "$PASS" -keypass "$PASS" \
    -alias floattok -keyalg RSA -keysize 3072 -validity 18250 -dname "CN=FloatTok, O=Personal" 2>&1 | { grep -v '^Picked up' || true; }
  printf 'storePassword=%s\nkeyAlias=floattok\nkeyPassword=%s\n' "$PASS" "$PASS" > "$KEYSTORE_PROPS"
  chmod 600 "$KEYSTORE" "$KEYSTORE_PROPS"
fi
STORE_PASS=$(grep -oP '^storePassword=\K.*' "$KEYSTORE_PROPS")
KEY_ALIAS=$(grep -oP '^keyAlias=\K.*' "$KEYSTORE_PROPS")
KEY_PASS=$(grep -oP '^keyPassword=\K.*' "$KEYSTORE_PROPS")
APK="$OUT/FloatTok-$VERSION_NAME-$BUILD_TYPE.apk"
apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --out "$APK" "$OUT/aligned.apk" 2>&1 | grep -v '^Picked up' || true

echo "== verify"
apksigner verify --verbose "$APK" 2>&1 | { grep -v '^Picked up\|^WARNING' || true; }
zipalign -c -p 4 "$APK" && echo "zipalign OK"
ls -la "$APK"
