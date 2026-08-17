# HyperNova Media — Phone Edition

This Android Studio module is the standalone public-API phone edition of HyperNova Media. It is intentionally separate from the privileged AAOS implementation retained under `aosp_source_snapshot/` and from the immutable APK handoff under `HyperNovaMedia_Handoff/`.

## Phone build

- Namespace: `com.hypernova.media`
- Release application ID: `com.hypernova.media.phone`
- Debug application ID: `com.hypernova.media.phone.debug`
- Minimum SDK: 26
- Target SDK: 36
- UI: Java + XML Views, portrait-first at a 540 × 960 dp design canvas (1080 × 1920 at 320 dpi)
- Playback: AndroidX Media3 1.10.1

```bash
./gradlew --no-configuration-cache :app:assembleDebug
./gradlew --no-configuration-cache :app:installDebug
adb shell am start -n com.hypernova.media.phone.debug/com.hypernova.media.MainActivity
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Architecture

`HyperNovaPlaybackService` is the sole owner of ExoPlayer and MediaSession. `PlaybackController` connects with a Media3 `MediaController`, so Activity recreation cannot create a competing player. The service supplies background playback, media-button handling, audio focus, becoming-noisy behavior, system media controls, lock-screen controls, and the Media3 playback notification.

Platform data flows through phone-safe contracts and immutable snapshots:

```text
Radio Browser mirrors → RadioApiClient → RadioRepository → RadioUiState
StorageManager / MediaStore / SAF → UsbVolumeRepository → LibraryUiState
AudioManager / BluetoothAdapter → PhoneBluetoothAudioBackend → BluetoothUiState
                                    ↓
                      MainUiRenderer + PlaybackController
                                    ↓
              HyperNovaPlaybackService / MediaSession / ExoPlayer
