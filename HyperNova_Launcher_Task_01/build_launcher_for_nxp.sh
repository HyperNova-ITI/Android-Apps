#!/usr/bin/env bash
set -euo pipefail

APP="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$APP"

OUT="$APP/nxp_deploy"
FINAL="$OUT/HyperNovaLauncher-NXP.apk"

mkdir -p "$OUT"

echo "========================================"
echo " BUILD HYPERNOVA LAUNCHER FOR NXP"
echo "========================================"

./gradlew \
    -PnxpDeployment=true \
    testDebugUnitTest \
    :app:assembleDebug \
    --no-daemon \
    --max-workers=10

APK="$APP/app/build/outputs/apk/debug/app-debug.apk"

test -f "$APK"

cp "$APK" "$FINAL"

AAPT="$(find "$HOME/Android/Sdk/build-tools" -type f -name aapt | sort -V | tail -1)"

echo
echo "===== PACKAGE ====="
"$AAPT" dump badging "$FINAL" | sed -n '1p'

PACKAGE="$("$AAPT" dump badging "$FINAL" | sed -n "s/package: name='\([^']*\)'.*/\1/p")"

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
