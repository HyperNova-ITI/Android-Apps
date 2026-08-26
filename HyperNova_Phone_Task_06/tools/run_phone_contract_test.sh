#!/usr/bin/env bash
set -u

ROOT="/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06"
PHONE_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
CLIENT_APK="$ROOT/phone-contract-test/build/outputs/apk/debug/phone-contract-test-debug.apk"
ADB="${ADB:-adb}"

echo "============================================================"
echo "HyperNova Phone Contract Runtime Test"
echo "============================================================"

command -v "$ADB" >/dev/null 2>&1 || {
    echo "ERROR: adb not found"
    exit 1
}

"$ADB" get-state >/dev/null 2>&1 || {
    echo "ERROR: no adb device"
    exit 1
}

USER_ID="$("$ADB" shell am get-current-user 2>/dev/null | tr -d '\r')"
[[ -n "$USER_ID" ]] || USER_ID=0

echo "Current Android user: $USER_ID"

echo
echo "[1/6] Install Phone"
"$ADB" install -r "$PHONE_APK" || {
    echo
    echo "ERROR: Phone APK could not replace installed package."
    echo "If this is a platform-signed AAOS build, use a platform-signed test client."
    exit 2
}

echo
echo "[2/6] Install Test Client"
"$ADB" install -r "$CLIENT_APK" || {
    echo
    echo "ERROR: Test client install failed."
    echo "Check CONTROL_COCKPIT_APPS permission owner/signing certificate."
    exit 3
}

echo
echo "[3/6] Runtime permission grants"
for p in \
    android.permission.READ_CONTACTS \
    android.permission.READ_CALL_LOG \
    android.permission.CALL_PHONE \
    android.permission.BLUETOOTH_CONNECT \
    android.permission.READ_PHONE_STATE \
    android.permission.POST_NOTIFICATIONS
do
    "$ADB" shell pm grant \
        --user "$USER_ID" \
        com.hypernova.phone \
        "$p" \
        >/dev/null 2>&1 || true
done

echo "Grant attempts complete"

echo
echo "[4/6] Dialer role"
"$ADB" shell cmd role add-role-holder \
    --user "$USER_ID" \
    android.app.role.DIALER \
    com.hypernova.phone \
    >/dev/null 2>&1 || true

echo "Dialer role request complete"

echo
echo "[5/6] Permission verification"
"$ADB" shell dumpsys package com.hypernova.phone.contracttest 2>/dev/null |
    grep -n -A8 -B4 \
    'CONTROL_COCKPIT_APPS' || true

echo
echo "[6/6] Launch"
"$ADB" shell am start \
    --user "$USER_ID" \
    -n \
    com.hypernova.phone.contracttest/.MainActivity

echo
echo "============================================================"
echo "TEST CLIENT LAUNCHED"
echo "============================================================"
echo
echo "Safe order:"
echo "1. API VERSION"
echo "2. GET CURRENT STATE"
echo "3. SEARCH CONTACTS"
echo "4. GET CONTACT"
echo "5. HISTORY filters"
echo
echo "Then intentionally test real calls."
echo
echo "Logs:"
echo "adb logcat -s HN-PhoneCommand HN-Telecom HN-Hfp HN-Contacts HN-CallHistory"