```

`MainActivity` owns lifecycle, permission launchers, and navigation rather than querying platform services. `MainUiRenderer` renders state, `LibraryBrowserController` owns USB media filters/search/history, and `RadioBrowserController` owns catalog discovery, filters, and custom-station UX. Network requests and storage metadata scans use cancellable background executors.

Important production classes:

- `radio/RadioApiClient`: Radio Browser mirror discovery, bounded HTTP, JSON validation, and click notification.
- `radio/RadioRepository`: lifecycle-safe catalog state, offline fallback, favorites, recents, hidden/broken records, user stations, and saved filters.
- `radio/RadioDatabase`: bounded structured SQLite cache with a 600-row catalog ceiling.
- `usb/UsbStorageMonitor`: process-lifetime mounted/eject/removed/scanner-finished observer using public Android APIs.
- `usb/UsbVolumeRepository`: removable-volume selection and state machine.
- `usb/UsbMediaScanner`: bounded MediaStore/SAF audio-video metadata scanner.
- `usb/UsbPermissionRepository`: persisted SAF URI ownership and removable-versus-primary classification.

## Phone versus AAOS

The phone build never imports `android.car`, uses hidden APIs, or attempts A2DP sink/AAOS AVRCP browsing. On a phone:

- **Radio** means the public Radio Browser Internet Radio catalog plus optional user stations, played by Media3. No FM tuner is claimed.
- **Bluetooth** reports paired/connected public A2DP, headset, BLE, and active Android audio-output state. Playback remains this app's MediaSession and Android routes it normally.
- **USB** means non-emulated removable storage mounted and exposed by Android. MediaStore is automatic where possible and the Storage Access Framework is the public fallback. Internal primary storage is never presented as USB.

The AAOS snapshot remains read-only reference code and retains its privileged car-specific backend contracts separately.

## Internet Radio and Radio Browser

The catalog uses the official [Radio Browser API](https://api.radio-browser.info/) and its documented [station/search endpoints](https://docs.radio-browser.info/). It does not pin one fragile mirror: `RadioApiClient` resolves `all.api.radio-browser.info`, shuffles discovered official mirrors, retries another mirror on transport failure, and sends a descriptive `User-Agent`.

Default discovery combines four bounded official feeds concurrently: Egypt by popularity, Arabic by popularity, English by popularity, and international top-clicked stations. The UI also exposes Popular, Top voted, Trending, Egypt, Arabic, English, genre/tag, Favorites, Recent, station-name search, explicit refresh, and a 48-record result ceiling. Requests use `hidebroken=true`; records additionally require `lastcheckok=1`, a UUID, name, and a valid resolved HTTP(S) stream URL.

Used contracts:

- `/json/stations/search` with name, `countrycode`, language, tag, codec, ordering, offset, limit, and `hidebroken`.
- `/json/stations/topclick/{count}` for popularity.
- `/json/stations/topvote/{count}` for top-voted mode.
- `clicktrend` ordering for trending mode.
- `/json/url/{stationuuid}` after a playback choice, without parsing/retrying a successful click response.

`RadioDatabase` caches the last successful station metadata and preserves favorites, recents, hidden broken stations, custom stations, verification state, and last filters. A cold offline launch shows cached results with an explicit Offline label. An API failure with cache shows Cached rather than blanking the screen.

Playback always uses `url_resolved` when supplied. Media3 handles redirects, MP3/AAC/OGG/progressive streams and HLS records; its HTTP source has 8-second connect and 12-second read timeouts. A separate 18-second startup watchdog converts a catalog-health false positive into an honest error with Retry, Next, and local Hide-broken options. No stream is downloaded as a file.

Add Station remains secondary. It validates a non-empty name and HTTP(S) URL and stores artwork, country, language, and tags. A saved custom stream is visibly unverified until Media3 actually reaches playback; users can edit, delete, favorite, retry, or hide it.

## Bluetooth behavior

Android 12+ requests `BLUETOOTH_CONNECT` only when the Bluetooth surface needs device details. The app distinguishes unsupported, permission-required, off, no-paired-audio, paired-but-inactive, connecting, and connected-output states. Device names are displayed only when Android reports them. Active routing comes from `AudioManager` media attributes; paired capability comes from public `BluetoothAdapter` state. Battery is deliberately omitted when no public broadcast/API provides it. Settings actions open Android's Bluetooth page. The phone remains an output/controller for this app's MediaSession and never acts as an A2DP sink.

## USB/OTG, MediaStore, and SAF behavior

“Any USB” means any removable USB/OTG mass-storage volume that Android mounts and exposes through public storage contracts. It does not mean raw `/dev` access, a private vendor mount path, root, `MANAGE_EXTERNAL_STORAGE`, a custom SCSI/filesystem driver, or a promise that unsupported phone hardware will mount a drive.

At process start and Activity resume, `UsbStorageMonitor` inspects `StorageManager.getStorageVolumes()`, rejects emulated/internal volumes, and retains mounted removable entries. On Android 11+, the volume's `getMediaStoreVolumeName()` is cross-checked against `MediaStore.getExternalVolumeNames()`. Exactly one removable volume is auto-selected; multiple volumes produce a picker. Broadcasts for mounted, unmounted, eject, removed, bad removal, and media-scanner completion refresh the immutable state.

When Android exposes an indexed removable volume and the appropriate permission exists, `UsbMediaScanner` queries that exact MediaStore volume—never every external volume—and reads real audio/video metadata. Scanning is single-worker, cancellable, iterative for SAF, capped at 4,000 playable files and depth 16, and closes cursors and `MediaMetadataRetriever` instances. MIME type is primary; supported extensions only recover ambiguous `application/octet-stream` documents.

**Select USB folder** launches `ACTION_OPEN_DOCUMENT_TREE`, persists read permission with `takePersistableUriPermission()`, and reconnects after relaunch. External-storage tree UUIDs are compared with real removable volumes. A primary/internal tree is explicitly labeled `Local Folder`, not USB. Revocation becomes Permission required with Reconnect; access is never silently replaced by broad storage permission. Long-pressing Select USB folder releases/forgets the retained grant.

Removal cancels an active scan. If the active Media3 item ID belongs to USB, the app stops and clears the queue, detaches the video surface through normal Activity/player lifecycle, clears stale metadata, and renders Removed before returning to No USB. SAF read failures are also treated as unavailable USB for playback cleanup.

USB browsing retains Tracks, Artists, Albums, Genres, Videos, Folders, Recently played, Favorites, Queue, search, and metadata-aware sorting. Local-folder playback is a fallback for phones with no inserted OTG media and remains visibly distinct.

## Permissions

- `POST_NOTIFICATIONS`: requested on the first playback action on Android 13+; denial does not crash or block foreground UI playback.
- `INTERNET` and `ACCESS_NETWORK_STATE`: catalog/stream connectivity only; TLS validation is never disabled.
- `READ_MEDIA_AUDIO`, `READ_MEDIA_VIDEO`, and Android 14 selected visual access: requested only for indexed MediaStore USB browsing.
- `READ_EXTERNAL_STORAGE`: legacy only, capped at API 32.
- `BLUETOOTH_CONNECT`: requested only for real paired/connected device state on Android 12+.
- SAF folder access needs no broad storage permission and remains the reduced-functionality path after denial or absent MediaStore exposure.
- Foreground-service media playback permissions are declared for the MediaSession service.

## Visual system and Demo Mode

Dark mode is the primary deep-navy/graphite cockpit presentation; light mode has a deliberate pale-graphite palette. The custom Canvas visualizer draws the cabin silhouette, horizon, parallax grid, ambient cyan strips, restrained violet glow, particles, procedural bars, radio rings, and Bluetooth nodes. It changes modes from real player/source state, pauses in `onStop`, respects the system animation scale, and never labels its procedural motion as a measured audio spectrum.

Debug builds expose a hidden state preview by long-pressing the header. Demo Mode is off by default, never persisted, always displays a `DEMO` badge, and previews the target compositions plus permission/no-device/detected/scanning/removed layouts. It does not replace or mutate real backend data. The USB inserted/scanning/removed reference screenshots are Demo previews when no removable drive is physically connected.

## Physical-phone validation

Validated on an OPPO CPH2641 (`OP5B16L1`), Android 15 / API 35, serial `b129ebcb`. The display reports 720 × 1604 pixels, physical 320 dpi, and a current 272 dpi override. Android exposes USB host/accessory features. During validation it exposed only `private` and `emulated;0` mounted volumes—no removable USB—while a previously persisted primary SAF tree remained readable and was correctly labeled Local Folder.

Passed on the physical phone:

- Cold/warm launch, Activity recreation, fast Radio/Bluetooth/USB switching, dark mode, and light mode.
- Radio Browser DNS mirror discovery, 48-record healthy catalog, Egypt filter, cached offline launch, station metadata/favicons, recents, a real 102.7 KIIS FM stream, pause/play, notification, lock-screen/system media session, and 18-second broken-stream error/retry.
- Existing persisted SAF grant survived install/relaunch; 2 real local audio files and 1 real local video were scanned. Audio play/pause/seek/next, 8-second portrait video, aspect-fit surface, fullscreen, and Back-to-exit-fullscreen passed.
- Background audio, screen-off continuation, media-key pause/play, public MediaSession state, and a three-action Media3 foreground notification passed.
- Bluetooth permission/public state refresh and paired-but-no-active-output rendering passed. A profile-proxy reconnect loop found during validation was removed; the clean regression run produced no repeated profile churn.
- Scoped logcat contained no app `FATAL EXCEPTION`, ANR, `SecurityException`, receiver leak, player leak, or `NetworkOnMainThreadException` after the final fixes.

The test Wi-Fi's opportunistic private DNS could resolve the Radio Browser host but temporarily timed out on unrelated stream hostnames. The positive stream test was performed after temporarily disabling that system DNS mode and immediately restoring its original unset/default value. This is an environmental resolver limitation; the app's retry/error/cache behavior remained correct.

Screenshots are under `artifacts/phone-radio-usb/`:

```text
01_radio_discovery.png       real Radio Browser discovery
02_radio_search.png          real Egypt results
03_radio_playing.png         real Internet Radio stream
04_radio_error.png           debug Demo of the tested broken-stream state
05_usb_no_device.png         debug Demo; no mounted removable volume
06_usb_detected.png          debug Demo; no physical USB available
07_usb_scanning.png          debug Demo; no physical USB available
08_usb_library.png           real persisted Local Folder scan
09_usb_audio_playing.png     real SAF audio playback
10_usb_video_playing.png     real SAF video playback
10_usb_video_fullscreen.png  real fullscreen video surface
11_usb_removed.png           debug Demo; no physical USB available
12_bluetooth_regression.png  real phone Bluetooth state
13_permission_state.png      debug permission rendering
14_radio_offline_cache.png   real cached/offline state
15_light_theme.png           real system light theme
```

## Final engineering gate and change inventory

The final installed artifact passed all of the following on the connected phone:

```bash
./gradlew --no-configuration-cache :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew --no-configuration-cache :app:installDebug
./gradlew --no-configuration-cache :app:connectedDebugAndroidTest
```

The final results were `BUILD SUCCESSFUL` for assembly, JVM unit tests, Android lint, installation, and both connected smoke tests. A subsequent normal-mode cold launch and six rapid source switches left the Activity resumed, exposed exactly one active Media3 session, returned 68 healthy API records, reported zero mounted removable volumes, and produced no app fatal/security/network-main-thread/leak/player exception. The process-scoped evidence is retained at `artifacts/phone-radio-usb/final-verification/clean-launch-logcat.txt`.

The implementation inventory is:

- Created the Java Phone Edition under `app/src/main/java/com/hypernova/media/`: application/activity, immutable models, Media3 service/controller, public phone Bluetooth backend, Radio Browser API/repository/cache/backend, USB monitor/volume/scanner/permission layers, UI controllers/adapters/renderer, and the Canvas visualizer.
- Created debug/release-separated Demo Mode controllers under `app/src/debug/` and `app/src/release/`; the release implementation cannot expose generated preview state.
- Created the phone layouts `activity_main.xml`, `row_radio_station.xml`, and `row_media_item.xml`, plus vector icons, state/card/input backgrounds, responsive dimensions, and designed light/dark color/theme resources.
- Modified `app/build.gradle.kts`, `gradle/libs.versions.toml`, and `AndroidManifest.xml` for the separate phone application ID, Java/ViewBinding, Media3 ExoPlayer/session/HLS/UI dependencies, required public permissions, and media playback service.
- Replaced the starter Kotlin Activity with `com.hypernova.media.MainActivity`, and updated the physical-device instrumentation smoke tests.
- Created `artifacts/phone-radio-usb/` for the required physical-device review set and final log evidence.
- Updated this README without removing the archived AAOS integration specification below.

Before the feature work, a timestamped recoverable project backup was created at `backups/HyperNovaMedia_Before_RadioUsb_20260803_235430.tar.gz` (SHA-256 `88e5ca7447991e3c28721a266bd7f82e388e23446743270dd382cc06b73914`). A final per-file SHA-256 comparison against the pre-work manifest confirmed that `aosp_source_snapshot/` and `HyperNovaMedia_Handoff/` are byte-for-byte unchanged.

Exact serial-specific reproduction commands:

```bash
cd /home/ayman/ITI/Android-Apps/HyperNova_Media_Task_04
./gradlew --no-configuration-cache :app:assembleDebug
./gradlew --no-configuration-cache :app:installDebug
adb -s b129ebcb shell am force-stop com.hypernova.media.phone.debug
adb -s b129ebcb shell am start -W -n com.hypernova.media.phone.debug/com.hypernova.media.MainActivity
```

## NXP / final AAOS expectation

On the final NXP AAOS image, a mounted USB volume exposed by Android should be discovered through the same `StorageManager`, MediaStore-volume, and SAF contracts. Target-specific USB controller support, passthrough, kernel drivers, filesystem support, vold/mount policy, and volume exposure remain below the application layer. The Phone Edition does not alter or assume those layers.

## Known phone limitations

- An ordinary phone cannot act as an AAOS A2DP sink or browse another phone's AVRCP catalog.
- Radio availability and metadata depend on Radio Browser health data, the station endpoint, DNS, and network policy; catalog health cannot guarantee a stream remains reachable.
- Codec support depends on the physical phone's MediaCodec implementation.
- USB/OTG discovery and persistence depend on the phone's controller, power role, filesystem support, vold/mount policy, MediaStore indexing, and document provider.
- Video pauses when the Activity leaves the foreground; audio and radio continue through the MediaSession service.
- Portrait is requested for the main cockpit composition. Android 16 may relax orientation locking on some large-screen devices.

## Protected reference material

`aosp_source_snapshot/` and `HyperNovaMedia_Handoff/` are reference/handoff inputs only. Phone Edition development does not modify either directory, and it never touches the external AOSP tree under `/mnt/wwn-0x5002538e7006e10b-part3`.

---

# Archived AAOS Product Specification

The remainder of this document is the original AAOS-oriented specification, preserved for integration history. Its package name, car services, and source semantics do not describe the phone APK above.

# HyperNova Cockpit — Task 04: Media Android App

> **Project:** HyperNova Cockpit  
> **Task:** Task 04 — HyperNova Media  
> **Application package:** `com.hypernova.media`  
> **Target platform:** Custom AOSP / Android Automotive IVI image  
> **Orientation:** Portrait only  
> **Production baseline:** `1080 × 1920 px`, 9:16  
> **Reference board resolution:** `1536 × 1024 px`  
> **Implementation language:** Kotlin  
> **UI technology:** Android XML Views + ViewBinding  
> **Playback framework:** AndroidX Media3 + MediaSession  
> **Architecture:** Single Activity + MVVM + Source Manager + source-specific controllers  
> **Supported sources:** Radio, Bluetooth Phone, USB Audio, USB Video  
> **Cloud dependency:** None  
> **Data policy:** Real source state and real metadata only  
> **Status:** Ready for implementation  

---

# 1. Approved Visual Reference

![HyperNova Media States](assets/hypernova_media_states_reference.png)

This image is the approved visual reference for Task 04.

It defines six primary screens:

```text
1. MEDIA SOURCE HOME
2. RADIO PLAYING
3. USB AUDIO PLAYING
4. USB VIDEO PLAYING
5. BLUETOOTH PHONE PLAYING
6. BLUETOOTH CONNECTED — NO MEDIA
```

The implementation must preserve the same visual language across all screens:

- Same top header.
- Same source selector.
- Same cards and borders.
- Same color tokens.
- Same typography.
- Same icon family.
- Same touch-target rules.
- Same portrait geometry.
- Same source-state behavior.

Values such as `NOVA FM`, `101.4 FM`, `Ayman's Phone`, `Kingston 64GB`, `SYNTHWAVE DREAMS`, and `FUTURE DRIVE` are visual examples only. Production code must use real source data.

