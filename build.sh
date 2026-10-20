#!/bin/bash
# Build the unified all-in-one APK (build/pluck.apk) containing:
#   - SysGuard Xposed hooks (io.github.wangxiaobo775.pluck.*)
#   - 毫毛 desktop UI & root backend (com.local.cloneui.*)
#   - Embedded VPN kill-switch & routing scripts (assets/clone-vpn.sh)
#
# Override package name at build time with PKG=your.package.name ./build.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")" && pwd)
TOOLS=${TOOLS:-/root/toolchain}
ANDROID_JAR=${ANDROID_JAR:-$TOOLS/android-34.jar}
R8=${R8:-$TOOLS/r8.jar}
KEYSTORE=${KEYSTORE:-$ROOT/debug.keystore}
PKG=${PKG:-}
OUT=$ROOT/build

if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" \
    -storepass android -keypass android -alias sysguard \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

rm -rf "$OUT"
mkdir -p "$OUT/stub-classes" "$OUT/classes" "$OUT/dex"

# Xposed API: compile against stubs, never package them.
find "$ROOT/stubs" -name '*.java' > "$OUT/stubs.list"
javac --release 17 -nowarn -encoding UTF-8 -d "$OUT/stub-classes" @"$OUT/stubs.list"

find "$ROOT/src" -name '*.java' > "$OUT/src.list"
javac --release 17 -Xlint:all,-options -Werror -encoding UTF-8 \
  -cp "$ANDROID_JAR:$OUT/stub-classes" -d "$OUT/classes" @"$OUT/src.list"

find "$OUT/classes" -name '*.class' > "$OUT/classes.list"
java -cp "$R8" com.android.tools.r8.D8 --release --min-api 29 \
  --lib "$ANDROID_JAR" --classpath "$OUT/stub-classes" \
  --output "$OUT/dex" @"$OUT/classes.list"

aapt2 compile --dir "$ROOT/res" -o "$OUT/res.zip"
LINK_ARGS=(--manifest "$ROOT/AndroidManifest.xml" -I "$ANDROID_JAR" -A "$ROOT/assets" --no-compress)
if [ -n "$PKG" ]; then
  LINK_ARGS+=(--rename-manifest-package "$PKG")
fi
# targetSdk 35: resources.arsc and dex must be stored uncompressed and 4-byte aligned.
aapt2 link "${LINK_ARGS[@]}" -o "$OUT/unaligned.apk" "$OUT/res.zip"
python3 - "$OUT/unaligned.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "a", zipfile.ZIP_STORED) as z:
    z.write(sys.argv[2], "classes.dex")
PY

zipalign -p -f 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias sysguard --out "$OUT/pluck.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/pluck.apk"
rm -f "$OUT/unaligned.apk" "$OUT/aligned.apk"
ln -sf pluck.apk "$OUT/clone-profiles.apk"
ln -sf pluck.apk "$OUT/sysguard.apk"
ln -sf pluck.apk "$OUT/clone-ui.apk"
echo "built $OUT/pluck.apk ($(stat -c %s "$OUT/pluck.apk") bytes)"
