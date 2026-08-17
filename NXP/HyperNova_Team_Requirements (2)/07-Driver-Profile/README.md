# HyperNova Driver Profile

## 1. Repository identity

```text
Repository: HyperNova_Driver_Profile
Package / applicationId: com.hypernova.driverprofile
Namespace: com.hypernova.driverprofile
Public open action: com.hypernova.driverprofile.action.OPEN
Target UI: 1080 × 1920 portrait
Theme: Theme.HyperNova
```

The package and action are fixed contracts with HyperNova Launcher.

---

## 2. Application role

Own driver profiles, active-driver selection, avatar, and driver-specific IVI preferences.

The app opens as a full-screen independent Activity when the user presses its card or bottom-navigation destination in HyperNova Launcher.

```text
Launcher card
    -> com.hypernova.driverprofile.action.OPEN
    -> com.hypernova.driverprofile/.MainActivity
    -> Full-screen HyperNova Driver Profile
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
    namespace = "com.hypernova.driverprofile"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.hypernova.driverprofile"
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
        <action android:name="com.hypernova.driverprofile.action.OPEN" />
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

- No profiles.
- Profile list.
- Create profile.
- Edit profile.
- Profile details.
- Select active profile.
- Delete confirmation.
- Avatar unavailable.
- Storage error.

Every state must be real. A placeholder may say that data is unavailable, but it must not present invented user, vehicle, route, call, media, or weather information.

---

## 5. Core functional requirements

- Store profiles locally using a repository and DataStore/database.
- Use stable profile IDs.
- Support one active profile.
- Keep avatar as an app-owned URI or file reference.
- Validate display names.
- Do not silently delete a profile.
- Do not store sensitive personal information that is not needed by the IVI.
- Prepare preference fields for theme-independent settings such as favorites and app behavior.

---

## 6. Permissions and platform access

- `No broad storage permission. Use app-private storage or the system picker for user-selected images.`

Rules:

- Request runtime permissions only when the feature needs them.
- Show a useful denied state.
- Do not request broad permissions in the app-shell milestone.
- Any signature/privileged permission must be documented and integrated through AOSP, not bypassed in an Android Studio build.

---

## 7. Recommended source structure

```text
app/src/main/java/com/hypernova/driverprofile/
├── MainActivity.kt
├── ui/
├── domain/
├── data/
├── service/
└── integration/
```

Responsibilities:

- `ui/ — list, create, edit, details, and selection.`
- `domain/ — profile and active-profile use cases.`
- `data/ — DataStore/database and avatar storage.`
- `service/ — launcher-facing active-profile service.`
- `integration/ — public OPEN action and AIDL adapter.`

---

## 8. Launcher dashboard integration

Opening the application is Milestone 1. Dashboard data is a later milestone.

Reserved service:

```text
Service class: com.hypernova.driverprofile.service.DriverProfileStatusService
Bind action: com.hypernova.driverprofile.action.BIND_STATUS
```

Expected Launcher snapshot fields:

- `connectionState`
- `activeProfileId`
- `displayName`
- `avatarUri`
- `hasActiveProfile`

The snapshot must be exposed through the shared `hypernova-contracts` AIDL library. Do not import Launcher implementation classes.

The Launcher receives only the active display name and avatar URI. Profile editing and all personal details stay inside this app.

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
adb shell pm list packages | grep com.hypernova.driverprofile
```

Test the public action:

```bash
adb shell am start     -a com.hypernova.driverprofile.action.OPEN     -p com.hypernova.driverprofile
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