---

# 2. Product Definition

HyperNova Media is a premium automotive media-source controller.

It is **not** a Spotify-style application.

It supports exactly these primary sources:

```text
Radio
Bluetooth Phone
USB
```

The application must not contain:

- Album discovery.
- Artist discovery.
- Music recommendations.
- Subscription accounts.
- Cloud streaming catalog.
- Social features.
- Music marketplace.
- Smartphone-style bottom navigation.
- Spotify-style library.
- Fake playback information.

The product flow is:

```text
Select Source
    |
    v
Activate Real Source
    |
    v
Receive Real State and Metadata
    |
    v
Render Current Source
    |
    v
Expose Supported Controls
    |
    v
Publish State through MediaSession
```

---

# 3. Task Objective

The developer must build a complete Android Studio project that:

- Matches the approved six-screen reference.
- Supports Radio, Bluetooth Phone, USB Audio, and USB Video.
- Contains one large central media area.
- Uses real source state and metadata.
- Integrates with the HyperNova Launcher.
- Integrates with NOVA AI.
- Uses Android MediaSession.
- Uses AndroidX Media3 for USB audio and video.
- Integrates Bluetooth through the approved Android/AOSP Bluetooth stack.
- Integrates Radio through the approved platform/vendor tuner service.
- Handles source switching correctly.
- Handles audio focus from Navigation, Phone, and NOVA AI.
- Handles disconnected, scanning, unavailable, interrupted, and error states.
- Is ready for integration into the HyperNova AOSP image.

---

# 4. Source Ownership

| Data | Real owner |
|---|---|
| Radio frequency | Radio controller/tuner service |
| Station name | Radio metadata/tuner service |
| Signal state | Radio service |
| Connected phone | Bluetooth platform stack |
| Bluetooth title/artist | AVRCP or platform media session |
| Bluetooth artwork | AVRCP/media metadata when available |
| Bluetooth playback state | Bluetooth media state |
| USB device label | Mounted-volume/device layer |
| USB file list | USB scanner/index |
| USB metadata | Media extractor/Media3 |
| USB video state | Media3 player |
| Position and duration | Active source/player |
| Audio focus | Android audio framework |
| Active source | HyperNova `MediaSourceManager` |

The UI must never invent any of these values.

---

# 5. No Production Dummy Data

The production app must not contain:

```text
MockRadioRepository
FakeBluetoothDevice
DummyUsbVolume
HardcodedStation
HardcodedTrack
FakeProgressTicker
StaticConnectedState
DemoMediaSource
```

Test doubles are allowed only in:

```text
src/test/
src/androidTest/
```

When data is unavailable, show an honest state:

```text
No station selected
No phone connected
Phone connected — no media is playing
USB not connected
No supported media found
Metadata unavailable
Playback unavailable
```

A placeholder image may be used only when real artwork is missing. It must not pretend to be real album artwork.

---

# 6. High-Level Architecture

```text
+---------------------- HyperNova Media App ----------------------+
|                                                                 |
|  MediaActivity                                                  |
|       |                                                         |
|       v                                                         |
|  MediaFragment                                                  |
|       |                                                         |
|       v                                                         |
|  MediaViewModel                                                 |
|       |                                                         |
|       v                                                         |
|  MediaRepository                                                |
|       |                                                         |
|       v                                                         |
|  MediaSourceManager                                             |
|       |                                                         |
|       +--> RadioSourceController                                |
|       +--> BluetoothSourceController                            |
|       +--> UsbSourceController                                  |
|                                                                 |
|  HyperNovaMediaService                                          |
|       |                                                         |
|       +--> MediaSession                                         |
|       +--> Media3 Player for USB audio/video                    |
|       +--> Source-specific command adapter                      |
|                                                                 |
|  AudioFocusController                                           |
|  VehicleUxRestrictionClient                                     |
+-----------------------------------------------------------------+
             |                         |
             v                         v
      HyperNova Launcher           NOVA AI
```

---

# 7. Component Responsibilities

