# NOVA AI

## 1. Repository identity

```text
Repository: NOVA_AI
Package / applicationId: com.hypernova.ai
Namespace: com.hypernova.ai
Public open action: com.hypernova.ai.action.OPEN
Target UI: 1080 × 1920 portrait
Theme: Theme.HyperNova
```

The package and action are fixed contracts with HyperNova Launcher.

---

## 2. Application role

Own the automotive assistant UI and communication with the NOVA AI service running on the Raspberry Pi 5. The Android app is the cockpit client, not the main AI compute node.

The app opens as a full-screen independent Activity when the user presses its card or bottom-navigation destination in HyperNova Launcher.

```text
Launcher card
    -> com.hypernova.ai.action.OPEN
    -> com.hypernova.ai/.MainActivity
    -> Full-screen NOVA AI
```

This app must not declare itself as an Android HOME application.

---

## 3. Milestone 1 — App shell

The first pull request must contain:

1. Independent Android project.
2. Correct package and namespace.
3. `MainActivity`.
4. Exact public open action.
5. `Theme.HyperNova` Light/Dark resources.
6. One honest empty/unavailable screen.
7. Successful open from HyperNova Launcher.
8. Correct Back and Home behavior.
9. README with build and test commands.

### `app/build.gradle.kts` identity

```kotlin
android {
    namespace = "com.hypernova.ai"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.hypernova.ai"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
```

### `AndroidManifest.xml` Activity

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:launchMode="singleTask"
    android:screenOrientation="portrait">

    <intent-filter>
        <action android:name="com.hypernova.ai.action.OPEN" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>

    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>

</activity>
```

---

## 4. Required screens and states

- Disconnected.
- Connecting.
- Ready.
- Listening.
- Processing.
- Speaking.
- Text conversation.
- Microphone permission denied.
- Raspberry Pi unreachable.
- Request timeout.
- Service error.
- Offline capability unavailable.

Every state must be real. A placeholder may say that data is unavailable, but it must not present invented user, vehicle, route, call, media, or weather information.

---

## 5. Core functional requirements

- Keep network transport behind an interface.
- Use a clear assistant state machine.
- Connect to the Raspberry Pi service using the team-approved protocol.
- Implement request IDs, timeout, cancellation, and reconnect behavior.
- Do not block the main thread.
- Do not claim the assistant is ready before the Pi service handshake succeeds.
- Protect audio and conversation data.
- Do not send conversations to third-party cloud services unless the product requirement explicitly changes.
- Support typed input even when voice is unavailable.
- Integrate voice capture and playback with audio focus.

---

## 6. Permissions and platform access

- `android.permission.INTERNET`
- `android.permission.RECORD_AUDIO`
- `Network-security configuration appropriate for the selected local connection and production security model.`

Rules:

- Request runtime permissions only when the feature needs them.
- Show a useful denied state.
- Do not request broad permissions in the app-shell milestone.
- Any signature/privileged permission must be documented and integrated through AOSP, not bypassed in an Android Studio build.

---

## 7. Recommended source structure

```text
app/src/main/java/com/hypernova/ai/
├── MainActivity.kt
├── ui/
├── domain/
├── data/
├── service/
└── integration/
```

Responsibilities:

- `ui/ — assistant states and conversation screen.`
- `domain/ — requests, responses, assistant state machine, use cases.`
- `data/ — Raspberry Pi transport, reconnect, serialization.`
- `audio/ — microphone capture, audio focus, response playback.`
- `service/ — launcher-facing assistant status service.`
- `integration/ — public OPEN action and AIDL adapter.`

---

## 8. Launcher dashboard integration

Opening the application is Milestone 1. Dashboard data is a later milestone.

Reserved service:

```text
Service class: com.hypernova.ai.service.NovaStatusService
Bind action: com.hypernova.ai.action.BIND_STATUS
```

Expected Launcher snapshot fields:

- `connectionState`
- `assistantState`
- `headline`
- `subtitle`
- `isListening`
- `isProcessing`
- `lastError`

The snapshot must be exposed through the shared `hypernova-contracts` AIDL library. Do not import Launcher implementation classes.

The Launcher receives only assistant availability/state. Conversation content and audio remain inside NOVA AI.

---

## 9. Theme requirements

Copy and follow `00-Design-System/README.md`.

Mandatory:

- `Theme.Material3.DayNight.NoActionBar`.
- Same semantic color names as the Launcher.
- `values/colors.xml` for Light.
- `values-night/colors.xml` for Dark.
- No hardcoded colors.
- No separate Light/Dark layouts.
- Follow system `uiMode`.
- 48dp minimum touch target for full-app controls.

---

## 10. Build and test

```bash
./gradlew clean assembleDebug

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Confirm package:

```bash
adb shell pm list packages | grep com.hypernova.ai
```

Test the public action:

```bash
adb shell am start     -a com.hypernova.ai.action.OPEN     -p com.hypernova.ai
```



Then test from HyperNova Launcher:

1. Press the matching card.
2. Confirm this app opens full screen.
3. Press Back and review the expected stack behavior.
4. Press Home and confirm HyperNova Launcher appears.
5. Test Light and Dark.
6. Rotate attempts must not break portrait layout.

---

## 11. Definition of Done

### Milestone 1

- [ ] Correct package.
- [ ] Correct public action.
- [ ] Launcher opens the app.
- [ ] Full-screen 1080 × 1920 shell.
- [ ] Light/Dark design-system compliance.
- [ ] Honest empty state.
- [ ] Back and Home tested.
- [ ] No crash.
- [ ] README complete.

### Core feature milestone

- [ ] Required real functionality implemented.
- [ ] Permission-denied and disconnected states implemented.
- [ ] No fake data.
- [ ] Unit tests for domain logic.
- [ ] Device/Cuttlefish test evidence.

### Launcher integration milestone

- [ ] Status service or standard Android session implemented.
- [ ] Shared contract used.
- [ ] Launcher receives real snapshots.
- [ ] Disconnect/reconnect tested.
- [ ] Error state tested.
