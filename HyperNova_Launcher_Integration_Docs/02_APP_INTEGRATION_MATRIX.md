# Application Integration Matrix

| Application | Package | Launch mechanism | Runtime state source | States shown | Permissions | Lifecycle | Fallback |
|---|---|---|---|---|---|---|---|
| Navigation | `com.hypernova.navigation` | `com.hypernova.navigation.action.OPEN`, then package launcher activity | Frozen `INavigationCommandService`; safe `getSavedDestinations()` query because every response carries `navigationState` | Not installed, not launchable, connecting, idle, calculating, active, arrived, error | `com.hypernova.permission.CONTROL_COCKPIT_APPS` (signature) | Bind in `onStart`, safe query on connect/resume, unbind in `onStop`, reconnect on package change/binder death | If service/permission is unavailable, show service unavailable. Never issue `setDestination()` or `cancelNavigation()` for status. |
| Media | `com.hypernova.media` | `com.hypernova.media.action.OPEN`, then package launcher activity | Existing `MediaSessionClient` using the exported `com.hypernova.media.playback.HyperNovaMediaSessionService` and Media3 legacy-browser conversion | Missing, installed/no service, connecting, session/no item, buffering, playing, paused, stopped, ended, error | Service is exported; no private database access | Connect in `onStart`, Player callbacks for metadata/playback, position update only while playing, release in `onStop`, reconnect on package change | Missing service is installed/no session, not fabricated metadata. |
| Settings | `com.hypernova.settings` | `com.hypernova.settings.action.OPEN`; AOSP Settings resolves to `HyperNovaSettingsActivities$HomeActivity` | `WifiManager`, read-only `Settings.System`/`Settings.Global`, `AudioManager` | Package availability plus real Wi-Fi, Bluetooth, brightness, and media volume values | `ACCESS_WIFI_STATE`; Settings Provider and AudioManager reads | Wi-Fi/Bluetooth/volume broadcasts and content observers in `onStart`/`onStop`; refresh on resume | Any unreadable field is `—`; if all fields fail, system state is unavailable. |
| Climate | `com.hypernova.climate` | `com.hypernova.climate.action.OPEN`, then package launcher activity | Frozen `IClimateCommandService.getCurrentState()` when implemented; otherwise standard AAOS `CarPropertyManager` HVAC properties | Missing, connecting, service/VHAL error, HVAC unavailable, available/stale, power off/on, real target temperature/fan/AUTO | `com.hypernova.permission.CONTROL_COCKPIT_APPS`; `android.car.permission.CONTROL_CAR_CLIMATE` | Bind/query or connect/register VHAL callbacks in `onStart`; refresh on resume; unbind/unsubscribe/disconnect in `onStop` | Permission/service/property failures show unavailable. No temperature or fan default is invented. |
| Phone | `com.hypernova.phone` | `com.hypernova.phone.action.OPEN`, then package launcher activity | `TelecomManager.isInCall()` and call-capable phone accounts; Bluetooth enabled state from read-only `Settings.Global` | Missing, status unavailable, Bluetooth off, no phone connected, phone connected, call active | `READ_PHONE_STATE` or platform `READ_PRIVILEGED_PHONE_STATE` | Telecom/phone/Bluetooth broadcasts and content observer while started; refresh on resume | Without Telecom permission, show phone status unavailable; never show a device, contact, number, or call that was not returned by Android. |

## Exact installed-target observations

On the connected AAOS target during verification:

- HOME resolved to `com.hypernova.launcher/.MainActivity`.
- Navigation OPEN resolved to `com.hypernova.navigation/.MainActivity`.
- Media OPEN resolved to `com.hypernova.media/.MainActivity`.
- Settings OPEN resolved to
  `com.hypernova.settings/com.android.car.settings.hypernova.HyperNovaSettingsActivities$HomeActivity`.
- Climate and Phone were not installed.
- Media exported the exact framework `MediaBrowserService` component registered
  in `AppRegistry`.

## Availability refresh

`AppAvailabilityMonitor` listens for `PACKAGE_ADDED`, `PACKAGE_REMOVED`,
`PACKAGE_CHANGED`, and `PACKAGE_REPLACED` with the `package:` data scheme.
Availability is also recomputed on every Launcher resume. This covers changes
while HOME is visible and while the Launcher was stopped behind another app.
