# Test Scenario

## Automated results

- Contracts debug AAR: PASS.
- Contracts Android test APK compilation: PASS.
- Contracts instrumentation test APK: 9 tests compile successfully; not run in
  this task because no device/Cuttlefish instance was launched.
- Navigation debug unit tests: 92/92 PASS.
- Navigation debug APK: PASS.
- Navigation release APK: PASS.
- Launcher debug unit tests: 22/22 PASS.
- Launcher debug APK: PASS.
- Launcher release APK: PASS.

## Manual route test

The debug producer and consumer APKs were installed together on the running
HyperNova Cockpit Android 14 device. The following test passed:

1. Boot Android and wait for HyperNova Launcher.
2. Confirm the Navigation card says no active route.
3. Open Navigation from the card.
4. Search for a real destination.
5. Select the destination and wait for OSRM route calculation.
6. Press Start so Navigation becomes ACTIVE.
7. Press HOME.
8. Confirm Launcher immediately shows the real destination.
9. Confirm `Route active`, planned duration, total route distance, derived
   arrival time, and cyan real route geometry are visible.
10. Finish navigation and return HOME; confirm the card says no active route
    and no cyan line or marker remains.

Observed route: Sheikh Zayed, 18 min, 8.9 km. The result came from a real OSRM
calculation initiated inside Navigation. Runtime evidence is saved in
`runtime-launcher-route-preview.png` and
`runtime-launcher-route-cleared.png`. A clean-log final refresh contained no
fatal exception, `TransactionTooLargeException`, Binder death, preview-request
failure, or security exception.

The AOSP-system-image version of this test remains pending until the missing
Navigation AOSP module is supplied or explicitly authorized.

## New large-map/live-arrow acceptance status

The previous textual/Canvas route scenario above remains valid evidence. The
new MapLibre road-map layout and continuous arrow flow compile and are covered
by contract, publisher-planner, mapper/reducer, projection, and dashboard-order
tests. A physical IVI/Cuttlefish runtime acceptance run was intentionally not
performed because the task explicitly requires stopping after APK replacement.

Run the following manually after building images and installing the matching
Navigation APK:

1. Verify Climate/Media and Settings/Phone order plus dominant Navigation.
2. Start a real OSRM route inside Navigation and confirm its arrow moves.
3. Press HOME and verify roads, real route, destination, real arrow, bearing,
   ETA, distance, and arrival.
4. Continue Navigation's existing location feed for at least ten seconds and
   confirm Launcher arrow updates without reopening HOME.
5. Cancel the route, return HOME, and verify route and arrow are absent.
6. Disable map networking and verify Canvas/text fallback without a crash.

## ADB verification

```bash
adb wait-for-device
adb shell 'until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done'
adb shell getprop sys.boot_completed
adb shell pidof system_server
adb shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN \
  -c android.intent.category.HOME
adb shell pm path com.hypernova.launcher
adb shell pm path com.hypernova.navigation
adb shell dumpsys package com.hypernova.launcher | grep -E \
  'versionName=|codePath=|READ_PRIVILEGED_PHONE_STATE|CONTROL_CAR_CLIMATE'
adb logcat -b all -d | grep -E \
  'privileged permissions not in privileged permission allowlist|READ_PRIVILEGED_PHONE_STATE|CONTROL_CAR_CLIMATE'
adb logcat -c
# Perform the manual route flow, then:
adb logcat -d -s NavigationStatusClient HN-NavigationAidl HN-NavigationStatus HyperNovaLauncher
```

The privileged-permission grep should produce no matches.

For route-preview Binder/UI failures:

```bash
adb logcat -d | grep -E \
  'TransactionTooLargeException|DeadObjectException|NavigationStatusClient|HN-NavigationStatus|AndroidRuntime|MapLibre'
adb shell uiautomator dump /sdcard/launcher.xml
adb pull /sdcard/launcher.xml
grep -E 'navigationRoutePreview|Route active|No active route' launcher.xml
```
