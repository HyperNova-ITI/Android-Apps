# HyperNova Media — Video / YouTube implementation report

## Scope and safety

- Project modified: `/home/ayman/ITI/Android-Apps/HyperNova_Media_Task_04` only.
- No AOSP image was built or modified. No flash, reboot, remount, `adb root`, ADB restart, device restart, uninstall, or system-service restart occurred.
- The runtime target `10.42.0.226:5555` remained online. Final `adb get-state`: `device`.
- HyperNova Launcher remained the registered HOME activity: `com.hypernova.launcher/.MainActivity`; final top-resumed activity was also the Launcher.

## Original HyperNova architecture findings

- `MainActivity` is an XML/View portrait shell. Its `NonScrollingNestedScrollView` deliberately prevents page-level vertical scrolling; bounded controls can still scroll internally.
- Radio uses `RadioRepository` and `InternetRadioBackend` and keeps the existing Radio Browser/station flow.
- Bluetooth uses `PhoneBluetoothAudioBackend` and its existing remote media-session controls.
- Audio playback remains service-owned: `HyperNovaPlaybackService` owns ExoPlayer and MediaSession; `PlaybackController` uses an activity-safe MediaController proxy.
- The prior USB path was `UsbVolumeRepository`, with mounted-volume discovery and generic media infrastructure. It is no longer instantiated, started, selected, or exposed in the release UI.
- The accepted visualizer was preserved: animated rings, particles, perspective/depth grid, Light/Dark treatment; the cockpit/sine waveform and lower ambient arcs remain disabled.

## MotorGuard reference findings

The `media-nav-settings-voice` branch informed the architecture rather than its UI/theme:

- Treat video/web playback as a dedicated source, separate from the audio MediaSession.
- Use an application-context persistent WebView for mobile YouTube, with JavaScript, DOM storage, cookie persistence, in-pane navigation and fullscreen callbacks.
- Keep WebView media state separate from Radio/Bluetooth session state.
- The reference local `MediaStore.Video`/Media3 approach was useful for the earlier implementation, but was removed from HyperNova's final user-facing Video feature at the user's request.

## Final HyperNova architecture

