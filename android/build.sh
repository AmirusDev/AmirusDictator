#!/usr/bin/env bash
# Builds ../AmirusDictator.apk without Gradle, using the Android SDK and Android Studio's bundled JDK.
# Run from Git Bash:  bash android/build.sh
set -euo pipefail
cd "$(dirname "$0")"

VERSION_CODE=1
VERSION_NAME=1.0
SDK=$(cygpath -m "${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}")
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"
export PATH="$JAVA_HOME/bin:$PATH"

rm -rf build && mkdir -p build/gen build/classes build/dex res/mipmap-xxxhdpi
cp ../icon-192.png res/mipmap-xxxhdpi/ic_launcher.png

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$PLATFORM" --manifest AndroidManifest.xml --java build/gen \
  --min-sdk-version 23 --target-sdk-version 34 --version-code $VERSION_CODE --version-name $VERSION_NAME build/res.zip
javac -encoding UTF-8 -source 8 -target 8 -nowarn -Xlint:-options -classpath "$PLATFORM" -d build/classes \
  $(find src build/gen -name '*.java')
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 23 --lib "$PLATFORM" --output build/dex \
  $(find build/classes -name '*.class')
(cd build/dex && python -c "import zipfile; z = zipfile.ZipFile('../base.apk', 'a', zipfile.ZIP_DEFLATED); z.write('classes.dex'); z.close()")
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk

# The signing key stays on this machine (git-ignored). Updates must be signed with the same key.
if [ ! -f release.keystore ]; then
  python -c "import secrets; open('keystore.pass', 'w').write(secrets.token_urlsafe(24))"
  keytool -genkeypair -keystore release.keystore -storepass:file keystore.pass -alias dictator \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Amirus Dictator"
fi
java -jar "$BT/lib/apksigner.jar" sign --ks release.keystore --ks-pass file:keystore.pass \
  --out ../AmirusDictator.apk build/aligned.apk
rm -f ../AmirusDictator.apk.idsig
ls -l ../AmirusDictator.apk
