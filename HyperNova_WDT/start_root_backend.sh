#!/usr/bin/env bash
set -euo pipefail

ADB_SERIAL="${ADB_SERIAL:-192.168.0.100:5555}"
PACKAGE="com.hypernova.wdt"
CLASS="com.hypernova.wdt.RootBackendServer"
LOG="/data/local/tmp/hypernova-wdt-backend.log"

adb -s "$ADB_SERIAL" get-state >/dev/null

APK_PATH="$(
  adb -s "$ADB_SERIAL" shell pm path "$PACKAGE" \
    | tr -d '\r' \
    | sed -n 's/^package://p' \
    | head -n1
)"

if [[ -z "$APK_PATH" ]]; then
  echo "ERROR: $PACKAGE is not installed on $ADB_SERIAL" >&2
  exit 1
fi

echo "APK: $APK_PATH"

echo "Stopping old backend if present..."
adb -s "$ADB_SERIAL" shell \
  "su 0 sh -c '/system/bin/toybox pkill -f \"[c]om.hypernova.wdt.RootBackendServer\" 2>/dev/null || true'" \
  >/dev/null || true

echo "Starting root backend..."
adb -s "$ADB_SERIAL" shell \
  "su 0 sh -c 'rm -f $LOG; CLASSPATH=\"$APK_PATH\" /system/bin/toybox nohup /system/bin/app_process /system/bin $CLASS >$LOG 2>&1 </dev/null &'"

sleep 1

echo
echo "========================================"
echo "ROOT BACKEND LOG"
echo "========================================"
adb -s "$ADB_SERIAL" shell "su 0 sh -c 'cat $LOG 2>/dev/null || true'"

echo
echo "========================================"
echo "ROOT BACKEND PROCESS"
echo "========================================"
adb -s "$ADB_SERIAL" shell \
  "su 0 sh -c '/system/bin/toybox ps -A -o PID,ARGS | grep \"[R]ootBackendServer\" || true'"

echo
echo "If the log contains READY 127.0.0.1:47631, reopen the WDT app."
