# HyperNova Climate

## 1. Repository identity

```text
Repository: HyperNova_Climate
Package / applicationId: com.hypernova.climate
Namespace: com.hypernova.climate
Public open action: com.hypernova.climate.action.OPEN
Target UI: 1080 × 1920 portrait
Theme: Theme.HyperNova
```

The package and action are fixed contracts with HyperNova Launcher.

---

## 2. Application role

Own vehicle HVAC presentation and commands: temperature, fan, A/C, AUTO, and air recirculation. Seat heating is not part of the current product scope.

The app opens as a full-screen independent Activity when the user presses its card or bottom-navigation destination in HyperNova Launcher.

```text
Launcher card
    -> com.hypernova.climate.action.OPEN
    -> com.hypernova.climate/.MainActivity
    -> Full-screen HyperNova Climate
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
    namespace = "com.hypernova.climate"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.hypernova.climate"
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
        <action android:name="com.hypernova.climate.action.OPEN" />
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

- Climate unavailable.
- Vehicle service disconnected.
- Climate off.
- Manual climate control.
- AUTO mode.
- A/C on/off.
- Fan-level adjustment.
- Air-recirculation on/off.
- Property read error.
- Property write error.

Every state must be real. A placeholder may say that data is unavailable, but it must not present invented user, vehicle, route, call, media, or weather information.

---

## 5. Core functional requirements

- Use Android Automotive car APIs through a dedicated vehicle repository.
- Read real HVAC properties from CarPropertyManager when available.
- Write only supported properties.
- Detect property availability before enabling a control.
- Expose temperature, fan level, A/C, AUTO, recirculation, and power state.
- Disable controls while disconnected.
- Do not simulate successful vehicle writes.
- Keep the UI usable when some HVAC properties are not implemented by the vehicle.

---

## 6. Permissions and platform access

- `Android Automotive car permissions required by the selected HVAC properties.`
- `Most real HVAC read/write access requires a platform or privileged application and AOSP permission allowlisting.`

Rules:

- Request runtime permissions only when the feature needs them.
- Show a useful denied state.
- Do not request broad permissions in the app-shell milestone.
- Any signature/privileged permission must be documented and integrated through AOSP, not bypassed in an Android Studio build.

---

## 7. Recommended source structure

```text
app/src/main/java/com/hypernova/climate/
├── MainActivity.kt
├── ui/
├── domain/
├── data/
├── service/
└── integration/
```

Responsibilities:

- `ui/ — HVAC screen and unavailable/error states.`
- `domain/ — climate state and command use cases.`
- `data/ — CarPropertyManager adapter and property mapping.`
- `service/ — launcher-facing climate status service.`
- `integration/ — public OPEN action and AIDL adapter.`

---

## 8. Launcher dashboard integration

Opening the application is Milestone 1. Dashboard data is a later milestone.

Reserved service:

```text
Service class: com.hypernova.climate.service.ClimateStatusService
Bind action: com.hypernova.climate.action.BIND_STATUS
```

Expected Launcher snapshot fields:

- `connectionState`
- `isClimateOn`
- `temperatureCelsius`
- `fanLevel`
- `isAcOn`
- `isAutoOn`
- `isRecirculationOn`
- `lastError`

The snapshot must be exposed through the shared `hypernova-contracts` AIDL library. Do not import Launcher implementation classes.

The current agreed controls are FAN, A/C, and RECIRC. Do not add seat-heating UI without a new vehicle requirement.

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
adb shell pm list packages | grep com.hypernova.climate
```

Test the public action:

```bash
adb shell am start     -a com.hypernova.climate.action.OPEN     -p com.hypernova.climate
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
