#!/usr/bin/env bash
set -euo pipefail

APP="$(pwd)"
GRADLE="$APP/app/build.gradle.kts"

OUT="$APP/nxp_deploy"
BACKUP="$OUT/build.gradle.kts.original"
FINAL="$OUT/HyperNovaLauncher-NXP.apk"

mkdir -p "$OUT"

echo "========================================"
echo " BUILD HYPERNOVA LAUNCHER FOR NXP"
echo "========================================"

cp "$GRADLE" "$BACKUP"

restore() {
    cp "$BACKUP" "$GRADLE"
}
trap restore EXIT

python3 <<'PY'
from pathlib import Path

p = Path("app/build.gradle.kts")
s = p.read_text()

needle = 'applicationIdSuffix = ".dev"'

if needle not in s:
    raise SystemExit("[ERROR] applicationIdSuffix .dev not found")

s = s.replace(
    needle,
    '// NXP deployment uses production package id',
    1
)

p.write_text(s)

print("[OK] production package enabled temporarily")
PY

./gradlew \
    testDebugUnitTest \
    :app:assembleDebug \
    --no-daemon \
    --max-workers=10

APK="$APP/app/build/outputs/apk/debug/app-debug.apk"

test -f "$APK"

cp "$APK" "$FINAL"

restore
trap - EXIT

AAPT="$(find "$HOME/Android/Sdk/build-tools" -type f -name aapt | sort -V | tail -1)"

echo
echo "===== PACKAGE ====="
"$AAPT" dump badging "$FINAL" | head -1

PACKAGE="$("$AAPT" dump badging "$FINAL" | sed -n "s/package: name='\([^']*\)'.*/\1/p" | head -1)"

if [ "$PACKAGE" != "com.hypernova.launcher" ]; then
    echo "[ERROR] Wrong package: $PACKAGE"
    exit 1
fi

echo
echo "========================================"
echo " BUILD READY"
echo "========================================"
ls -lh "$FINAL"
echo
echo "$FINAL"