- The fixed selector is **Radio | Bluetooth | Video**. The third card is styled with the HyperNova video icon and the concise subtitle **YouTube**.
- Video is YouTube-only. It attaches the one retained `YoutubeWebSession` WebView to the existing Video card area; there is no Local toggle, list, empty state, permission prompt or player UI.
- `YoutubeWebSession` has a single application-context WebView with JavaScript, DOM storage, cookie acceptance (including third-party cookies), in-pane URL loading and cookie flush on detach. The WebView itself is never recreated for source changes.
- The Video pane has compact HyperNova **← Back**, **Home**, and fullscreen controls. Back is only visible when it can use browser history or safely escape a non-YouTube page. Home always loads `https://m.youtube.com/` in the same WebView without clearing cookies, DOM storage, WebStorage or cache.
- Explicit selection of the top Video card resets the *page* to YouTube Home and clears stale navigation history after Home finishes. It preserves the same WebView and authenticated/cookie session. Thus Radio/Bluetooth → Video cannot reopen Google Account Help/Gmail/old sign-in pages.
- Android/main Media Back priority is: fullscreen exit; then WebView history/safe same-session Home escape; then the existing Media-to-Launcher HOME behavior. The permanent header Back button remains in place.
- A minimal JavaScript bridge watches only actual `navigator.mediaSession` / HTML5-video play state. Header status is **YOUTUBE · READY** when there is no reliable browser media state, otherwise genuine **PLAYING**/**PAUSED**. It does not alter the Radio/Bluetooth MediaSession.
- The main layout remains non-scrolling. The WebView may scroll its own page.

## USB and Local Video removal

- USB is absent from all user-facing Media selection and behavior.
- The task-created Local Video UI and implementation were removed: `VideoLibraryState`, `VideoLibraryRepository`, `VideoPlayback`, `VideoSafetyPolicy`, and `NoVehicleVideoSafetyPolicy`.
- `READ_MEDIA_VIDEO` and `READ_MEDIA_VISUAL_USER_SELECTED` were removed from the manifest because they were solely added for the removed Local Video feature. Existing `INTERNET` and `ACCESS_NETWORK_STATE` remain; pre-existing audio/storage declarations were not changed.
- Existing generic USB/storage classes were retained without blind deletion. They have no active release UI/startup path.

## Intent / quick-command audit

- The only Media open route found is the existing `com.hypernova.media.action.OPEN` intent filter. No source-selection extra, Video quick command, voice command, or external Video-selection action exists in this project.
- No new command system was invented. Consequently there was no existing quick-command route to modify or invoke for Video.

## Files modified

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/hypernova/media/HyperNovaMediaApplication.java`
- `app/src/main/java/com/hypernova/media/MainActivity.java`
- `app/src/main/java/com/hypernova/media/model/MediaSourceType.java`
- `app/src/main/java/com/hypernova/media/ui/MainUiRenderer.java`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/values/dimens.xml`
- `app/src/main/res/values/strings.xml`

## Files created and retained

- `app/src/main/java/com/hypernova/media/video/YoutubeWebSession.java`
- `app/src/main/res/drawable/ic_video.xml`

## Files removed

- `app/src/main/java/com/hypernova/media/video/VideoLibraryState.java` — Local-video UI state only.
- `app/src/main/java/com/hypernova/media/video/VideoLibraryRepository.java` — `MediaStore.Video` feature only.
- `app/src/main/java/com/hypernova/media/video/VideoPlayback.java` — Local Media3 player only.
- `app/src/main/java/com/hypernova/media/video/VideoSafetyPolicy.java` and `NoVehicleVideoSafetyPolicy.java` — only used by the removed local-video feature.

Pre-edit backups are in `backups/video-source-20260813-001909/` and `backups/youtube-only-navigation-20260813-014303/`.

## Build, signing and install

- Java 21 command: `./gradlew --no-daemon --max-workers=10 clean assembleRelease`
- Result: **BUILD SUCCESSFUL** (45 tasks; full transcript: `CODEX_OUTPUT/build.log`).
- Unsigned APK: `app/build/outputs/apk/release/app-release-unsigned.apk`.
- Platform-signed runtime APK: `CODEX_OUTPUT/apk/HyperNovaMedia-runtime.apk`.
- `apksigner verify --verbose --print-certs`: v2 and v3 signatures verified with the permitted existing RPi AOSP platform key.
- Runtime installation: `adb -s 10.42.0.226:5555 install -r CODEX_OUTPUT/apk/HyperNovaMedia-runtime.apk` succeeded. The target rejected incremental install (`INSTALL_FAILED_SESSION_INVALID`), then ADB's normal streamed fallback succeeded. No uninstall occurred.

## RPi runtime test matrix

| Test | Status | Evidence |
|---|---|---|
| Device connected / no reboot | VERIFIED ON RPI | Precheck and final `get-state` both `device`; `runtime_test.log` |
| Launcher remains HOME | VERIFIED ON RPI | Resolved HOME stayed `com.hypernova.launcher/.MainActivity` |
| Selector is Radio / Bluetooth / Video; no USB/Local UI | VERIFIED ON RPI | `screenshots/youtube_home.png` |
| Video source opens real mobile YouTube | VERIFIED ON RPI | `screenshots/youtube_home.png`; `YouTube Home` accessibility state |
| Status integration | VERIFIED ON RPI | Initial `YOUTUBE · READY`; actual HTML5/media state later reported `YOUTUBE · PAUSED` |
| Google Account Help opens in-pane | VERIFIED ON RPI | `screenshots/youtube_google_help.png` |
| In-pane Web Back from Google Help | VERIFIED ON RPI | Returned to Google account chooser/history predecessor: `screenshots/youtube_web_back.png` |
| In-pane Home from Google Help | VERIFIED ON RPI | Returned same WebView to YouTube Home: `screenshots/youtube_home_button.png` |
| YouTube page → Back | VERIFIED ON RPI | You tab then Back returned to YouTube Home: `screenshots/youtube_same_domain_back.png` |
| Radio → explicit Video re-entry resets page | VERIFIED ON RPI | `screenshots/youtube_reenter_video.png` shows YouTube Home and no visible web Back control |
| Fullscreen then Android Back | VERIFIED ON RPI | `screenshots/youtube_fullscreen.png` and `screenshots/youtube_after_fullscreen_back.png`; returned to pane without app restart |
| Main Media Back with no Web history | VERIFIED ON RPI | Header Back returned to `com.hypernova.launcher/.MainActivity` |
| Radio / Bluetooth regression | SOURCE-CODE VERIFIED ONLY for this follow-up | No Radio/Bluetooth code was changed in this follow-up; earlier feature-cycle RPi screenshots remain `radio.png` / `bluetooth.png` |
| Local video | REMOVED BY REQUIREMENT | No Local UI, permission or `MediaStore.Video` entry point remains |
| Actual YouTube video playback | NOT TESTED | No specific playable video was started in this navigation-focused follow-up |
| Quick command opening Video | NOT TESTED / NOT APPLICABLE | Audit found no pre-existing Video selection quick-command route |

## Outputs and logcat

- Screenshots: `CODEX_OUTPUT/screenshots/`, including `youtube_home.png`, `youtube_google_help.png`, `youtube_web_back.png`, `youtube_home_button.png`, and `youtube_reenter_video.png`.
- Runtime transcript: `CODEX_OUTPUT/runtime_test.log`.
- Relevant logs: `CODEX_OUTPUT/logcat.txt`.
- Observed WebView/Chromium output includes target WebGL/capability messages. No `AndroidRuntime` fatal exception for `com.hypernova.media` was observed during the follow-up flow.

## Permissions

- Online YouTube requires existing `INTERNET` and `ACCESS_NETWORK_STATE`.
- No Local Video runtime permission remains.

## Git status

`CODEX_OUTPUT/git_status.txt` contains the exact final `git status --short`. It includes pre-existing unrelated user changes (visualizer, `bg_hero.xml`, portrait dimensions, other sibling-workspace paths) that were preserved. Task output and backups are intentionally untracked.