| Component | Responsibility |
|---|---|
| `MediaActivity` | Hosts the full-screen portrait UI |
| `MediaFragment` | Renders all source screens and states |
| `MediaViewModel` | Exposes immutable UI state |
| `MediaRepository` | Combines source and player state |
| `MediaSourceManager` | Activates/deactivates sources |
| `RadioSourceController` | Tune, seek, scan, presets |
| `BluetoothSourceController` | Connect, metadata, playback controls |
| `UsbSourceController` | USB monitoring, scanning, files |
| `HyperNovaMediaService` | MediaSession and playback lifecycle |
| `UsbPlayerController` | Media3 audio/video playback |
| `MediaSessionCoordinator` | Publishes unified media state |
| `AudioFocusController` | Handles audio interruptions |
| `VehicleUxRestrictionClient` | Applies driving restrictions |
| `AppNavigator` | Opens pairing/settings safely |

Recommended pattern:

```text
Single Activity
+
One primary MediaFragment
+
State-driven source renderers
+
One MediaSession service
+
StateFlow
```

Do not create one Activity per source.

---

# 8. Unified Source Interface

```kotlin
interface MediaSourceController {
    val sourceType: MediaSourceType
    val state: StateFlow<MediaSourceState>
    val capabilities: StateFlow<MediaSourceCapabilities>

    suspend fun activate(): SourceCommandResult
    suspend fun deactivate(): SourceCommandResult
    suspend fun play(): SourceCommandResult
    suspend fun pause(): SourceCommandResult
    suspend fun previous(): SourceCommandResult
    suspend fun next(): SourceCommandResult
    suspend fun seekTo(positionMs: Long): SourceCommandResult
    suspend fun stop(): SourceCommandResult
}
```

Capabilities:

```kotlin
data class MediaSourceCapabilities(
    val canPlay: Boolean,
    val canPause: Boolean,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val canSeek: Boolean,
    val canBrowse: Boolean,
    val canShowVideo: Boolean,
    val canShowSubtitles: Boolean,
    val canFullscreen: Boolean
)
```

The UI enables only confirmed capabilities.

---

# 9. Source Switching Flow

```text
User selects new source
        |
        v
Disable repeated presses
        |
        v
Pause or stop previous source
        |
        v
Deactivate previous source
        |
        v
Activate selected source
        |
        v
Wait for READY / ERROR
        |
        v
Update viewport, metadata, controls, and MediaSession
```

Rules:

- Never show metadata from the previous source.
- Never preserve the previous source progress.
- Do not show PLAYING before confirmation.
- Do not run two audible sources at once.
- Clear stale artwork and metadata.
- If activation fails, show a real source error.
- Persist the last source only when it remains available.

---

# 10. Required States

## Global

```text
SELECT_SOURCE
SWITCHING_SOURCE
BUFFERING
SEEKING
AUDIO_FOCUS_INTERRUPTED
PLAYBACK_ERROR
```

## Radio

```text
RADIO_READY
RADIO_PLAYING
RADIO_MUTED
RADIO_SCANNING
RADIO_NO_SIGNAL
RADIO_ERROR
```

## Bluetooth

```text
BLUETOOTH_DISABLED
BLUETOOTH_NO_PAIRED_DEVICE
BLUETOOTH_CONNECTING
BLUETOOTH_CONNECTED_NO_MEDIA
BLUETOOTH_PLAYING
BLUETOOTH_PAUSED
BLUETOOTH_METADATA_UNAVAILABLE
BLUETOOTH_DISCONNECTED
BLUETOOTH_ERROR
```

## USB

```text
USB_NOT_CONNECTED
USB_SCANNING
USB_READY
USB_AUDIO_PLAYING
USB_AUDIO_PAUSED
USB_VIDEO_PLAYING
USB_VIDEO_PAUSED
USB_DISCONNECTED
USB_UNSUPPORTED_FILE
USB_ERROR
```

---

# 11. Shared HyperNova Design System

The app must use:

```text
hypernova-design-system
```

The shared module defines:

- Colors.
- Dimensions.
- Typography.
- Card shapes.
- Buttons.
- Icons.
- Loading/error states.
- Automotive touch sizes.
- Press animations.

The Media developer must not redefine these values locally.

---

# 12. Color System

| Token | Hex | Usage |
|---|---|---|
| `hn_background_primary` | `#020A13` | Main background |
| `hn_background_secondary` | `#06121F` | Lower gradient |
| `hn_surface_primary` | `#071524` | Standard cards |
| `hn_surface_secondary` | `#0B1B2C` | Elevated areas |
| `hn_surface_overlay` | `#102337` | Selected source |
| `hn_border_primary` | `#506174` | Main border |
| `hn_border_subtle` | `#293847` | Dividers |
| `hn_primary_cyan` | `#25D9E8` | Active controls |
| `hn_primary_cyan_pressed` | `#1FC2D0` | Press state |
| `hn_primary_cyan_dark` | `#0B8493` | Cyan glow |
| `hn_text_primary` | `#F5F7FA` | Main text |
| `hn_text_secondary` | `#A7B0BE` | Secondary text |
| `hn_text_disabled` | `#687486` | Disabled text/icons |
| `hn_success` | `#39EA4B` | Healthy/playing |
| `hn_warning` | `#F5A623` | Scanning/connecting/interrupted |
| `hn_error` | `#FF5E68` | Real error |
| `hn_white` | `#FFFFFF` | High-emphasis control |
| `hn_transparent` | `#00000000` | Transparent |

Color rules:

- Cyan is the main active color.
- Green appears only for confirmed healthy or playing state.
- Amber appears for scanning, connecting, or interruption.
- Red appears only for real errors or destructive actions.
- Do not recolor the whole screen.
- Artwork and video may contain other colors.
- UI controls remain cyan, white, and gray.

---

# 13. Screen Baseline and Spacing

```text
Resolution: 1080 × 1920 px
Aspect ratio: 9:16
Logical baseline: approximately 540 × 960 dp
```

Spacing scale:

```text
4dp
8dp
12dp
16dp
20dp
24dp
32dp
```

Global dimensions:

```text
Screen horizontal margin: 16dp
Header height: 56dp
Source selector item height: 68–76dp
Section gap: 12dp
Main card radius: 22dp
Small card radius: 16dp
Card padding: 12–16dp
Primary button height: 48dp
Minimum touch target: 48dp
Main Play/Pause button: 64dp
```

---

# 14. Typography

Use `Roboto`.

| Element | Size | Weight |
|---|---:|---|
| App title | `18sp` | Medium |
| Header status | `9–10sp` | Medium |
| Source title | `13–14sp` | Medium |
| Source subtitle | `9–10sp` | Regular |
| Media title | `20–24sp` | Medium |
| Artist/station/device | `12–14sp` | Regular |
| Radio frequency | `32–40sp` | Medium |
| Body | `13sp` | Regular |
| Secondary | `10–11sp` | Regular |
| Button | `13sp` | Medium |
| Progress time | `11–12sp` | Regular |

Rules:

- Maximum title lines: 2.
- Use ellipsis for long text.
- Do not shrink important controls.
- Do not use decorative fonts.

---

# 15. Icon System

Use `Material Symbols Rounded`.

Required icons:

```text
Back
Media
Radio
Bluetooth
Bluetooth Connected
Bluetooth Off
Phone
USB
Audio File
Video File
Folder
Previous
Rewind 10
Play
Pause
Forward 10
Next
Mute
Volume
Fullscreen
Subtitle
Favorite
Scan
Signal
Change Device
Disconnect
Retry
Eject
Shuffle
Repeat
More
Aspect Ratio
Audio Track
Error
Warning
```

Rules:

