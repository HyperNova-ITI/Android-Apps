#!/usr/bin/env bash
set -e

ADB_SERIAL="${ADB_SERIAL:-192.168.0.100:5555}"
APK="$(cd "$(dirname "$0")" && pwd)/HyperNovaGoogleNavigation-debug.apk"
PKG="com.hypernova.navigation"
EXPECTED_CERT_SHA256="0192d46445395c15df170bb2f0765f7e0047a0a460628b0f075b5c46a2986ad0"
ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
BT="$(find "$ANDROID_SDK/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1)"
BACKUP_DIR="${BACKUP_DIR:-$(cd "$(dirname "$0")" && pwd)/rollback-before-google-navigation}"

[ -f "$APK" ] || { echo "ERROR: APK not found: $APK"; exit 1; }
[ -x "$BT/apksigner" ] || { echo "ERROR: apksigner not found under $ANDROID_SDK"; exit 1; }

APK_CERT_SHA256="$("$BT/apksigner" verify --print-certs "$APK" 2>/dev/null \
    | sed -n 's/.*SHA-256 digest: //p' | head -1)"
[ "$APK_CERT_SHA256" = "$EXPECTED_CERT_SHA256" ] \
    || { echo "ERROR: replacement APK has the wrong signing certificate"; exit 1; }

echo
echo "============================================================"
echo "HYPERNOVA GOOGLE NAVIGATION — NXP INSTALL"
echo "============================================================"

echo
echo "ADB target: $ADB_SERIAL"

adb connect "$ADB_SERIAL" || true

echo
echo "============================================================"
echo "DEVICE"
echo "============================================================"

adb -s "$ADB_SERIAL" get-state
adb -s "$ADB_SERIAL" shell getprop ro.build.version.release
adb -s "$ADB_SERIAL" shell getprop ro.build.version.sdk

echo
echo "============================================================"
echo "AOSP WEBVIEW"
echo "============================================================"

if adb -s "$ADB_SERIAL" shell dumpsys webviewupdate \
    2>/dev/null | grep -q 'Current WebView package'; then
    echo "Android WebView: PRESENT"
    adb -s "$ADB_SERIAL" shell dumpsys webviewupdate \
      | grep -m3 -E 'Current WebView package|Preferred WebView package|Valid package' || true
else
    echo "ERROR: a usable Android WebView was not found; keeping the existing Navigation app"
    exit 2
fi

echo
echo "============================================================"
echo "ANDROID INTERNET ROUTE"
echo "============================================================"

if adb -s "$ADB_SERIAL" shell ip route 2>/dev/null | grep -q '^default '; then
    adb -s "$ADB_SERIAL" shell ip route | grep '^default '
else
    echo "ERROR: Android has no default internet route; keeping the existing Navigation app"
    exit 2
fi

echo
echo "============================================================"
echo "BACK UP CURRENT NAVIGATION"
echo "============================================================"

CURRENT_PATH="$(adb -s "$ADB_SERIAL" shell pm path "$PKG" 2>/dev/null \
    | sed -n 's/^package://p' | head -1 | tr -d '\r')"
if [ -n "$CURRENT_PATH" ]; then
    mkdir -p "$BACKUP_DIR"
    CURRENT_APK="$BACKUP_DIR/HyperNovaNavigation-before-google.apk"
    adb -s "$ADB_SERIAL" pull "$CURRENT_PATH" "$CURRENT_APK" >/dev/null
    CURRENT_CERT_SHA256="$("$BT/apksigner" verify --print-certs "$CURRENT_APK" 2>/dev/null \
        | sed -n 's/.*SHA-256 digest: //p' | head -1)"
    [ "$CURRENT_CERT_SHA256" = "$APK_CERT_SHA256" ] \
        || { echo "ERROR: installed Navigation uses a different certificate; backup kept at $CURRENT_APK"; exit 3; }
    echo "Current APK backed up: $CURRENT_APK"
else
    echo "No existing Navigation package found."
fi

echo
echo "============================================================"
echo "INSTALL APK"
echo "============================================================"

adb -s "$ADB_SERIAL" install -r -g "$APK"

echo
echo "============================================================"
echo "VERIFY PACKAGE"
echo "============================================================"

adb -s "$ADB_SERIAL" shell pm path "$PKG"

echo
echo "============================================================"
echo "START NAVIGATION"
echo "============================================================"

adb -s "$ADB_SERIAL" logcat -c
adb -s "$ADB_SERIAL" shell am force-stop "$PKG"

adb -s "$ADB_SERIAL" shell am start \
  -n "$PKG/.MainActivity"

echo
echo "============================================================"
echo "DONE"
echo "============================================================"
echo
echo "Navigation launched."
echo "Now inspect the IVI display."
