#!/usr/bin/env bash
# Wave Launcher — aapt2/kotlinc/d8/apksigner build pipeline (no Gradle)
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/opt/jdk17}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="$HOME/.local/opt/android-sdk"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
KOTLINC="$HOME/.local/opt/kotlinc/bin"
OUT="build"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] aapt2 compile resources"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"

echo "[2/6] aapt2 link -> resources.apk + R.java"
"$BT/aapt2" link -o "$OUT/resources.apk" \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  --java "$OUT/gen" \
  --auto-add-overlay \
  --version-code 26 \
  --version-name 0.8.1 \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  "$OUT/res.zip"

echo "[3/6] kotlinc -> JVM classes"
"$KOTLINC/kotlinc" -Xmx3g \
  -classpath "$PLATFORM" \
  -jvm-target 17 -no-jdk -nowarn \
  -d "$OUT/classes" \
  src/com/salman/wavelauncher/*.kt "$OUT/gen/com/salman/wavelauncher/R.java"

echo "[4/6] d8 -> classes.dex (app + kotlin stdlib)"
"$BT/d8" --release --min-api 26 --lib "$PLATFORM" \
  --output "$OUT/dex" \
  "$HOME/.local/opt/kotlinc/lib/kotlin-stdlib.jar" \
  $(find "$OUT/classes" -name '*.class' | grep -v '\$DefaultImpls' || true)
ls "$OUT/dex"/*.dex

echo "[5/6] package -> unsigned.apk"
cp "$OUT/resources.apk" "$OUT/unsigned.apk"
python3 - "$OUT/unsigned.apk" "$OUT/dex" <<'PYEOF'
import sys, glob, zipfile
apk, dexdir = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    for dex in sorted(glob.glob(dexdir + '/*.dex')):
        z.write(dex, dex.split('/')[-1])
        print('packed', dex.split('/')[-1])
PYEOF

echo "[6/6] zipalign + sign"
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -alias wave -keyalg RSA \
    -keysize 2048 -validity 10000 -storepass wave123 -keypass wave123 \
    -dname "CN=Wave Launcher, OU=Dev, O=Salman, L=SF, ST=CA, C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:wave123 \
  --key-pass pass:wave123 --out WaveLauncher.apk "$OUT/aligned.apk"

echo "OK -> android/WaveLauncher.apk"