- Same rounded outline style.
- Same stroke weight.
- Active icon: cyan.
- Inactive icon: gray.
- Healthy state: green.
- Warning: amber.
- Error: red.
- Every interactive icon needs a content description.

---

# 16. Fixed Screen Geometry

Every primary screen uses:

```text
Top Header
Source Selector
Large Main Content / Media Viewport
Media Information Card
Playback Controls
Source-Specific Secondary Controls
```

Do not add:

```text
Albums
Artists
Playlists
Recommendations
Queue
Streaming account
```

as main application navigation.

---

# 17. Top Header

Content:

```text
Back
HyperNova Media logo
MEDIA
Active source
Playback/connection state
Current time
```

Possible source labels:

```text
SELECT SOURCE
RADIO
BLUETOOTH
USB
USB VIDEO
```

Possible state labels:

```text
READY
PLAYING
PAUSED
CONNECTED
CONNECTING
SCANNING
DISCONNECTED
ERROR
```

Dimensions:

```text
Height: 56dp
Back touch target: 48dp
Logo: 28dp
Title: 18sp
Status dot: 6–8dp
```

---

# 18. Source Selector

Show exactly three equal source buttons:

```text
Radio | Bluetooth | USB
```

Each includes:

- Icon.
- Source name.
- Short availability state.

Selected style:

```text
Background: #102337
Border: 1dp #25D9E8
Icon: #25D9E8
Title: #25D9E8
Subtle cyan glow
```

Inactive style:

```text
Background: #071524
Border: 1dp #506174
Icon: #A7B0BE
Title: #F5F7FA
Subtitle: #A7B0BE
```

Runtime examples:

```text
Radio — FM / AM
Bluetooth — [Phone name] / No device
USB — [Volume label] / Not connected
```

---

# 19. Screen 1 — Media Source Home

This screen appears when no source is opened or when the user returns to source selection.

## Header

```text
MEDIA
SELECT SOURCE
Current time
```

## Source Cards

### Radio

```text
Radio
FM / AM
Live stations and favorites
Open Radio
```

### Bluetooth

Connected:

```text
Bluetooth Phone
[Phone name]
Stream audio from your phone
Open Bluetooth
```

Disconnected:

```text
Bluetooth Phone
No phone connected
Connect Phone
```

### USB

Connected:

```text
USB Storage
[Device label]
Play music and videos
Open USB
```

Disconnected:

```text
USB Storage
No device connected
Connect a USB device
```

Bottom text:

```text
Choose an audio source to get started
```

No fake media appears here.

---

# 20. Screen 2 — Radio Playing

Required content:

```text
Radio selected
Frequency
Station name
Live state
Signal state
Radio visualization
Radio controls
Station scan
Favorites
```

Central content displays:

- Radio tower icon.
- Real frequency.
- Station name when available.
- Audio spectrum visualization.
- `Live Radio`.

Do not display fake video.

Information card:

```text
Source badge: RADIO
Frequency
Station name
Live state
Signal state
```

Main controls:

```text
Previous Station
Seek Down
Favorite
Seek Up
Next Station
```

Secondary controls:

```text
Mute
Scan Stations
Favorites
```

Every action must call the real radio controller.

---

# 21. Radio Scanning

Display:

```text
SCANNING
Scanning radio stations…
Detected stations
Stop Scan
```

Rules:

- Do not select a fake station.
- Show each real detected frequency.
- Use amber/cyan processing state.
- Cancel safely.
- Restore previous station when appropriate.

---

# 22. Radio No Signal

Display:

```text
NO SIGNAL
Radio signal unavailable
Try another frequency or rescan
```

Actions:

```text
Seek
Scan Stations
```

Do not show `Strong Signal`.

---

# 23. Radio Architecture

```text
RadioSourceController
        |
        v
RadioTunerClient
        |
        v
Android Broadcast Radio / Vendor Tuner Service
```

Required operations:

```text
open()
close()
tune(frequency)
seekUp()
seekDown()
scan()
stopScan()
setMuted()
saveFavorite()
removeFavorite()
```

Keep the app vendor-independent above `RadioTunerClient`.

---

# 24. Screen 3 — USB Audio Playing

Required content:

```text
USB selected
Real device label
Artwork or placeholder
Track title
Artist
Album when available
Elapsed position
Duration
Playback controls
Shuffle
Repeat
More
```

Central viewport:

- Embedded artwork when available.
- HyperNova placeholder if missing.
- USB badge.
- Optional artwork fullscreen view.

Playback controls:

```text
Previous
Rewind 10s
Play/Pause
Forward 10s
Next
```

Secondary:

```text
Shuffle
Repeat
More
```

Only enable supported actions.

---

# 25. Screen 4 — USB Video Playing

Required content:

```text
USB selected
Real device label
Large video viewport
Video title
Video type/resolution when available
Progress
Playback controls
Aspect
Audio
More
```

Video viewport:

```text
Aspect ratio: 16:9
Rounded 22dp corners
Thin border
Fullscreen control
Real video frame
```

Do not display desktop player controls.

Video controls:

```text
Previous
Rewind 10s
Play/Pause
Forward 10s
Next
```

Secondary:

```text
Aspect
Audio Track
More
```

Subtitle appears only when a subtitle track exists.

---

# 26. USB Fullscreen Video

Fullscreen mode must:

- Preserve video aspect ratio.
- Use black background.
- Preserve playback position.
- Show an exit action.
- Auto-hide nonessential controls.
- Restore controls on touch.
- Respect safe insets.
- Follow vehicle video-safety restrictions.
- Avoid desktop-style UI.

---

# 27. USB Scanning

Display:

```text
SCANNING USB
Searching audio and video files
```

Show:

- Device label.
- Scan progress when available.
- Supported-file count.
- Cancel action.

Do not show a completed list before scanning finishes.

---

# 28. USB Not Connected

Display:

```text
USB not connected
Connect a USB storage device
```

Actions:

```text
Retry
Choose Source
```

No fake device information.

---

# 29. USB Unsupported File

Display:

```text
Unable to play this file
Unsupported or damaged media format
```

Actions:

```text
Skip File
Choose Another File
```

The application must not crash.

---

# 30. USB Architecture

```text
UsbSourceController
        |
        +--> UsbDeviceMonitor
        +--> MountedVolumeProvider
        +--> MediaFileScanner
        +--> UsbMediaIndex
        +--> UsbPlayerController
                    |
                    v
             AndroidX Media3
```

Metadata:

```text
File name
Title
Artist
Album
Duration
Artwork
MIME type
Folder
Subtitle tracks
URI
```

---

# 31. Screen 5 — Bluetooth Phone Playing

Required content:

```text
Bluetooth selected
Connected phone name
Connection state
Artwork when available
Track title
Artist
Album when available
Playback progress when available
Previous
Rewind 10s when supported
Play/Pause
Forward 10s when supported
Next
Shuffle/Repeat/More only when supported
```

Bluetooth rules:

- Receive phone audio through platform A2DP Sink behavior.
- Receive metadata/controls through AVRCP or approved platform media integration.
- Do not claim full phone-library browsing.
- Do not display video through normal A2DP.
- Do not fake metadata.
- Do not update progress unless position data exists.
- Do not claim command success before phone confirmation.

Central area:

- Real artwork when available.
- HyperNova placeholder when unavailable.
- Bluetooth badge.
- Optional audio-reactive background.
- Phone name.

---

# 32. Screen 6 — Bluetooth Connected, No Media

Display:

```text
Phone connected
No media is playing
Start music on your phone or press Play below
[Phone name]
Connected
```

Actions:

```text
Play
Change Device
Open Now Playing
```

Disabled:

```text
Previous
Next
```

