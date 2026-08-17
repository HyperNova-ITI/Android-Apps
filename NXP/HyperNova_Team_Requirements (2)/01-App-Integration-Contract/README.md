# HyperNova Application Integration Contract

This document defines how independent HyperNova APKs are opened from HyperNova Launcher.

## 1. Important separation

Every feature is an independent Android application.

```text
HyperNova Launcher APK
HyperNova Navigation APK
HyperNova Media APK
HyperNova Phone APK
...
```

Do not add feature screens as Activities inside the Launcher project.

Only HyperNova Launcher declares the Android `HOME` category.

Feature applications must not declare `HOME`.

---

## 2. Fixed identities

| App | Package | Open action |
|---|---|---|
| Navigation | `com.hypernova.navigation` | `com.hypernova.navigation.action.OPEN` |
| Media | `com.hypernova.media` | `com.hypernova.media.action.OPEN` |
| Phone | `com.hypernova.phone` | `com.hypernova.phone.action.OPEN` |
| Climate | `com.hypernova.climate` | `com.hypernova.climate.action.OPEN` |
| Weather | `com.hypernova.weather` | `com.hypernova.weather.action.OPEN` |
| Driver Profile | `com.hypernova.driverprofile` | `com.hypernova.driverprofile.action.OPEN` |
| Settings | `com.hypernova.settings` | `com.hypernova.settings.action.OPEN` |
| NOVA AI | `com.hypernova.ai` | `com.hypernova.ai.action.OPEN` |

These strings must match the Launcher `AppRegistry`.

---

## 3. Required Gradle identity

Example for Navigation:

```kotlin
android {
    namespace = "com.hypernova.navigation"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.hypernova.navigation"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }
}
```

Do not add a debug `applicationIdSuffix` to feature apps unless the Launcher also has a matching debug registry. The simplest team workflow is to keep the final package name in debug builds.

---

## 4. Required Activity manifest contract

Replace the package-specific action for each app:

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:launchMode="singleTask"
    android:screenOrientation="portrait">

    <!-- Stable action used by HyperNova Launcher -->
    <intent-filter>
        <action android:name="com.hypernova.navigation.action.OPEN" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>

    <!-- Development fallback and direct Android Studio launch -->
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>

</activity>
```

Requirements:

- `android:exported="true"` so another APK can open the Activity.
- The public action must include `CATEGORY_DEFAULT`.
- The feature app must not include `CATEGORY_HOME`.
- `singleTask` prevents duplicate root screens when the Launcher opens the app repeatedly.

---

## 5. Runtime flow

```text
User presses Launcher card
        |
        v
Launcher selects AppDestination
        |
        v
AppRegistry returns package + open action
        |
        v
AppLauncher resolves the public action
        |
        v
Android starts the target MainActivity
        |
        v
Target app appears full screen
```

The Launcher remains behind the app.

- Back normally finishes the feature Activity and returns to the previous screen.
- Home always returns to HyperNova Launcher because it is the system HOME app.
- Feature apps must not create an Intent that starts another launcher instance.

---

## 6. Opening is not dashboard-data integration

After the APK is installed, the Launcher can open it. This does not automatically give the Launcher route, climate, call, or weather data.

```text
Application opening:
Launcher -> Intent -> MainActivity

Status sharing:
Feature service -> Launcher client -> LauncherUiState -> Card
```

Milestone 1 is opening.

Milestone 3 is data sharing.

---

## 7. Data contract direction

A future shared Android library should be created:

```text
hypernova-contracts
```

It should contain:

- Package constants.
- Intent action constants.
- Bound-service action constants.
- AIDL interfaces.
- Parcelable snapshot definitions.
- Shared signature-permission names.

Feature apps must not import classes from the Launcher application module.

### Recommended integration type

| Application | Launcher data channel |
|---|---|
| Media | Android Media3 `MediaSession` / `MediaController` |
| Navigation | Bound Service + AIDL route snapshot |
| Phone | Bound Service + AIDL call/connection snapshot |
| Climate | Bound Service + AIDL HVAC snapshot |
| Weather | Bound Service + AIDL weather snapshot |
| Driver Profile | Bound Service + AIDL active-profile snapshot |
| Settings | Bound Service + AIDL system-summary snapshot |
| NOVA AI | Bound Service + AIDL assistant-state snapshot |

The AIDL work begins only after the app shell and core functionality are stable.

---

## 8. Honest states

An installed application without a connected status service is not `READY`.

The Launcher may show:

```text
NOT_INSTALLED
NO_LAUNCHABLE_ACTIVITY
DISCONNECTED
CONNECTING
READY
ERROR
```

Feature apps must provide real state. Do not send fake values to make the dashboard look populated.

---

## 9. Build and Launcher test

From the feature project:

```bash
./gradlew clean assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Confirm package:

```bash
adb shell pm list packages | grep <PACKAGE_NAME>
```

Test the public action directly:

```bash
adb shell am start     -a <PUBLIC_OPEN_ACTION>     -p <PACKAGE_NAME>
```

Then press the matching Launcher card.

Test Home:

```bash
adb shell input keyevent KEYCODE_HOME
```

---

## 10. Milestone 1 Definition of Done

- [ ] Independent repository/project.
- [ ] Correct final package.
- [ ] Correct public open action.
- [ ] Exported MainActivity.
- [ ] No `HOME` category.
- [ ] HyperNova Launcher detects the package.
- [ ] Launcher card opens the correct screen.
- [ ] Repeated presses do not create many root Activities.
- [ ] Back behavior is correct.
- [ ] Home returns to HyperNova Launcher.
- [ ] Light and Dark themes work.
- [ ] No crash.
- [ ] No fake data.
