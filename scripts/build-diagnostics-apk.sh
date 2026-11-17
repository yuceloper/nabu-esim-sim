#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
bt=""
for candidate in "$sdk"/build-tools/*; do
  if [[ -x "$candidate/aapt" && -x "$candidate/d8" && -x "$candidate/apksigner" && -x "$candidate/zipalign" ]]; then bt="$candidate"; fi
done
[[ -n "$bt" ]] || { echo "Android build-tools missing" >&2; exit 1; }
platform="$sdk/platforms/android-33/android.jar"
[[ -f "$platform" ]] || { echo "Install Android SDK Platform 33" >&2; exit 1; }
out=build/diagnostics-apk
mkdir -p "$out/classes" "$out/dex"
javac --release 8 -classpath "$platform" -d "$out/classes" app/src/com/nabucellular/diagnostics/MainActivity.java
"$bt/d8" --min-api 28 --lib "$platform" --output "$out/dex" "$out/classes/com/nabucellular/diagnostics/MainActivity.class"
"$bt/aapt" package -f -M app/AndroidManifest.xml -I "$platform" -F "$out/unsigned.apk"
(cd "$out/dex" && zip -q ../unsigned.apk classes.dex)
"$bt/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
key="$out/local-debug.keystore"
if [[ ! -f "$key" ]]; then
  keytool -genkeypair -keystore "$key" -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Nabu Local Debug" >/dev/null
fi
"$bt/apksigner" sign --ks "$key" --ks-pass pass:android --key-pass pass:android --out "$out/nabu-esim.apk" "$out/aligned.apk"
"$bt/apksigner" verify "$out/nabu-esim.apk"
echo "APK: $out/nabu-esim.apk — local debug key; do not commit build output"
