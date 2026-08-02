# Limitations

## Navigation route detail contract

The frozen Navigation API has no `getCurrentState()` or status callback.
`getSavedDestinations()` is non-mutating and includes `navigationState`, so the
Launcher can authoritatively distinguish idle, calculating, active, arrived,
and error.

For that operation, Navigation returns:

- `selectedDestination = null`
- `etaSeconds = -1`
- `distanceMeters = -1`

Therefore a route created in the Navigation UI can be shown as **Route active**,
but its destination, ETA, and remaining distance cannot safely be retrieved.
The Launcher leaves those fields unavailable.

Future API needed: a read-only `getCurrentState()` plus state-change callback
that returns the current destination, remaining ETA, and remaining distance.

The installed test Navigation APK declares a signature permission and is
data-installed. The debug Launcher shared its debug signer and connected. The
final AOSP Launcher is platform-signed; if Navigation remains signed by a
different key, Android will deny that signature permission. The fix is product
signing alignment or a dedicated correctly protected read-only status API—not
a Launcher bypass and not a Navigation source edit in this task.

## Media source variants

The sibling Android Studio Media project currently contains only a stub
Activity and no MediaSession service. The AOSP device tree Media application
does implement and export
`com.hypernova.media.playback.HyperNovaMediaSessionService`, which matches the
existing Launcher registry and was verified at runtime.

If the stub sibling APK is installed instead of the AOSP Media app, the
Launcher correctly reports installed/no MediaSession and has no track data.
A real session must be implemented by Media; the Launcher will not fake one.

## Climate API availability

The frozen Climate contract includes `getCurrentState()`, but the inspected
Climate source does not register or implement `ClimateCommandService` or the
planned read-only `ClimateStatusService`. Its Ethernet and VHAL backends are
also implementation shells, and its debug UI preview values are explicitly
fake. The Launcher never reads those preview values.

The Launcher therefore tries the existing frozen read call when a service is
actually declared, then reads standard AAOS HVAC properties. This needs
`android.car.permission.CONTROL_CAR_CLIMATE`; an ordinary adb-installed build
may not receive it and will show `HVAC permission unavailable`. The AOSP module
platform-signs the Launcher.

Future preferred API: implement the documented read-only Climate status
service/callback in Climate. That app is read-only for this task, so it was not
added here.

## Phone contract and privacy

The Phone sibling directory contains a specification and visual asset, but no
application source, manifest, service, or frozen AIDL status API. Phone was not
installed on the connected target.

The Launcher can currently observe only:

- Bluetooth enabled/disabled state.
- Whether Telecom has a call-capable phone account.
- Whether Telecom reports an active call.

It cannot safely display a device name, connection profile, contact, number,
call duration, or detailed call state. `READ_PHONE_STATE` or the platform
`READ_PRIVILEGED_PHONE_STATE` permission is required. Without it, the card says
phone status unavailable.

Future API needed: a privacy-filtered, signature-protected Phone status service
with connection/call callbacks and no raw contacts by default.

## Settings contract

The Settings sibling directory contains no implementation or status service.
The deployed AOSP Settings app is based on Car Settings and exposes the OPEN
activity but no Launcher summary API. Per this task's framework-API rule, the
Launcher observes Android state directly.

It does not expose update/restart-required state because no authoritative
public source for those HyperNova-specific values exists.

## Runtime validation boundaries

- Climate and Phone installation/runtime behavior could not be exercised on
  the connected target because those packages were absent.
- No state-changing Navigation command was issued merely to create an active
  test route.
- No other app was installed, disabled, changed, or rebuilt to simulate package
  events.
- The AOSP module build was not run because the shell had no configured product
  environment.
