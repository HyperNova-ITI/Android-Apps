# HyperNova Launcher Boot Permission Regression Fix

## Root cause

The previous production Launcher APK requested two platform
signature/privileged permissions that were not accepted by the product's
effective privileged-permission allowlist:

- `android.permission.READ_PRIVILEGED_PHONE_STATE`
- `android.car.permission.CONTROL_CAR_CLIMATE`

During `PackageManagerService.systemReady()`, Android enforced the privileged
permission allowlist and threw `IllegalStateException`. That fatal exception
terminated `system_server`, so Cuttlefish could not complete boot and remained
on boot animation.

The correction removes the privileged requirements instead of granting more
power to the Launcher.

## Supported Launcher applications

Phone and Climate **are still supported applications**. The final Home keeps:

- NOVA AI, including its existing status client and AIDL behavior.
- Navigation, including package refresh, launch, and frozen AIDL state.
- Media, including MediaSession metadata, playback state, and controls.
- Settings, including package launch and read-only Android system summaries.
- Phone card, package availability, OPEN action, and launch fallback.
- Climate card, package availability, OPEN action, and launch fallback.
- Runtime handling for package add, remove, change, and replace broadcasts,
  plus lifecycle refresh when Home resumes.
- System/application light and dark theme behavior.

Installed, available, connected, active, and error remain separate concepts.

## Phone behavior after the correction

Removed Launcher-side access to Telecom call/account state and the privileged
phone-state permission. The Launcher no longer claims an active call, connected
phone, caller, contact, number, or device identity.

The card now displays authoritative package availability and, when observable,
whether Bluetooth itself is enabled. Bluetooth enabled is not treated as proof
that a phone is connected. An installed launchable app displays the neutral
`Phone available` state (or `Bluetooth is off` when that setting is confirmed),
and every Phone action still opens HyperNova Phone.

## Climate behavior after the correction

Removed direct `Car`, `CarPropertyManager`, and HVAC/VHAL observation from the
Launcher. Therefore the Launcher no longer needs
`android.car.permission.CONTROL_CAR_CLIMATE`.

The existing frozen Climate AIDL `getCurrentState()` request remains because it
is read-only. If an installed Climate app does not expose that service or the
service cannot be bound, the card displays the neutral `Climate available`
state and continues to open HyperNova Climate. Temperature, fan, power, A/C,
and AUTO values appear only when the existing contract returns confirmed real
state. No value is fabricated.

## Removed Home widgets

- The Weather quick card, click listener, view rendering, and widget-only
  strings were removed. Weather does not appear as installed or unavailable.
- The Driver Profile quick card, status-bar profile name/avatar, click
  listeners, view rendering, widget state, and widget-only resources were
  removed. Driver Profile does not appear as installed or unavailable.
- The remaining Settings quick card now uses the full row width while retaining
  the approved Launcher design language.

The legacy Weather and Driver Profile destination/registry records are harmless
non-visual infrastructure and were deliberately not refactored as part of this
scoped Home cleanup.

## Manifest permissions

Launcher source manifest before this correction:

| Permission | Result |
|---|---|
| `android.permission.MODIFY_DAY_NIGHT_MODE` | Kept for working system theme switching. |
| `com.hypernova.ai.permission.READ_STATUS` | Kept for NOVA status. |
| `com.hypernova.permission.CONTROL_COCKPIT_APPS` | Kept for existing read-only app contracts. |
| `android.permission.ACCESS_WIFI_STATE` | Kept for Settings summary. |
| `android.permission.READ_PHONE_STATE` | Removed because detailed Telecom observation was removed. |
| `android.permission.READ_PRIVILEGED_PHONE_STATE` | Removed; caused boot enforcement failure. |
| `android.car.permission.CONTROL_CAR_CLIMATE` | Removed; caused boot enforcement failure. |

The generated release APK requests only:

```text
android.permission.MODIFY_DAY_NIGHT_MODE
com.hypernova.ai.permission.READ_STATUS
com.hypernova.permission.CONTROL_COCKPIT_APPS
android.permission.ACCESS_WIFI_STATE
android.permission.ACCESS_NETWORK_STATE
com.hypernova.launcher.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
```

The last two are merged from Android/library components. Neither forbidden
permission is present in source, merged manifests, or the generated APK.

## Privapp allowlist

Before:

```text
android.permission.MODIFY_DAY_NIGHT_MODE
android.permission.READ_PRIVILEGED_PHONE_STATE
android.car.permission.CONTROL_CAR_CLIMATE
```

After:

```text
android.permission.MODIFY_DAY_NIGHT_MODE
```

File:

```text
/mnt/wwn-0x5002538e7006e10b-part3/device/hypernova/cockpit/permissions/privapp-permissions-hypernova-launcher.xml
```

## Launcher build and validation

```bash
cd /home/ayman/ITI/Android-Apps/HyperNova_Launcher_Task_01
./gradlew clean assembleDebug
./gradlew testDebugUnitTest lintDebug assembleRelease
```

Both commands completed successfully. The release build also completed
release lint-vital checks.

Production identity:

```text
Package: com.hypernova.launcher
HOME activity: com.hypernova.launcher.MainActivity
Source APK:
/home/ayman/ITI/Android-Apps/HyperNova_Launcher_Task_01/app/build/outputs/apk/release/app-release-unsigned.apk
SHA256: 9e67e5e19fa805bcdcc13f5eb823b142206289ef2ee706c8e087942489c8c119
```

