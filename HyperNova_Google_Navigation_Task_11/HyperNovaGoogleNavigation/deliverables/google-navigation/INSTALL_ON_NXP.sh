#!/usr/bin/env bash
set -e

ADB_SERIAL="${ADB_SERIAL:-192.168.0.100:5555}"
APK="$(cd "$(dirname "$0")" && pwd)/HyperNovaGoogleNavigation-debug.apk"
PKG="com.hypernova.navigation"

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
echo "GOOGLE PLAY SERVICES"
echo "============================================================"

if adb -s "$ADB_SERIAL" shell pm path com.google.android.gms \
    2>/dev/null | grep -q '^package:'; then
    echo "Google Play Services: PRESENT"
    adb -s "$ADB_SERIAL" shell dumpsys package com.google.android.gms \
      | grep -m2 -E 'versionName=|versionCode=' || true
else
    echo "WARNING: Google Play Services NOT FOUND"
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
