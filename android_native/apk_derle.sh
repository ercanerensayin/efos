#!/usr/bin/env bash
# EFOS-Ofis Android APK derleyicisi (Linux/macOS ve GitHub Actions için; apk_derle.ps1 ile aynı adımlar).
# İmza: EFOS_KEYSTORE / EFOS_KEY_ALIAS / EFOS_KEYSTORE_PASS / EFOS_KEY_PASS ayarlıysa yayın anahtarı,
# değilse android_native/debug.keystore kullanılır.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$ROOT")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"

PLATFORM="$SDK/platforms/android-35"
[ -d "$PLATFORM" ] || PLATFORM="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)"
BUILD_TOOLS="$SDK/build-tools/35.0.0"
[ -d "$BUILD_TOOLS" ] || BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"

ANDROID_JAR="$PLATFORM/android.jar"
AAPT2="$BUILD_TOOLS/aapt2"
D8_JAR="$BUILD_TOOLS/lib/d8.jar"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER_JAR="$BUILD_TOOLS/lib/apksigner.jar"
for req in "$ANDROID_JAR" "$AAPT2" "$D8_JAR" "$ZIPALIGN" "$APKSIGNER_JAR"; do
  [ -e "$req" ] || { echo "Gerekli Android SDK bileseni bulunamadi: $req" >&2; exit 1; }
done

BUILD="$ROOT/build"
DIST="$PROJECT_ROOT/dist"
rm -rf "$BUILD"
mkdir -p "$BUILD"/{res_compiled,gen,obj,test_obj,dex} "$DIST"

echo "[1/6] Cekirdek motor birim testleri calistiriliyor..."
javac -encoding UTF-8 -nowarn -d "$BUILD/test_obj" \
  "$ROOT/src/org/efosofis/mobile/FormulaEngine.java" \
  "$ROOT/src/org/efosofis/mobile/OfficeEngines.java" \
  "$ROOT/test/org/efosofis/mobile/SelfTest.java"
java -cp "$BUILD/test_obj" org.efosofis.mobile.SelfTest

echo "[2/6] Android kaynaklari (aapt2) derlenip baglaniyor..."
"$AAPT2" compile --dir "$ROOT/res" -o "$BUILD/res_compiled/resources.zip"
"$AAPT2" link -I "$ANDROID_JAR" --manifest "$ROOT/AndroidManifest.xml" \
  --java "$BUILD/gen" -o "$BUILD/base.apk" "$BUILD/res_compiled/resources.zip"

echo "[3/6] Android Java kaynaklari derleniyor (Java 8 bytecode)..."
find "$BUILD/gen" "$ROOT/src" -name '*.java' > "$BUILD/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -nowarn -Xlint:-options \
  -classpath "$ANDROID_JAR" -d "$BUILD/obj" @"$BUILD/sources.txt"

echo "[4/6] Dalvik bytecode (classes.dex) olusturuluyor (d8)..."
find "$BUILD/obj" -name '*.class' > "$BUILD/classes.txt"
java -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api 24 \
  --lib "$ANDROID_JAR" --output "$BUILD/dex" @"$BUILD/classes.txt"

echo "[5/6] classes.dex APK icine paketleniyor ve hizalaniyor (zipalign)..."
(cd "$BUILD/dex" && zip -q -9 "$BUILD/base.apk" classes.dex)
"$ZIPALIGN" -p -f 4 "$BUILD/base.apk" "$BUILD/aligned-imzasiz.apk"

echo "[6/6] APK imzalaniyor ve dogrulaniyor (apksigner)..."
if [ -n "${EFOS_KEYSTORE:-}" ]; then
  KS="$EFOS_KEYSTORE"; ALIAS="${EFOS_KEY_ALIAS:-efos-yayin}"
  SP="${EFOS_KEYSTORE_PASS:?EFOS_KEYSTORE_PASS gerekli}"; KP="${EFOS_KEY_PASS:-$SP}"
  echo "      Yayin anahtari kullaniliyor ($ALIAS)"
else
  KS="$ROOT/debug.keystore"; ALIAS="efosofis"; SP="android"; KP="android"
  [ -f "$KS" ] || { echo "debug.keystore bulunamadi: $KS" >&2; exit 1; }
  echo "      Kisisel (debug) anahtar kullaniliyor"
fi
java -jar "$APKSIGNER_JAR" sign --ks "$KS" --ks-key-alias "$ALIAS" \
  --ks-pass "pass:$SP" --key-pass "pass:$KP" --out "$DIST/EFOS-Ofis.apk" "$BUILD/aligned-imzasiz.apk"
java -jar "$APKSIGNER_JAR" verify --print-certs "$DIST/EFOS-Ofis.apk"
echo "[BASARILI] Android APK olusturuldu: $DIST/EFOS-Ofis.apk ($(du -k "$DIST/EFOS-Ofis.apk" | cut -f1) KB)"
