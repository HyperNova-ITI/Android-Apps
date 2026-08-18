# Changed Files

## Launcher project

| File | Reason |
|---|---|
| `app/build.gradle.kts` | Compile the frozen Contracts source read-only and add the AAOS `android.car` compile library. |
| `app/src/main/AndroidManifest.xml` | Add required read permissions and package/service visibility while preserving HOME/LAUNCHER filters. |
| `app/src/debug/res/values/strings.xml` | Keep the development-only `HyperNova Launcher Dev` label. |
| `app/src/main/res/values/strings.xml` | Add honest Navigation, Media, Settings, Climate, Phone, and production app-label text. |
| `app/src/main/java/com/hypernova/launcher/MainActivity.kt` | Own new clients/observers, refresh on lifecycle/package events, and render card state without redesign. |
| `core/integration/AppAvailability.kt` | Represent availability-check failure separately. |
| `core/integration/AppAvailabilityMonitor.kt` | Observe package add/remove/change/replace events. |
| `core/navigation/NavigationStatusSnapshot.kt` | Define Navigation runtime state and contract-state mapping. |
| `core/navigation/NavigationStatusClient.kt` | Bind the frozen service and issue only the safe non-mutating status-bearing request. |
| `core/media/MediaSessionSnapshot.kt` | Preserve explicit playback states, including paused/stopped/error. |
| `core/media/MediaSessionClient.kt` | Verify the actual service exists and publish detailed real playback/error state. |
| `core/settings/SystemSettingsSnapshot.kt` | Define read-only system summary data. |
| `core/settings/SystemSettingsClient.kt` | Observe Wi-Fi, Bluetooth, brightness, and volume framework state. |
| `core/climate/ClimateSnapshot.kt` | Define source, HVAC availability, and nullable confirmed values. |
| `core/climate/ClimateStatusClient.kt` | Read existing Climate AIDL when present and guarded AAOS HVAC properties otherwise. |
| `core/phone/PhoneSnapshot.kt` | Define nullable authoritative phone/call state. |
| `core/phone/PhoneStatusClient.kt` | Observe Telecom phone-account/call state and Bluetooth enabled state. |
| `core/state/LauncherUiState.kt` | Separate package availability, runtime connection, active state, and errors. |
| `core/state/LauncherStateController.kt` | Aggregate all real snapshots into existing card content. |
| `app/src/test/java/com/hypernova/launcher/core/navigation/NavigationStatusMapperTest.kt` | Verify all frozen Navigation states. |
| `app/src/test/java/com/hypernova/launcher/core/state/IntegratedAppStateTest.kt` | Verify availability/connection/activity remain separate. |

Paths beginning with `core/` above are below
`app/src/main/java/com/hypernova/launcher/`.

## AOSP device tree

Only this existing file was replaced:

`/mnt/wwn-0x5002538e7006e10b-part3/device/hypernova/cockpit/apps/HyperNovaLauncher/HyperNovaLauncher.apk`

`Android.bp` was inspected but not modified. No other AOSP APK was touched.

## Explicit scope statement

No source, manifest, Gradle file, AIDL, resource, generated file, or APK was
modified in Navigation, Media, Settings, Climate, Phone, Driver Profile, NOVA
AI, Weather, or `HyperNova_Contracts`.
