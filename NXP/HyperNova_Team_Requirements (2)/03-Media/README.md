# HyperNova Media

## 1. Repository identity

```text
Repository: HyperNova_Media
Package / applicationId: com.hypernova.media
Namespace: com.hypernova.media
Public open action: com.hypernova.media.action.OPEN
Target UI: 1080 × 1920 portrait
Theme: Theme.HyperNova
```

The package and action are fixed contracts with HyperNova Launcher.

---

## 2. Application role

Own audio and video browsing/playback for Local Storage, USB Storage, Bluetooth Phone, and Radio. It is the only owner of playback state and metadata.

The app opens as a full-screen independent Activity when the user presses its card or bottom-navigation destination in HyperNova Launcher.

```text
Launcher card
    -> com.hypernova.media.action.OPEN
    -> com.hypernova.media/.MainActivity
    -> Full-screen HyperNova Media
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
    namespace = "com.hypernova.media"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.hypernova.media"
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
        <action android:name="com.hypernova.media.action.OPEN" />
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

- Media Home with source selection.
- Music Library.
- Album details.
- Playlist details.
- Now Playing — playing.
- Now Playing — paused.
- Playback Queue.
- Scanning media.
- No media.
- Buffering.
- Seeking.
- Audio focus interrupted.
- Source disconnected.
- Playback error.

Every state must be real. A placeholder may say that data is unavailable, but it must not present invented user, vehicle, route, call, media, or weather information.

---

## 5. Core functional requirements

- Use AndroidX Media3.
- Publish a real MediaSession.
- Use a MediaLibraryService or MediaSessionService suitable for browsing and playback.
- Support Local Storage.
- Support USB Storage and source-disconnect handling.
- Plan Bluetooth A2DP Sink audio reception and AVRCP Controller metadata/commands; these may require AAOS system integration.
- Support Radio through a source abstraction; hardware/backend details stay outside the UI.
- Handle audio focus correctly.
- Keep playback alive independently of the Activity.
- Publish title, artist, album, artwork URI, duration, position, playback state, and available commands.
- No cloud dependency in the core product.

---

## 6. Permissions and platform access

- `android.permission.READ_MEDIA_AUDIO`
- `android.permission.READ_MEDIA_VIDEO only for supported video browsing.`
- `Storage/USB access through supported Android media and document APIs.`
- `Bluetooth and radio privileged access only through the AAOS platform integration approved by the team.`

Rules:

- Request runtime permissions only when the feature needs them.
- Show a useful denied state.
- Do not request broad permissions in the app-shell milestone.
- Any signature/privileged permission must be documented and integrated through AOSP, not bypassed in an Android Studio build.

---

## 7. Recommended source structure

```text
app/src/main/java/com/hypernova/media/
├── MainActivity.kt
├── ui/
├── domain/
├── data/
├── service/
└── integration/
```

Responsibilities:

- `ui/ — home, library, details, queue, now playing, source states.`
- `domain/ — media source, media item, queue, playback use cases.`
- `data/ — local scanner, USB scanner, Bluetooth metadata adapter, radio adapter.`
- `playback/ — Media3 service, player, MediaSession, audio-focus handling.`
- `integration/ — public OPEN action and Launcher compatibility.`

---

## 8. Launcher dashboard integration

Opening the application is Milestone 1. Dashboard data is a later milestone.

Reserved service:

```text
Service class: com.hypernova.media.playback.HyperNovaMediaSessionService
Bind action: Android MediaSession discovery
```

Expected Launcher snapshot fields:

- `Android MediaSession is the contract; do not create a duplicate custom playback AIDL.`
- `Media metadata`
- `Playback state`
- `Current position and duration`
- `Available play/pause/previous/next commands`
- `Artwork URI`
- `Active source`

The snapshot must be exposed through the shared `hypernova-contracts` AIDL library. Do not import Launcher implementation classes.

The service class name is already reserved by the Launcher AppRegistry and must remain exactly `com.hypernova.media.playback.HyperNovaMediaSessionService`.

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
adb shell pm list packages | grep com.hypernova.media
```

Test the public action:

```bash
adb shell am start     -a com.hypernova.media.action.OPEN     -p com.hypernova.media
```

Inspect active media sessions after playback starts:

```bash
adb shell dumpsys media_session
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
