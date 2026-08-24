#!/usr/bin/env bash

ADB_SERIAL="${ADB_SERIAL:-192.168.0.100:5555}"
OUT="$(cd "$(dirname "$0")" && pwd)/nxp_google_navigation_runtime.txt"

adb -s "$ADB_SERIAL" logcat -d -v time \
  | grep -Ei \
'com\.hypernova\.navigation|HyperNova|NavigationApi|Navigator|GoogleNavigation|Google Maps|GooglePlaces|Places|NOT_AUTHORIZED|authorization|API.?key|CONFIGURATION_REQUIRED|TERMS_REQUIRED|TERMS_NOT_ACCEPTED|LOCATION_PERMISSION|LOCATION_UNAVAILABLE|GOOGLE_SERVICES|SecurityException|FATAL EXCEPTION|AndroidRuntime' \
  > "$OUT"

echo "Saved:"
echo "$OUT"
