# HyperNova Settings

## 1. Repository identity

```text
Repository: HyperNova_Settings
Package / applicationId: com.hypernova.settings
Namespace: com.hypernova.settings
Public open action: com.hypernova.settings.action.OPEN
Target UI: 1080 × 1920 portrait
Theme: Theme.HyperNova
```

The package and action are fixed contracts with HyperNova Launcher.

---

## 2. Application role

Own IVI appearance, connectivity entry points, system information, and HyperNova application settings. It becomes the final user-facing owner of Light/Dark mode.

The app opens as a full-screen independent Activity when the user presses its card or bottom-navigation destination in HyperNova Launcher.

```text
Launcher card
    -> com.hypernova.settings.action.OPEN
    -> com.hypernova.settings/.MainActivity
    -> Full-screen HyperNova Settings
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
    namespace = "com.hypernova.settings"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.hypernova.settings"
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
        <action android:name="com.hypernova.settings.action.OPEN" />
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

- Settings home.
- Appearance.
- Light mode.
- Dark mode.
- Follow system/automatic mode when supported.
- Connectivity entry points.
- System information.
- Application information.
- Permission/privilege unavailable.
- Setting apply error.

Every state must be real. A placeholder may say that data is unavailable, but it must not present invented user, vehicle, route, call, media, or weather information.

---

## 5. Core functional requirements

- Use Android system APIs; do not duplicate system state in local preferences.
- Appearance changes must update Android uiMode so all DayNight HyperNova apps stay synchronized.
- For a normal development APK, show a clear app-only fallback or privilege-unavailable state.
- For the AOSP production build, integrate the required privileged permission and allowlist.
- Expose system/app version details from real package/build properties.
- Do not provide fake connectivity switches.
- Deep-link to the appropriate platform settings screen when direct control is not allowed.
- Make dangerous/reset operations explicit and confirmed.

---

## 6. Permissions and platform access

- `android.permission.MODIFY_DAY_NIGHT_MODE for production system-wide theme control; signature/privileged and must be allowlisted in AOSP.`
- `Additional platform settings permissions only when a concrete approved requirement exists.`

Rules:

- Request runtime permissions only when the feature needs them.
- Show a useful denied state.
- Do not request broad permissions in the app-shell milestone.
- Any signature/privileged permission must be documented and integrated through AOSP, not bypassed in an Android Studio build.

---

## 7. Recommended source structure

```text
app/src/main/java/com/hypernova/settings/
├── MainActivity.kt
├── ui/
├── domain/
├── data/
├── service/
└── integration/
```

Responsibilities:

- `ui/ — settings home and category screens.`
- `domain/ — appearance and system-setting use cases.`
- `data/ — UiModeManager/system API adapters.`
- `service/ — launcher-facing settings summary service.`
- `integration/ — public OPEN action and AIDL adapter.`

---

## 8. Launcher dashboard integration

Opening the application is Milestone 1. Dashboard data is a later milestone.

Reserved service:

```text
Service class: com.hypernova.settings.service.SettingsStatusService
Bind action: com.hypernova.settings.action.BIND_STATUS
```

Expected Launcher snapshot fields:

- `connectionState`
- `appearanceMode`
- `systemSummary`
- `hasWarnings`
- `warningCount`

The snapshot must be exposed through the shared `hypernova-contracts` AIDL library. Do not import Launcher implementation classes.

Production system-wide theme flow:

```text
HyperNova Settings
    -> UiModeManager.setNightMode(...)
    -> Android updates uiMode
    -> Every HyperNova DayNight app reloads matching resources
```

Only HyperNova Settings should be the final user-facing theme owner. The Launcher toggle can remain a development/fallback convenience until Settings is integrated.

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
adb shell pm list packages | grep com.hypernova.settings
```

Test the public action:

```bash
adb shell am start     -a com.hypernova.settings.action.OPEN     -p com.hypernova.settings
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
