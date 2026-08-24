#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

if [ -n "${NOVA_JAVA_HOME:-}" ]; then
    JAVA_HOME="$NOVA_JAVA_HOME"
elif [ -d /usr/lib/jvm/java-21-openjdk-amd64 ]; then
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
elif [ -d /usr/lib/jvm/java-17-openjdk-amd64 ]; then
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
elif [ -n "${JAVA_HOME:-}" ]; then
    :
else
    echo "No supported JDK found. Set NOVA_JAVA_HOME to JDK 17 or 21." >&2
    exit 1
fi

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

LOG="$PROJECT/logs/prepare_mustafa_apk.log"
OUT="$PROJECT/deliverables/google-navigation"
SRC="$PROJECT/app/build/outputs/apk/debug/app-debug.apk"
DST="$OUT/HyperNovaGoogleNavigation-debug.apk"

if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    SDK_ROOT="$ANDROID_SDK_ROOT"
elif [ -n "${ANDROID_HOME:-}" ]; then
    SDK_ROOT="$ANDROID_HOME"
elif [ -f "$PROJECT/local.properties" ]; then
    SDK_ROOT="$(sed -n 's/^sdk\.dir=//p' "$PROJECT/local.properties")"
else
    SDK_ROOT="$HOME/Android/Sdk"
fi

BT="$(find "$SDK_ROOT/build-tools" \
    -mindepth 1 -maxdepth 1 -type d 2>/dev/null \
    | sort -V | tail -1)"

mkdir -p "$OUT" "$PROJECT/logs"

: > "$LOG"

log() {
    echo "$1"
    echo "$1" >> "$LOG"
}

fail() {
    echo
    echo "============================================================"
    echo "FAILED: $1"
    echo "============================================================"
    echo
    echo "Last 60 log lines:"
    tail -60 "$LOG"
    echo
    echo "Full log:"
    echo "$LOG"
    exit 1
}

run_gradle_step() {
    NAME="$1"
    TASK="$2"

    log ""
    log "============================================================"
    log "$NAME"
    log "============================================================"

    "$PROJECT/gradlew" \
        -p "$PROJECT" \
        "$TASK" \
        --max-workers=10 \
        --no-daemon \
        --console=plain \
        >> "$LOG" 2>&1 \
        || fail "$NAME"

    log "$NAME: PASS"
}

log "============================================================"
log "HYPERNOVA GOOGLE NAVIGATION — MUSTAFA APK PREPARATION"
log "============================================================"

log ""
log "Project:"
log "$PROJECT"

log ""
log "============================================================"
log "1. VERIFY PROJECT"
log "============================================================"

[ -f "$PROJECT/gradlew" ] \
    || fail "gradlew not found"

chmod +x "$PROJECT/gradlew"

[ -f "$PROJECT/secrets.properties" ] \
    || fail "secrets.properties not found"

if grep -Eq '^MAPS_API_KEY=AIza[0-9A-Za-z_-]{30,}$' "$PROJECT/secrets.properties"; then
    log "MAPS_API_KEY: CONFIGURED"
else
    fail "MAPS_API_KEY is missing or is not a Google API key"
fi

if git -C "$PROJECT" check-ignore secrets.properties >/dev/null 2>&1; then
    log "secrets.properties: GIT-IGNORED"
else
    fail "secrets.properties is NOT ignored by Git"
fi

[ -n "$BT" ] || fail "Android build-tools directory not found"
[ -x "$BT/apksigner" ] || fail "apksigner not found"
[ -x "$BT/aapt" ] || fail "aapt not found"

log "Build tools: $BT"
log "Java: $JAVA_HOME"

run_gradle_step \
    "2. ASSEMBLE DEBUG" \
    ":app:assembleDebug"

CONFIGURED_MAPS_KEY="$(sed -n 's/^MAPS_API_KEY=//p' "$PROJECT/secrets.properties")"
[ -f "$SRC" ] || fail "Built APK not found after assemble"
if grep -aFq "$CONFIGURED_MAPS_KEY" "$SRC"; then
    log "MAPS_API_KEY embedded value: VERIFIED"
else
    fail "Built APK does not contain the configured MAPS_API_KEY"
fi
unset CONFIGURED_MAPS_KEY

run_gradle_step \
    "3. UNIT TESTS" \
    ":app:testDebugUnitTest"

run_gradle_step \
    "4. LINT" \
    ":app:lintDebug"

log ""
log "============================================================"
log "5. CREATE DELIVERABLE"
log "============================================================"

[ -f "$SRC" ] \
    || fail "Built APK not found"

cp -f "$SRC" "$DST" \
    || fail "Could not copy APK"

log "APK copied:"
log "$DST"

log ""
log "============================================================"
log "6. APK INFO"
log "============================================================"

{
    ls -lh "$DST"

    "$BT/aapt" dump badging "$DST" \
        | grep -E "^package:|sdkVersion|targetSdkVersion|launchable-activity"

} >> "$LOG" 2>&1 || fail "APK inspection failed"

PACKAGE="$("$BT/aapt" dump badging "$DST" \
    | sed -n "s/package: name='\([^']*\)'.*/\1/p")"

if [ "$PACKAGE" != "com.hypernova.navigation" ]; then
    fail "Unexpected package: $PACKAGE"
fi

log "Package: $PACKAGE"

log ""
log "============================================================"
log "7. APK CERTIFICATE"
log "============================================================"

"$BT/apksigner" verify --print-certs "$DST" \
    >> "$LOG" 2>&1 \
    || fail "APK signature verification failed"

CERT_SHA1="$("$BT/apksigner" verify --print-certs "$DST" 2>/dev/null \
    | sed -n 's/.*SHA-1 digest: //p')"

log "Certificate SHA-1: $CERT_SHA1"

log ""
log "============================================================"
log "8. APK SHA256"
log "============================================================"

(
    cd "$OUT"
    sha256sum "$(basename "$DST")"
) | tee "$OUT/SHA256SUMS.txt" \
    >> "$LOG" \
    || fail "SHA256 generation failed"

APK_SHA256="$(sha256sum "$DST" | awk '{print $1}')"

log "APK SHA-256: $APK_SHA256"

log ""
log "============================================================"
log "READY TO SEND TO MUSTAFA"
log "============================================================"

echo
echo "ASSEMBLE DEBUG : PASS"
echo "UNIT TESTS     : PASS"
echo "LINT           : PASS"
echo "APK PACKAGE    : com.hypernova.navigation"
echo
echo "APK:"
echo "$DST"
echo
echo "APK SHA-256:"
echo "$APK_SHA256"
echo
echo "FULL LOG:"
echo "$LOG"
echo
echo "============================================================"
echo "READY TO SEND TO MUSTAFA"
echo "============================================================"
