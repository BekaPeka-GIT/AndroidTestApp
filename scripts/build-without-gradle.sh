#!/usr/bin/env bash
# Awaryjne budowanie debug APK bez Gradle/AGP (gdy Google Maven jest niedostępny).
# Wymaga: ANDROID_SDK (platforms/android-23, build-tools z aapt2/zipalign/apksigner),
#         kotlinc, dalvik-exchange (dx), keytool (JDK).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK:-$HOME/android-sdk}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
ANDROID_JAR="$SDK/platforms/android-23/android.jar"
KOTLINC="${KOTLINC:-kotlinc}"
SRC="$ROOT/app/src/main"
OUT="$ROOT/build/manual"
PKG=com.example.helloworld

rm -rf "$OUT"; mkdir -p "$OUT"/{res,gen,classes,dex}

# Manifest z atrybutem package (w Gradle podaje go "namespace")
sed "s|<manifest |<manifest package=\"$PKG\" android:versionCode=\"1\" android:versionName=\"1.0\" |" \
  "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
sed -i "s|<application|<uses-sdk android:minSdkVersion=\"21\" android:targetSdkVersion=\"23\" />\n    <application|" "$OUT/AndroidManifest.xml"

"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res/res.zip"
"$BT/aapt2" link -I "$ANDROID_JAR" --manifest "$OUT/AndroidManifest.xml" \
  --java "$OUT/gen" -o "$OUT/base.apk" "$OUT/res/res.zip"

"$KOTLINC" -nowarn -jvm-target 1.8 -cp "$ANDROID_JAR" -d "$OUT/classes" \
  $(find "$SRC/java" "$OUT/gen" -name '*.kt' -o -name '*.java' | tr '\n' ' ') 2>&1 | grep -v '^Picked up' || true

# Uwaga: MainActivity nie używa klas kotlin-stdlib w runtime (tylko adnotacja @Metadata),
# więc stdlib nie jest dołączany. Większy kod wymagałby d8/R8 z AGP.
dalvik-exchange --dex --min-sdk-version=21 --output="$OUT/dex/classes.dex" "$OUT/classes"

cp "$OUT/base.apk" "$OUT/unaligned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unaligned.apk" classes.dex)
"$BT/zipalign" -f 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

KS="$OUT/debug.keystore"
keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" 2>&1 | grep -v '^Picked up' || true
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out "$OUT/HelloWorld-debug.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --verbose "$OUT/HelloWorld-debug.apk" | head -5
echo "APK: $OUT/HelloWorld-debug.apk"
