# HyperNova Google Maps Navigation

Drop-in `com.hypernova.navigation` provider for the HyperNova cockpit. This
version targets the project’s Android 16 bare-AOSP NXP guest: it uses Google
Maps JavaScript, Places API (New), and the Routes library in the guest’s system
WebView and has no Google Play Services dependency.

The frozen Navigation AIDL v1, package name, service name, bind/open actions,
signature permission, launcher callbacks, and NOVA request semantics are
unchanged.

## Runtime architecture

`HyperNovaNavigationApplication` owns one `NavigationRuntime` and one
process-lifetime `GoogleMapsWebGateway`. The gateway’s WebView starts when the
process starts and remains available while `MainActivity` is closed. Therefore
an AIDL search or route request issued by NOVA from the launcher does not depend
on the Navigation screen being visible.

When the activity opens, the same WebView is attached to its map container. It
is detached on activity destruction without destroying the process-owned maps
engine.

```text
Gemini / local router
        |
        | structured navigation action
        v
NOVA Android command client
        |
        | frozen Navigation AIDL v1
        v
NavigationCommandService -> NavigationRuntime
        |                       |
        |                       +-> DestinationTokenStore
        v
GoogleMapsWebGateway
        |
        +-> Maps JavaScript API (visible Google map)
        +-> Places API (New) (maximum four real results)
        +-> Routes library (real route geometry, ETA, distance)
```

Gemini interprets language and selects tools. Android Navigation owns the
authoritative Google Place IDs, opaque AIDL destination tokens, route result,
and final success/failure callback. NOVA must only claim that a destination was
set after the final `STATUS_CONFIRMED` result.

See [docs/04-non-gms-google-maps-web-architecture.md](docs/04-non-gms-google-maps-web-architecture.md)
for the detailed Gemini boundary and runtime failure behavior.

## What is supported

- Real interactive Google roadmap on the Android 16 AOSP guest.
- Google Places text search, bounded to four AIDL results.
- Opaque search/saved-destination tokens.
- Real Google route preview, geometry, ETA, and distance.
- Launcher route/status callbacks through the frozen contract.
- Headless searches and route preparation while the launcher is visible.
- Honest error propagation for missing key, authorization, network, no route,
  timeout, expired token, and internal errors.

The current AOSP image has no GNSS provider. A recent Android location is used
when available; otherwise route preview uses the frozen ITI Smart Village demo
origin (`30.07112, 31.02075`) and the UI labels it `DEMO ORIGIN — live Google
route`. The Google route itself is real; the origin is explicitly a demo input.

Native Google Navigation SDK turn-by-turn guidance is not claimed by this
build. That feature requires a GMS-enabled Android image. `setDestination`
prepares a route and never starts a trip, matching frozen Navigation AIDL v1.

## Google Cloud configuration

Use the existing NOVA Cloud project and enable:

1. Maps JavaScript API
2. Places API (New)
3. Routes API

Create a dedicated browser key. Do not reuse the Gemini/Vertex key.

Application restriction:

```text
Websites
https://nova.hypernova.local/*
```

API restrictions:

```text
Maps JavaScript API
Places API (New)
Routes API
```

The maps document uses the fixed HTTPS origin
`https://nova.hypernova.local/` via `WebView.loadDataWithBaseURL`. The key is a
browser credential and is visible to the WebView by design; website/API
restrictions and low per-API quotas are the security and billing controls.

## Local key file

Create this ignored file at the project root:

```text
<this-project>/secrets.properties
```

Contents:

```properties
MAPS_API_KEY=YOUR_RESTRICTED_BROWSER_KEY
```

Never commit the file. `GoogleApiKeyPolicy` rejects placeholders and unrelated
64-character hexadecimal tokens.

## Build and verification

From this project directory:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
ANDROID_HOME="$HOME/Android/Sdk" \
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Or run:

```bash
./scripts/prepare_mustafa_apk.sh
```

The deployable debug APK is copied to:

```text
deliverables/google-navigation/HyperNovaGoogleNavigation-debug.apk
```

The preparation script also creates an ignored `SHA256SUMS.txt` containing only
the APK filename—never a developer laptop path. No APK/checksum is packaged in
Git before a real restricted key build succeeds.

The installer performs these gates before replacing the installed provider:

- replacement APK signing certificate matches the frozen cockpit certificate;
- Android 16 guest is reachable;
- a valid system WebView exists;
- Android has a default internet route;
- the current Navigation APK is backed up;
- installed and replacement certificates match.

Run it only after the key-enabled build succeeds:

```bash
ADB_SERIAL=192.168.0.100:5555 \
./deliverables/google-navigation/INSTALL_ON_NXP.sh
```

## Live acceptance sequence

1. Confirm Google map renders with visible Google attribution.
2. Search `maintenance center near ITI Smart Village` and receive no more than
   four real Places results.
3. Select one result and confirm a Google route preview, ETA, and distance.
4. Return home and repeat the search through NOVA while Navigation is closed.
5. Confirm NOVA waits for `STATUS_CONFIRMED` before speaking success.
6. Confirm `setDestination` does not start guidance.
7. Disable the internet route and verify NOVA reports unavailable instead of
   claiming success.

## Official references

- https://developers.google.com/maps/documentation/javascript/browsersupport
- https://developers.google.com/maps/documentation/javascript/place-search
- https://developers.google.com/maps/documentation/javascript/routes/get-a-route
- https://developers.google.com/maps/api-security-best-practices
- https://developers.google.com/maps/documentation/places/web-service/policies