Do not show:

- Fake artwork.
- Fake title.
- Fake artist.
- Fake duration.
- Moving progress.

---

# 33. Bluetooth Connecting

Display:

```text
CONNECTING
Connecting to [Phone Name]…
Waiting for Bluetooth audio and media controls
```

Actions:

```text
Cancel
Choose Another Device
```

Use amber/cyan, not green.

---

# 34. Bluetooth Disconnected

Display:

```text
DISCONNECTED
No phone connected
Connect a Bluetooth phone to stream audio
```

Actions:

```text
Connect Phone
Choose Device
```

On disconnect:

- Stop progress updates.
- Clear current metadata.
- Clear playing state.
- Publish disconnected state.
- Clear stale artwork.

---

# 35. Bluetooth Metadata Unavailable

Display:

```text
Audio connected
Media information unavailable
Playback controls may still be available
```

Enable only confirmed controls.

---

# 36. Bluetooth Architecture

```text
BluetoothSourceController
        |
        +--> Bluetooth state client
        +--> A2DP Sink audio state
        +--> AVRCP metadata
        +--> AVRCP controls
        +--> Platform MediaSession when available
```

Required states:

```text
BLUETOOTH_DISABLED
NO_PAIRED_DEVICE
PAIRING
CONNECTING
CONNECTED_NO_MEDIA
CONNECTED_PLAYING
CONNECTED_PAUSED
METADATA_UNAVAILABLE
DISCONNECTED
ERROR
```

---

# 37. Bluetooth Device Selection

Use a HyperNova-styled screen.

Sections:

```text
Paired Devices
Available Devices
```

Actions:

```text
Connect
Pair
Cancel
```

Safety note:

```text
Bluetooth setup is available while parked
```

Do not copy the stock Android Settings UI.

---

# 38. Media3 and Playback Service

Use:

```text
AndroidX Media3
ExoPlayer
MediaSessionService
```

for USB audio/video.

Recommended service:

```text
com.hypernova.media.playback.HyperNovaMediaService
```

Responsibilities:

- Playback lifecycle.
- MediaSession.
- Foreground playback.
- Metadata publishing.
- Transport controls.
- Active source publishing.
- Launcher/NOVA AI integration.
- Audio focus.

Radio and Bluetooth should adapt their state into the unified MediaSession where practical.

---

# 39. MediaSession Contract

MediaSession publishes:

```text
Title
Artist/station
Artwork
Duration
Position
Playback state
Available actions
Active source
```

Standard controls:

```text
play()
pause()
skipToPrevious()
skipToNext()
seekTo()
```

Custom commands:

```text
SELECT_RADIO
SELECT_BLUETOOTH
SELECT_USB
RADIO_SEEK_UP
RADIO_SEEK_DOWN
RADIO_SCAN
OPEN_SOURCE_SELECTOR
```

Validate every custom command.

---

# 40. Launcher Integration

The Launcher media card reads:

```text
Source badge
Artwork/placeholder
Title/station
Subtitle
Playback state
Progress when available
Previous
Play/Pause
Next
```

Launcher rules:

- Do not access Media internal databases.
- Do not show stale metadata.
- Do not enable unsupported controls.
- Clear old source state when switching.
- Use MediaSession as source of truth.

---

# 41. NOVA AI Integration

Supported commands:

```text
Play radio
Switch to Bluetooth
Play USB media
Pause media
Next track
Scan radio stations
Open USB video
```

Flow:

```text
NOVA AI
    |
    v
MediaSession or protected source command
    |
    v
HyperNova Media
    |
    v
Real source result
    |
    v
NOVA AI SUCCESS / ERROR
```

Results:

```text
ACCEPTED
IN_PROGRESS
COMPLETED
REJECTED
SOURCE_UNAVAILABLE
DEVICE_NOT_CONNECTED
UNSUPPORTED_ACTION
TIMEOUT
ERROR
```

NOVA AI waits for confirmation.

---

# 42. Audio Focus

## Navigation Guidance

```text
Navigation prompt
      |
      v
Duck active media
      |
      v
Restore after prompt
```

Visible state:

```text
Volume reduced for Navigation guidance
```

## NOVA AI Speech

NOVA AI may request temporary focus.

The active source may:

- Duck.
- Pause.
- Resume after TTS.

## Phone Call

```text
Phone call begins
      |
      v
Pause or mute media
      |
      v
Show interrupted state
```

Visible state:

```text
Playback paused for phone call
```

Resume only after real audio-focus and source state return.

---

# 43. Audio Focus Interrupted

Display:

```text
AUDIO FOCUS
Playback temporarily interrupted
[Reason]
```

Possible reasons:

```text
Navigation guidance
Phone call
NOVA AI response
System prompt
```

Use amber, not red.

---

# 44. Buffering

Display:

```text
BUFFERING
Preparing media…
```

Rules:

- Keep last confirmed progress.
- Do not show PLAYING.
- Disable unsupported controls.
- Allow cancel when supported.

---

# 45. Seeking

Display:

```text
SEEKING
Seeking to [Time]…
Waiting for playback confirmation
```

Rules:

- Separate requested and confirmed positions.
- Do not update confirmed progress before confirmation.
- Prevent repeated seek commands while pending.

---

# 46. Playback Error

Display:

```text
Unable to play media
[Readable reason]
No audio or video is playing
```

Actions:

```text
Try Again
Skip
Choose Source
```

Use red only for the error indicator.

---

# 47. Video Safety

Video must follow the approved product policy.

Recommended behavior:

```text
Parked -> Video visible
Moving -> Video paused, hidden, or audio-only
```

When restricted:

- Preserve position.
- Pause video or continue audio according to policy.
- Display a clear message.
- Do not show moving video behind overlays.
- Do not bypass the central UX restriction source.

---

# 48. Media UI State

```kotlin
data class MediaUiState(
    val selectedSource: MediaSourceType?,
    val sourceState: MediaSourceState,
    val capabilities: MediaSourceCapabilities,
    val metadata: MediaMetadataUi?,
    val playbackState: PlaybackUiState,
    val currentPositionMs: Long?,
    val durationMs: Long?,
    val volume: Int?,
    val isMuted: Boolean,
    val radioPresets: List<RadioPreset>,
    val bluetoothDevice: BluetoothDeviceUi?,
    val usbEntries: List<UsbMediaEntry>,
    val message: UiText?
)
```

Required enums:

```text
MediaSourceType
MediaSourceState
PlaybackUiState
MediaContentType
SourceCommandStatus
AudioFocusState
```

---

# 49. UI Events

```kotlin
sealed interface MediaUiEvent {
    data object BackPressed : MediaUiEvent
    data class SourceSelected(val source: MediaSourceType) : MediaUiEvent
    data object PlayPausePressed : MediaUiEvent
    data object PreviousPressed : MediaUiEvent
    data object NextPressed : MediaUiEvent
    data object RewindPressed : MediaUiEvent
    data object ForwardPressed : MediaUiEvent
    data class SeekRequested(val positionMs: Long) : MediaUiEvent
    data object ScanRadioPressed : MediaUiEvent
    data object FavoritePressed : MediaUiEvent
    data object ChangeBluetoothDevicePressed : MediaUiEvent
    data object DisconnectBluetoothPressed : MediaUiEvent
    data object FullscreenPressed : MediaUiEvent
    data object SubtitlePressed : MediaUiEvent
    data object EjectUsbPressed : MediaUiEvent
    data object RetryPressed : MediaUiEvent
}
```

---

# 50. Recommended Project Structure