The release artifact is unsigned because the existing AOSP
`android_app_import` signs it with the platform certificate.

APK inspection:

```bash
SDK=/home/ayman/Android/Sdk
APK=app/build/outputs/apk/release/app-release-unsigned.apk

$SDK/build-tools/36.1.0/aapt2 dump badging "$APK"
$SDK/build-tools/36.1.0/aapt2 dump xmltree \
  --file AndroidManifest.xml "$APK"
```

Verify neither forbidden permission is printed:

```bash
$SDK/build-tools/36.1.0/aapt2 dump badging "$APK" | \
  grep '^uses-permission:'
```

## AOSP deployment

```text
Previous imported APK SHA256:
f022976cd4aeb8a4e6fa38e6f36cbb5c520fd35f3de7e039f41372a945567764

Backup:
/home/ayman/ITI/Android-Apps/HyperNova_Launcher_Task_01/aosp-backup/HyperNovaLauncher-before-privileged-permission-fix.apk

Final AOSP APK:
/mnt/wwn-0x5002538e7006e10b-part3/device/hypernova/cockpit/apps/HyperNovaLauncher/HyperNovaLauncher.apk

Final imported APK SHA256:
9e67e5e19fa805bcdcc13f5eb823b142206289ef2ee706c8e087942489c8c119
```

Only `HyperNovaLauncher.apk` was replaced. Its existing `Android.bp` was not
changed, and no other AOSP app APK was replaced.

## Manual AOSP image build

The product tree declares the exact lunch target
`hypernova_cockpit_x86_64-trunk_staging-userdebug` and module
`HyperNovaLauncher`.

Run manually from a clean shell:

```bash
cd /mnt/wwn-0x5002538e7006e10b-part3
source build/envsetup.sh
lunch hypernova_cockpit_x86_64-trunk_staging-userdebug
m HyperNovaLauncher -j10
m systemextimage -j10
m superimage -j10
```

## Fresh Cuttlefish boot

After the three builds succeed, start with a newly created userdata image so a
stale instance cannot mask the result:

```bash
cd /mnt/wwn-0x5002538e7006e10b-part3
source build/envsetup.sh
lunch hypernova_cockpit_x86_64-trunk_staging-userdebug

$ANDROID_HOST_OUT/bin/stop_cvd || true
$ANDROID_HOST_OUT/bin/launch_cvd \
  --daemon=true \
  --resume=false \
  --data_policy=always_create \
  --report_anonymous_usage_stats=n
```

`always_create` intentionally discards the Cuttlefish userdata for a fresh
boot. Do not use it when userdata must be preserved.

## ADB verification

```bash
adb wait-for-device
adb shell getprop sys.boot_completed
# Expected: 1

adb shell pidof system_server
# Expected: one live PID

adb shell service check package
# Expected: Service package: found

adb shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN \
  -c android.intent.category.HOME
# Expected: com.hypernova.launcher/.MainActivity

adb shell pm path com.hypernova.launcher
adb shell dumpsys package com.hypernova.launcher | \
  grep -A20 'requested permissions:'
```

Assert that the fatal allowlist error did not occur:

```bash
if adb logcat -b all -d | grep -E \
  'Signature\|privileged permissions not in privileged permission allowlist|FATAL EXCEPTION IN SYSTEM PROCESS|READ_PRIVILEGED_PHONE_STATE|CONTROL_CAR_CLIMATE'; then
  echo 'FAIL: privileged-permission/system_server regression found'
else
  echo 'PASS: no privileged-permission boot regression found'
fi
```

Then exercise the Launcher without changing other apps:

```bash
adb shell input keyevent KEYCODE_HOME
adb shell dumpsys activity activities | grep -m1 'mResumedActivity'
adb logcat -d -s HyperNovaLauncher NavigationStatusClient MediaSessionClient \
  ClimateStatusClient PhoneStatusClient NovaStatusClient
```

## Rollback

The retained backup is the immediately preceding APK and has SHA256
`f022976cd4aeb8a4e6fa38e6f36cbb5c520fd35f3de7e039f41372a945567764`.
It contains the privileged permission requests that caused this regression, so
it must **not** be used to produce a boot image with the corrected one-entry
allowlist.

For a safe rollback, restore a known-good pre-regression Launcher artifact that
does not request the two forbidden permissions, verify it with `aapt2`, and then
replace only the imported APK:

```bash
KNOWN_GOOD=/absolute/path/to/verified/HyperNovaLauncher.apk
AOSP_APK=/mnt/wwn-0x5002538e7006e10b-part3/device/hypernova/cockpit/apps/HyperNovaLauncher/HyperNovaLauncher.apk

/home/ayman/Android/Sdk/build-tools/36.1.0/aapt2 dump badging "$KNOWN_GOOD"
sha256sum "$KNOWN_GOOD"
cp "$KNOWN_GOOD" "$AOSP_APK"
sha256sum "$AOSP_APK"
```

Rebuild `HyperNovaLauncher`, `systemextimage`, and `superimage` using the exact
commands above. Keep the corrected privapp XML unless the known-good artifact
has a separately reviewed legitimate permission set.
