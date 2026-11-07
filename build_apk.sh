set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
TC="${TOOLCHAIN:-$ROOT/.toolchain}"
if [ -n "$JAVA_HOME" ]; then
  JDK="$JAVA_HOME"
elif [ -d "$TC/jdk/jdk-17.0.20.1+1" ]; then
  JDK="$TC/jdk/jdk-17.0.20.1+1"
else
  JDK="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
fi
SDK="${ANDROID_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$TC/sdk}}}"
BT="$SDK/build-tools/30.0.3"
PLAT="$SDK/platforms/android-30/android.jar"
WORK="$TC/work"
SRC="$WORK/src"
OUT="$WORK/out"

if [ -f "$BT/aapt2.exe" ]; then EXE=".exe"; else EXE=""; fi

export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"

[ -f "$PLAT" ] || { echo "找不到 android.jar：$PLAT"; exit 1; }
[ -f "$BT/aapt2$EXE" ] || { echo "找不到 build-tools/30.0.3 的 aapt2：$BT"; exit 1; }

rm -rf "$WORK"
mkdir -p "$SRC" "$OUT/gen" "$OUT/classes" "$OUT/dex"

VER=$(grep -oE 'versionName[[:space:]]+"[^"]+"' "$ROOT/app/build.gradle" \
      | sed -E 's/.*"([^"]+)".*/\1/' | head -1)
CODE=$(grep -oE 'versionCode[[:space:]]+[0-9]+' "$ROOT/app/build.gradle" \
      | grep -oE '[0-9]+' | head -1)
[ -n "$VER" ] && [ -n "$CODE" ] || { echo "读不到版本号，检查 app/build.gradle"; exit 1; }
APK="$ROOT/apk/看得清-$VER.apk"
mkdir -p "$ROOT/apk"
echo "版本：$VER (versionCode $CODE) → apk/看得清-$VER.apk"

cp -r "$ROOT/app/src/main/." "$SRC/"

echo "== 1/6 aapt2 compile =="
"$BT/aapt2$EXE" compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "== 2/6 aapt2 link =="
"$BT/aapt2$EXE" link \
  -o "$OUT/base.apk" \
  -I "$PLAT" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 29 \
  --version-code "$CODE" \
  --version-name "$VER" \
  "$OUT/res.zip"

echo "== 3/6 javac =="
find "$SRC/java" "$OUT/gen" -name "*.java" > "$OUT/srcs.txt"
"$JDK/bin/javac$EXE" -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$PLAT" -d "$OUT/classes" "@$OUT/srcs.txt"

echo "== 4/6 d8 =="
"$JDK/bin/jar$EXE" cf "$OUT/classes.jar" -C "$OUT/classes" .
"$JDK/bin/java$EXE" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$PLAT" --release --min-api 24 --output "$OUT/dex" "$OUT/classes.jar"

echo "== 5/6 打包 =="
cd "$OUT"
cp base.apk unsigned.apk
cp dex/classes.dex ./classes.dex
"$JDK/bin/jar$EXE" uf unsigned.apk classes.dex
"$BT/zipalign$EXE" -f -p 4 unsigned.apk aligned.apk

echo "== 6/6 签名 =="
KS="$TC/debug.keystore"
ALIAS="${KEY_ALIAS:-androiddebugkey}"
STOREPASS="${KEYSTORE_PASSWORD:-android}"
KEYPASS="${KEY_PASSWORD:-$STOREPASS}"
if [ ! -f "$KS" ]; then
  if [ -n "$KEYSTORE_B64" ]; then
    printf '%s' "$KEYSTORE_B64" | base64 -d > "$KS"
    echo "已从 Secrets 恢复固定签名密钥"
  else
    echo "::warning::没有固定签名密钥，本次用临时调试密钥，装新版要卸载旧版"
    "$JDK/bin/keytool$EXE" -genkeypair -keystore "$KS" -alias androiddebugkey \
      -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
  fi
fi
"$JDK/bin/java$EXE" -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-key-alias "$ALIAS" \
  --ks-pass "pass:$STOREPASS" --key-pass "pass:$KEYPASS" \
  --out "$OUT/kanqing.apk" aligned.apk

cp "$OUT/kanqing.apk" "$APK"

echo "== 校验 =="
"$BT/aapt2$EXE" dump badging "$APK" | head -4
"$JDK/bin/java$EXE" -jar "$BT/lib/apksigner.jar" verify --print-certs "$APK" | head -2
ls -lh "$APK"