```text
app/src/main/java/com/hypernova/media/
|
+-- MediaActivity.kt
|
+-- ui/
|   +-- MediaFragment.kt
|   +-- MediaViewModel.kt
|   +-- MediaUiState.kt
|   +-- MediaUiEvent.kt
|   +-- components/
|       +-- HeaderBinder.kt
|       +-- SourceSelectorBinder.kt
|       +-- ViewportBinder.kt
|       +-- MetadataBinder.kt
|       +-- PlaybackControlsBinder.kt
|       +-- SourceContentBinder.kt
|
+-- source/
|   +-- MediaSourceManager.kt
|   +-- MediaSourceController.kt
|   +-- radio/
|   |   +-- RadioSourceController.kt
|   |   +-- RadioTunerClient.kt
|   +-- bluetooth/
|   |   +-- BluetoothSourceController.kt
|   |   +-- BluetoothMediaClient.kt
|   +-- usb/
|       +-- UsbSourceController.kt
|       +-- UsbDeviceMonitor.kt
|       +-- MediaFileScanner.kt
|       +-- UsbMediaIndex.kt
|
+-- playback/
|   +-- HyperNovaMediaService.kt
|   +-- UsbPlayerController.kt
|   +-- MediaSessionCoordinator.kt
|   +-- AudioFocusController.kt
|
+-- model/
|   +-- MediaSourceType.kt
|   +-- MediaSourceState.kt
|   +-- MediaMetadataModel.kt
|   +-- RadioStation.kt
|   +-- BluetoothMediaState.kt
|   +-- UsbMediaEntry.kt
|
+-- integration/
|   +-- VehicleUxRestrictionClient.kt
|   +-- NovaAiMediaCommandAdapter.kt
|
+-- util/
    +-- UiText.kt
    +-- Result.kt
    +-- TimeFormatter.kt
```

---

# 51. Recommended Layout Files

```text
activity_media.xml
fragment_media.xml
view_media_header.xml
view_media_source_selector.xml
view_media_source_home.xml
view_radio_playing.xml
view_usb_audio_playing.xml
view_usb_video_playing.xml
view_bluetooth_playing.xml
view_bluetooth_no_media.xml
view_media_information.xml
view_media_progress.xml
view_media_playback_controls.xml
view_media_secondary_controls.xml
view_state_switching_source.xml
view_state_radio_scanning.xml
view_state_radio_no_signal.xml
view_state_bluetooth_connecting.xml
view_state_bluetooth_disconnected.xml
view_state_usb_scanning.xml
view_state_usb_not_connected.xml
view_state_audio_focus.xml
view_state_playback_error.xml
dialog_bluetooth_devices.xml
dialog_usb_eject.xml
```

---

# 52. Suggested View IDs

## Header

```text
btnBack
ivMediaLogo
tvMediaTitle
ivActiveSource
tvActiveSource
viewMediaStatusDot
tvMediaStatus
tvCurrentTime
```

## Source Selector

```text
sourceRadio
sourceBluetooth
sourceUsb
ivRadioSource
ivBluetoothSource
ivUsbSource
tvRadioState
tvBluetoothState
tvUsbState
```

## Media Area

```text
mediaViewport
playerView
radioVisualization
ivMediaArtwork
ivSourceBadge
btnFullscreen
```

## Metadata

```text
tvMediaTitlePrimary
tvMediaSubtitle
tvMediaTertiary
tvDeviceName
```

## Progress

```text
mediaSeekBar
tvElapsedTime
tvDuration
tvLiveIndicator
```

## Controls

```text
btnPrevious
btnRewind
btnPlayPause
btnForward
btnNext
btnMute
btnShuffle
btnRepeat
btnMore
btnAspect
btnAudioTrack
```

## Source Actions

```text
btnOpenRadio
btnConnectPhone
btnOpenUsb
btnScanStations
btnFavorites
btnChangeDevice
btnDisconnect
btnOpenNowPlaying
btnEjectUsb
```

---

# 53. Manifest

```xml
<activity
    android:name=".MediaActivity"
    android:exported="true"
    android:screenOrientation="portrait">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

Media service:

```xml
<service
    android:name=".playback.HyperNovaMediaService"
    android:exported="true"
    android:foregroundServiceType="mediaPlayback">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
    </intent-filter>
</service>
```

---

# 54. Permissions

Possible permissions:

```text
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK
android.permission.BLUETOOTH_CONNECT
android.permission.BLUETOOTH_SCAN
android.permission.POST_NOTIFICATIONS
android.permission.READ_MEDIA_AUDIO
android.permission.READ_MEDIA_VIDEO
android.permission.ACCESS_NETWORK_STATE
```

Platform Radio and Bluetooth may additionally require:

- System-app installation.
- Platform signing.
- Vendor permissions.
- SELinux policy.
- Product-specific service access.

Document every privileged requirement.

---

# 55. IPC Security

Use the shared signature permission for custom source commands:

```xml
<permission
    android:name="com.hypernova.permission.ACCESS_COCKPIT_SERVICES"
    android:protectionLevel="signature" />
