# AOSP APK Deployment

## Source and destination

```text
Launcher project:
/home/ayman/ITI/Android-Apps/HyperNova_Launcher_Task_01

Generated APK:
/home/ayman/ITI/Android-Apps/HyperNova_Launcher_Task_01/app/build/outputs/apk/release/app-release-unsigned.apk

AOSP module directory:
/mnt/wwn-0x5002538e7006e10b-part3/device/hypernova/cockpit/apps/HyperNovaLauncher

Destination APK:
/mnt/wwn-0x5002538e7006e10b-part3/device/hypernova/cockpit/apps/HyperNovaLauncher/HyperNovaLauncher.apk
```

Discovered `Android.bp` module:

```bp
android_app_import {
    name: "HyperNovaLauncher",
    apk: "HyperNovaLauncher.apk",
    certificate: "platform",
    privileged: true,
    system_ext_specific: true,
}
```

No AOSP build configuration was changed.

## Package checks

| Check | Value |
|---|---|
| New release APK package | `com.hypernova.launcher` |
| Previous imported APK package | `com.hypernova.launcher` |
| Installed default HOME | `com.hypernova.launcher/.MainActivity` |
| New release HOME candidate | `com.hypernova.launcher.MainActivity` with HOME/DEFAULT |

The debug-only package remains `com.hypernova.launcher.dev`; it was not copied
to AOSP.

## Hashes and metadata

Before replacement:

```text
SHA256: 33540d800c66a221db3ef4a953c5f5c24904b418e30b158472d20c05f0424c17
Size: 23606456 bytes
Mode: 0664
UID:GID: 1000:1000
mtime: 2026-07-18 13:12:43.039408481 +0300
```

After replacement:

```text
SHA256: f022976cd4aeb8a4e6fa38e6f36cbb5c520fd35f3de7e039f41372a945567764
Size: 23749076 bytes
Mode: 0664
UID:GID: 1000:1000
mtime: 2026-07-31 23:08:11.412550917 +0300
```

The destination passed a byte-for-byte comparison with the generated release
artifact.

## Module build

The current shell was not AOSP-configured: `ANDROID_BUILD_TOP` was unset and
the `m` shell function was unavailable. No product/lunch target was guessed.

After entering the correct already-established product environment, validate
only the discovered module:

```bash
cd /mnt/wwn-0x5002538e7006e10b-part3
source build/envsetup.sh
lunch <the-existing-hypernova-product-target>
m HyperNovaLauncher -j10
```

The lunch value must come from the product's established build instructions.

## Post-build/device verification

```bash
adb shell pm path com.hypernova.launcher
adb shell dumpsys package com.hypernova.launcher | grep -A8 "requested permissions"
adb shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell am force-stop com.hypernova.launcher
adb shell input keyevent KEYCODE_HOME
adb logcat -s HyperNovaLauncher NavigationStatusClient ClimateStatusClient
```

Verify the platform-signed installed APK receives the required signature
permissions on the final image.
