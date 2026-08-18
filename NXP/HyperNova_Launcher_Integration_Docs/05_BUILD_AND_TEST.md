# Build and Test

## Commands executed

From `/home/ayman/ITI/Android-Apps/HyperNova_Launcher_Task_01`:

```bash
./gradlew clean assembleDebug
./gradlew testDebugUnitTest lintDebug assembleRelease
```

Results:

- Debug build: successful.
- Unit tests: successful.
- Debug lint: successful (no lint errors).
- Release build and release lint-vital: successful.

Artifacts:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

The release artifact is intentionally unsigned because the existing AOSP
`android_app_import` signs it using `certificate: "platform"`.

## Unit coverage added

- Every frozen Navigation contract state maps to the corresponding Launcher
  runtime state.
- Unknown Navigation states map to unavailable.
- Installed, available, connected, and active remain independent.
- An availability error does not claim the package is installed.

## APK validation

```bash
SDK=/home/ayman/Android/Sdk
$SDK/build-tools/36.1.0/aapt2 dump badging \
  app/build/outputs/apk/release/app-release-unsigned.apk
$SDK/build-tools/36.1.0/aapt2 dump xmltree \
  --file AndroidManifest.xml \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

Verified release values:

```text
package: com.hypernova.launcher
versionName: 1.0
application label: HyperNova Launcher
exported activity: com.hypernova.launcher.MainActivity
HOME + DEFAULT filter: present
LAUNCHER filter: present
```

## Connected AAOS runtime smoke test

The side-by-side debug APK (`com.hypernova.launcher.dev`) was installed and
launched without replacing the production HOME package. Observed results:

- Navigation package available, AIDL connected, state `IDLE`.
- Media package available, real MediaSession connected, no active item.
- Settings package available; Wi-Fi, Bluetooth, brightness, and media volume
  displayed from real framework values.
- Climate and Phone packages absent and displayed as not installed.
- No crash or fabricated value occurred.

Useful verification commands:

```bash
adb shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd package list packages | grep com.hypernova
adb shell dumpsys package com.hypernova.launcher
adb logcat -s HyperNovaLauncher NavigationStatusClient ClimateStatusClient
```

## Package-change test procedure

On a test target, install or remove only an authorized application APK while
HOME is visible, then observe its card and log:

```bash
adb install <authorized-app.apk>
adb logcat -s HyperNovaLauncher
```

Expected: the package broadcast causes immediate availability recomputation;
returning HOME also recomputes it in `onResume`. No Launcher restart is needed.
