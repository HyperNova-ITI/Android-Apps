# HyperNova Android 16 laptop environment

This is the local development baseline for NOVA and HyperNova Launcher before integration into the
NXP i.MX 8QM Android 16 guest.

## Installed baseline

- AVD: `HyperNova_API_36`
- Android: 16 / API 36
- Image: `system-images;android-36;default;x86_64` (plain AOSP)
- Display: 1080 × 1920 portrait
- Audio: emulator output enabled for NOVA PCM playback
- HOME app: `com.hypernova.launcher.dev/com.hypernova.launcher.MainActivity`
- NOVA endpoint default: `192.168.1.32:8765` control and `:8766` audio

The older `Medium_Phone` API 35 AVD remains installed as a fallback, but it is not the project target.

## Create the AVD on another laptop

Install the official Android SDK command-line tools, then run:

```bash
$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager \
  --install 'system-images;android-36;default;x86_64'

$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager create avd \
  --name HyperNova_API_36 \
  --package 'system-images;android-36;default;x86_64' \
  --device medium_phone
```

Android documents `sdkmanager` package installation in the
[official SDK manager guide](https://developer.android.com/tools/sdkmanager) and API 36 setup in the
[Android 16 SDK guide](https://developer.android.com/about/versions/16/setup-sdk).

## Boot at the cockpit target size

```bash
$ANDROID_SDK_ROOT/emulator/emulator \
  -avd HyperNova_API_36 \
  -skin 1080x1920 \
  -no-snapshot \
  -no-boot-anim \
  -gpu auto \
  -netdelay none \
  -netspeed full
```

Verify the runtime rather than trusting the AVD name:

```bash
adb shell getprop ro.build.version.release   # 16
adb shell getprop ro.build.version.sdk       # 36
adb shell wm size                            # 1080x1920
```

## Build and install NOVA

The project contains its own Gradle wrapper:

```bash
cd HyperNova_NOVA_AI_Task_02
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -a com.hypernova.ai.action.OPEN -p com.hypernova.ai
```

If the Pi receives a different IPv4 address, select it at build time:

```bash
./gradlew -PnovaHost=192.168.1.50 testDebugUnitTest assembleDebug
```

Clear existing app data before testing a changed default, because the runtime intentionally persists
the last configured endpoint:

```bash
adb shell pm clear com.hypernova.ai
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Install and select HyperNova Launcher

```bash
cd ../HyperNova_Launcher_Task_01
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

adb shell cmd package set-home-activity --user 0 \
  com.hypernova.launcher.dev/com.hypernova.launcher.MainActivity
adb shell input keyevent KEYCODE_HOME
```

The production AOSP image will use the production launcher package and platform signing instead of the
`.dev` application ID.

## Launcher live NOVA state

The launcher now binds to NOVA's read-only status service and receives all eight visible states in
real time. The shared contract is version 1 and uses:

- action: `com.hypernova.ai.action.BIND_STATUS`;
- permission: `com.hypernova.ai.permission.READ_STATUS`;
- service: `com.hypernova.ai.status.NovaStatusService`;
- states: `IDLE`, `LISTENING`, `PROCESSING`, `EXECUTING`, `SUCCESS`, `ERROR`, `SPEAKING`, and
  `UNAVAILABLE`.

The permission is signature-protected. Debug APKs work together because they use the same debug key;
the final launcher and NOVA APK must be signed with the same approved platform/product key. The
contract exposes only customer-visible assistant state—never microphone, speaker, network-host, or
device-topology details.

## Connect to the Raspberry Pi later

The Pi is intentionally powered off at the end of the July 22 development session. When it is needed:

1. Power on `hnc-ai30` and confirm its IPv4 address from the laptop.
2. Confirm `nova-llama.service` and `nova-agent.service` are active.
3. Confirm the Pi listens on TCP 8765 and 8766.
4. Confirm the emulator can reach the Pi's LAN IPv4 address.
5. Launch NOVA; it should move from `UNAVAILABLE` to `READY` only after both sockets connect.
6. Say “Hey NOVA” into the Pi microphone and verify Android owns all response playback.

The emulator uses NAT but can normally reach devices on the laptop's LAN directly. Do not depend on
`.local` discovery inside the Android guest; use the Pi's IPv4 address or the final guest network/DNS
configuration.

## Final NXP migration boundary

The app and protocol contain no Trout-specific APIs. Migration to the Android 16 ARM64 guest requires:

- preinstalling/platform-signing the APK using the image team's procedure;
- allowing guest networking to the Pi on TCP 8765/8766;
- verifying Android audio output routing and focus on the passed-through NXP speaker device;
- replacing the debug launcher package with the production privileged HOME package;
- signing Launcher and NOVA with the same key so the status permission is granted;
- rerunning Launcher → NOVA, Back/Home, reconnect, and PCM playback tests.

No Android microphone pass-through is required: the CM108 microphone remains owned by the Pi.