```

Rules:

- Validate custom MediaSession commands.
- Validate caller package/signature where required.
- Use content URIs for USB media.
- Do not expose private device identifiers unnecessarily.
- Do not export debug services in release builds.

---

# 56. Contract Versioning

Expose versions for custom contracts:

```text
getApiVersion()
getServiceVersion()
```

Recommended:

```text
HyperNova Media API version: 1
```

On mismatch:

- Reject unsupported custom commands.
- Keep standard MediaSession controls where possible.
- Log the mismatch.
- Show a readable unavailable state when necessary.

---

# 57. State Persistence

Persist only safe state:

```text
Last selected source
Last radio station
Favorite stations
Last USB folder URI
Approved USB playback position
Mute state
```

Do not persist:

```text
False Bluetooth connected state
Stale Bluetooth metadata
Invalid USB URI
Playing state after source removal
Unconfirmed playback position
```

---

# 58. Driving Restrictions

When moving:

- Source selection remains available.
- Bluetooth pairing may be blocked.
- Complex USB browsing may be limited.
- Video follows the product safety policy.
- Text-heavy lists may be simplified.
- Voice commands remain available.

When parked:

- Bluetooth pairing may be enabled.
- USB browsing may be expanded.
- Video controls may be fully available.
- Subtitle/audio-track selection may be enabled.

Use the approved `VehicleUxRestrictionClient`.

---

# 59. Performance Requirements

- Do not block the main thread.
- Use coroutines and `StateFlow`.
- Scan USB off the UI thread.
- Avoid repeated artwork decoding.
- Cache thumbnails safely.
- Use hardware video decoding where available.
- Stop video rendering when hidden.
- Stop radio visualization when hidden.
- Avoid re-scanning unchanged volumes.
- Prevent duplicate source activation.
- Release player, radio, and Bluetooth resources correctly.
- Avoid service and Binder leaks.

---

# 60. Logging

Use tags:

```text
HN-Media
HN-MediaSource
HN-Radio
HN-BluetoothMedia
HN-UsbMedia
HN-Playback
HN-MediaSession
HN-AudioFocus
```

Log:

- Source changes.
- Activation/deactivation.
- Radio tuning/scanning.
- Bluetooth connection state.
- USB mount/removal.
- Playback state.
- MediaSession commands.
- Audio-focus changes.
- Error codes.

Do not log:

- Private Bluetooth identifiers.
- Sensitive phone metadata.
- Full personal USB paths.
- Raw media contents.
- Authentication data.

---

# 61. Accessibility and Automotive UX

- Minimum touch target: `48dp`.
- Main Play/Pause: `64dp`.
- High contrast.
- Source state shown as text and color.
- Content descriptions for icons.
- No required long press.
- No required multi-touch.
- No tiny video controls.
- One-press source selection.
- Short readable errors.
- Do not depend on color only.

---

# 62. Animation Rules

| Animation | Duration |
|---|---:|
| Source selection | `160–220ms` |
| Card press | `100ms` |
| Playback-state change | `160ms` |
| Radio scan | Continuous, subtle |
| Bluetooth connecting | Continuous, subtle |
| USB scanning | Continuous, subtle |
| Error pulse | `350–500ms` |
| Fullscreen controls | `160–220ms` |

No rapid flashing.

---

# 63. Testing Requirements

## Source Switching

- Radio to Bluetooth.
- Bluetooth to USB.
- USB to Radio.
- Activation failure.
- Rapid repeated selection.
- Previous source stops/pauses.
- Stale metadata is cleared.

## Radio

- Ready.
- Playing.
- Tune.
- Seek up/down.
- Scan.
- Stop scan.
- Favorite.
- No signal.
- Service unavailable.

## Bluetooth

- Bluetooth disabled.
- No paired device.
- Pairing restriction.
- Connecting.
- Connected no media.
- Playing.
- Paused.
- Metadata unavailable.
- Phone disconnected.
- AVRCP command support.
- Unsupported seek.

## USB

- Device connected.
- Device removed.
- Scan.
- Audio playback.
- Video playback.
- Fullscreen.
- Subtitle/audio track.
- Unsupported file.
- Damaged file.
- Eject.
- Driving restrictions.

## MediaSession

- Launcher receives metadata.
- Launcher controls play/pause.
- NOVA AI commands work.
- Source badge changes.
- Old metadata clears.
- Available actions match capabilities.

## Audio Focus

- Navigation ducking.
- NOVA AI speech.
- Phone-call pause.
- Focus restore.
- Focus loss during video.

## Visual

- Six primary screens match the reference.
- HyperNova colors are used.
- No clipped text.
- No overlapping controls.
- 9:16 portrait fit.
- Video viewport is 16:9.
- Touch sizes are correct.
- No Spotify-style content.

---

# 64. Development Order

```text
1. Freeze package name and shared contracts
2. Import HyperNova design system
3. Create project and dark theme
4. Build Header
5. Build Source Selector
6. Build Media Source Home
7. Build Radio Playing screen
8. Build USB Audio screen
9. Build USB Video screen
10. Build Bluetooth Playing screen
11. Build Bluetooth No Media screen
12. Implement MediaUiState
13. Implement MediaSourceManager
14. Implement RadioSourceController
15. Integrate platform/vendor Radio service
16. Implement BluetoothSourceController
17. Integrate A2DP/AVRCP/platform state
18. Implement UsbDeviceMonitor
19. Implement USB scanner/index
20. Implement Media3 USB audio playback
21. Implement Media3 USB video playback
22. Implement fullscreen and subtitle/audio-track handling
23. Implement HyperNovaMediaService
24. Implement MediaSession
25. Integrate Launcher
26. Integrate NOVA AI
27. Implement source-switch policy
28. Implement AudioFocusController
29. Implement driving restrictions
30. Add connecting/scanning/error states
31. Add IPC security/versioning
32. Test all sources
33. Build debug and release APKs
34. Integrate into AOSP
35. Validate on target portrait display
```

---

# 65. Required Deliverables

```text
1. Complete Android Studio project
2. Source code
3. HyperNova design-system version
4. IPC-contract version
5. Six primary screens
6. All transition/error states
7. Radio controller
8. Bluetooth controller
9. USB controller
10. MediaSourceManager
11. Media3 USB audio playback
12. Media3 USB video playback
13. Fullscreen video
14. Subtitle/audio-track support when available
15. MediaSession service
16. Launcher integration
17. NOVA AI integration
18. Audio-focus implementation
19. Driving-restriction implementation
20. Debug APK
21. Release APK
22. Screenshot of every required state
23. Source-switch test report
24. MediaSession integration report
25. Permission documentation
26. AOSP integration notes
27. Radio/Bluetooth/USB dependency notes
28. Codec support notes
29. Asset license/source notes
30. Updated final README
```

Suggested APK names:

```text
HyperNovaMedia-debug.apk
HyperNovaMedia-release.apk
```

---

# 66. Definition of Done

## Visual

- [ ] Six approved screens are implemented.
- [ ] UI matches the supplied reference.
- [ ] HyperNova colors are used.
- [ ] Header is consistent.
- [ ] Source selector is consistent.
- [ ] Radio uses a radio visualization, not fake video.
- [ ] USB audio uses real metadata.
- [ ] USB video uses a real video viewport.
- [ ] Bluetooth uses real metadata only.
- [ ] Bluetooth no-media state has no fake track.
- [ ] Controls meet `48dp`.
- [ ] Main Play/Pause is `64dp`.
- [ ] No Spotify-style screen exists.
- [ ] No desktop/VLC UI exists.
- [ ] No text clipping.
- [ ] No overlapping controls.

## Architecture

- [ ] Package is `com.hypernova.media`.
- [ ] No production dummy data exists.
- [ ] Unified source interface exists.
- [ ] MediaSourceManager exists.
- [ ] Radio controller exists.
- [ ] Bluetooth controller exists.
- [ ] USB controller exists.
- [ ] Media3 is used for USB audio/video.
- [ ] MediaSession is implemented.
- [ ] Capabilities control UI actions.
- [ ] Source switching clears stale metadata.
- [ ] Bluetooth does not claim A2DP video.
- [ ] USB removal is handled.
- [ ] Radio no-signal is handled.
- [ ] Version mismatch is handled.
- [ ] Privileged requirements are documented.

## Integration

- [ ] Launcher receives real media state.
- [ ] Launcher controls active playback.
- [ ] NOVA AI commands work.
- [ ] Commands wait for source confirmation.
- [ ] Navigation audio ducking works.
- [ ] Phone-call interruption works.
- [ ] Video safety policy works.
- [ ] Driving restrictions work.

## Delivery

- [ ] Debug APK generated.
- [ ] Release APK generated.
- [ ] State screenshots included.
- [ ] Test reports included.
- [ ] AOSP integration notes included.
- [ ] Final README updated.

---

# 67. Questions and Answers

## Why are there only three sources?

The agreed HyperNova Media scope is Radio, Bluetooth Phone, and USB.

## Is this a Spotify-style app?

No. It is an automotive media-source controller.

## Can Bluetooth display video?

Not through normal A2DP. Video requires a separate supported mechanism.

## Can Bluetooth browse the entire phone library?

Not by default. Only explicitly supported capabilities may be exposed.

## Why is USB video shown in a large viewport?

The app must support real video files while preserving a consistent automotive layout.

## How is USB media played?

Through AndroidX Media3/ExoPlayer using real media URIs.

## How does the Launcher receive metadata?

Through MediaSession.

## How does NOVA AI control Media?

Through MediaSession and protected custom source commands.

## What happens during Navigation guidance?

The active source ducks or pauses according to audio-focus policy.

## What happens during a phone call?

Media pauses or mutes and resumes only after real focus and source state return.

## What is the most important rule?

Never display source, connection, metadata, playback position, or command success before the real source confirms it.

---

# 68. Final Instruction

Build HyperNova Media as a production automotive media-source controller.

The final result must combine:

```text
Shared HyperNova design
+
Radio
+
Bluetooth Phone
+
USB Audio
+
USB Video
+
MediaSession
+
Launcher integration
+
NOVA AI integration
+
Audio focus
+
Automotive safety
```

Do not add streaming-service pages, fake source state, fake metadata, unsupported Bluetooth video, or production dummy data without an approved architecture change.
