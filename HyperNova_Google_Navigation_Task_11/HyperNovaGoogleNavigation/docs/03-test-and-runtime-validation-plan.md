# Test and Runtime Validation Plan

> Superseded on 2026-08-20 because the live Android 16 guest has no GMS.
> Use the live acceptance sequence in `README.md` for the WebView build.

Date: 2026-08-19
Status: Pre-key automated verification complete; post-key runtime validation
remains pending. **No Google runtime PASS is claimed.**

## Scope and non-claims

- This plan covers the current implemented code only and is documentation-only.
- Runtime device validation requires a Google Play services-enabled device, a
  provisioned restricted key, accepted Google terms, and live map/route
  exercise. Until all of that is observed, only build/unit-test results may be
  reported, and they must not be called runtime validation.
- No secret value, no SHA-1 literal, and no fabricated route/device data appear
  in this plan.
- `HyperNova_Contracts`, NOVA, Launcher, the legacy Navigation project, and the
  AAOS image are not modified by this plan.

## Environment prerequisites

- JDK 17 or 21 (Gradle 8.14.5 does not run on the JDK 25 currently default on
  this machine). Example:
  `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.
- Android SDK (compileSdk 36.1) reachable via `sdk.dir` in `local.properties`
  or `ANDROID_HOME`/`ANDROID_SDK_ROOT`.
- A Google Cloud project with billing and the Navigation SDK + Places API (New)
  enabled (see `README.md`).

## Existing automated coverage

- `app/src/test/java/com/hypernova/navigation/FrozenNavigationIdentityTest.kt`
  — asserts the frozen identity (API version 1, package, service FQCN, bind and
  open actions). Runs under `testDebugUnitTest` and currently passes
  (`tests="1" failures="0" errors="0"`).
- Pure unit suites cover API-key placeholder detection, durable destination
  token TTL/opacity/stability, request replay/coalescing/conflicts, Navigator
  readiness, public state projection, route geometry validation/bounding, and
  route/progress state reduction. The current total is 55 tests with 0
  failures and 0 errors.
- `:hypernova-contracts` (read-only) carries
  `NavigationContractInstrumentedTest.java` (ABI/parcel checks) in the contracts
  repository; it is an external reference, not executed here.
- The app module currently has **no `androidTest` sources**; device-level
  verification is manual per the checklists below unless an instrumented test
  is added in a later phase.

## Commands

Run from `HyperNovaGoogleNavigation/` with a compatible JDK:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:testDebugUnitTest
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:lintDebug
```

Current pre-key findings:

- `assembleDebug`: PASS with the safe placeholder configuration.
- `testDebugUnitTest`: PASS, 55 tests, 0 failures/errors.
- `lintDebug`: PASS, `No issues found`.
- `assembleRelease`: PASS, producing an intentionally unsigned APK only.

## Stage A — Pre-key build-only validation (no Google calls)

Do this before provisioning any key. A fresh clone has only
`secrets.properties.example`, so the build must compile and run in the no-key
state.

1. **PASS:** `assembleDebug` succeeds with no local `secrets.properties`; the APK is
   produced at `app/build/outputs/apk/debug/app-debug.apk`.
2. **PASS:** `testDebugUnitTest` passes all 55 tests.
3. **PASS:** `lintDebug` reports no issues.
4. **PASS:** the generated `BuildConfig` contains the safe placeholder
   `YOUR_GOOGLE_MAPS_API_KEY`, and `GoogleApiKeyPolicy.isConfigured(...)` is
   false for it.
5. **NOT VALIDATED — ENVIRONMENT BLOCKER:** launch the debug APK on a
   device/emulator (no key) and confirm the
   `CONFIGURATION_REQUIRED` behavior: no map surface is created, the
   "Google configuration required" panel is shown with no action button, and
   the app does not crash (`MainActivity.kt:55-57,205-208`;
   `NavigationRuntime.kt:250-261`).
6. Code review confirms `NavigationApi.setApiKey()` is guarded by
   `GoogleApiKeyPolicy`; runtime observation remains part of step 5
   (`HyperNovaNavigationApplication.kt:13-16`).

## Stage B — Key provisioning validation (Cloud Console)

1. Confirm billing is enabled on the Google Cloud project.
2. Confirm the **Navigation SDK** and **Places API (New)** are both enabled.
3. Create a restricted API key for package `com.hypernova.navigation` using the
   SHA-1 of the signing certificate that will sign the tested APK (see
   `README.md` for the `keytool` commands; no SHA-1 literal is recorded here).
4. Copy `secrets.properties.example` to `secrets.properties` at the project
   root and set `MAPS_API_KEY` to the real key value.
5. Re-run `assembleDebug` and verify the generated `BuildConfig.MAPS_API_KEY`
   is now the configured value and that `NavigationApi.setApiKey(...)` is called
   once before any Navigation SDK object is used.
6. Verify the key value is never printed to logs and never committed
   (`.gitignore` covers `secrets.properties`).

## Stage C — Post-key device validation (Google-enabled device)

Prerequisite: a physical device or emulator with Google Play services that
satisfies the SDK's minimum requirements, signed with the certificate whose
SHA-1 is registered on the key.

1. **Play services present:** `GoogleApiAvailability.isGooglePlayServicesAvailable(...)`
   returns `SUCCESS`; otherwise the app must report `GOOGLE_SERVICES_UNAVAILABLE`
   (`NavigationRuntime.kt:240-242,59-66`).
2. **Location:** grant `ACCESS_FINE_LOCATION`; denial must produce
   `LOCATION_UNAVAILABLE` with the "Allow location" action
   (`MainActivity.kt:214-217`).
3. **Initialization:** fresh install then launch. Expected observed transitions:
   `INITIALIZING` → `READY_IDLE` once terms are accepted and the Navigator is
   ready. Any of `TERMS_REQUIRED`, `GOOGLE_SERVICES_UNAVAILABLE`,
   `LOCATION_UNAVAILABLE`, or `ERROR` (with `GOOGLE_NOT_AUTHORIZED` /
   `GOOGLE_INITIALIZATION_ERROR`) is a documented state, not a silent hang
   (`NavigationRuntime.kt:169-217`).
4. **Map:** a real Google map surface renders (via `SupportNavigationFragment`).
5. **Search:** enter a query; at most `MAX_DESTINATION_RESULTS` (4) real Places
   results are returned, each carrying title/subtitle/category
   (`GooglePlacesSearchGateway.kt:23-51`).
6. **Route preview:** selecting a result transitions `CALCULATING` →
   `PREVIEW_READY` with SDK-derived ETA and distance and real Google route
   geometry (`NavigationSessionStore.kt:47-88`); no fabricated line is drawn.
7. **Guidance:** "Start guidance" transitions to `GUIDING`; progress snapshots
   are published at no more than the frozen 1-second cadence
   (`NavigationStatusPublisher.kt:58-67`).
8. **Reroute/arrival:** route changes increment `routeVersion`; arrival
   publishes `ARRIVED` with zeroed metrics (`NavigationSessionStore.kt:104-177`).
9. **Cancel:** `cancelNavigation` clears the route and publishes a new idle
   snapshot (`NavigationSessionStore.kt:195-214`).
10. **Simulation (debug only):** the "Demo drive" action runs the SDK Simulator
    and the UI labels it "SIMULATED — Google SDK demo"; position derived from
    simulation is never presented as live GPS (`SimulationControllerFactory.kt`).
11. **Background/foreground:** dismissing the Activity does not destroy the
    navigation session; `Navigator.cleanup()` is not tied to
    `Activity.onDestroy()`.

## Stage D — AIDL command validation (frozen contract)

Exercise the service directly (or via a test client) using the frozen identity
(`com.hypernova.navigation`, service FQCN, `BIND_COMMAND` action, API version
`1`). Run both with no key (Stage A states) and with a key (Stage C states).

| Operation | Expected behavior |
|---|---|
| `getApiVersion` | Returns `1`. |
| `searchDestinations` | `ACCEPTED` then a final `CONFIRMED` (≤4 destinations) or `REJECTED` (`NO_RESULTS`); without a key `UNAVAILABLE` "Google Maps configuration is required." |
| `getSavedDestinations` | `CONFIRMED` with saved entries, or `REJECTED` `NO_SAVED_DESTINATIONS`; dedup via `(requestId, operation)`. |
| `setDestination` | `ACCEPTED` (`STATE_CALCULATING`) immediately, then final `CONFIRMED` reporting `STATE_IDLE`, ETA, and distance; expired token → `REJECTED` `DESTINATION_EXPIRED`; unknown token → `REJECTED` `DESTINATION_NOT_FOUND`; timeout → `TIMEOUT`; without a key → `UNAVAILABLE` `SERVICE_UNAVAILABLE`. |
| `cancelNavigation` | `ACCEPTED` then `CONFIRMED` with `STATE_IDLE`; a newer idle snapshot is published. |
| `getCurrentNavigationState` | One-shot `CONFIRMED` with current state and metrics only when `GUIDING`/`REROUTING`/`ARRIVED`. |
| `getCurrentNavigationRoutePreview` | One-shot `CONFIRMED` with bounded (≤128 points) geometry, or "Route preview is unavailable." |
| `registerNavigationStatusCallback` | Immediate route and progress snapshots on registration. |
| `unregisterNavigationStatusCallback` | Stops delivery for that observer only. |

Also verify:

- Request dedup retention is 10 minutes; identical in-flight requests coalesce,
  completed requests replay the final result, and the same `requestId` with a
  different argument fingerprint is rejected (`RequestRegistry.kt:18-63`).
- Binder threads are never blocked: work is dispatched on the bounded
  service scope (`NavigationCommandService.kt:19-20`), and callbacks tolerate
  dead Binder clients (`NavigationCommandController.kt:397-415`).

## Stage E — Launcher validation

Launcher must keep working against the new provider (read-only external
client). Use the existing Launcher or its status client.

1. Register the additive observer after bind; expect an immediate route
   snapshot and progress snapshot (`NavigationStatusPublisher.kt:34-39`).
2. Expect a newer empty/idle snapshot after `cancelNavigation`.
3. Route geometry must be real Google geometry with at least two points,
   bounded to 128 points, valid lat/lng, and monotonic `routeVersion`;
   progress `progressSequence` monotonic
   (`ContractProjection.kt:60-96`, `RouteGeometry.kt:7-33`).
4. Observer registration is optional at runtime; one-shot
   `getCurrentNavigationState` and `getCurrentNavigationRoutePreview` must still
   work for a fallback path.
5. Run against both the no-key build (states project to `STATE_ERROR`/
   `UNAVAILABLE`) and the keyed build (normal flows).

## Stage F — NOVA validation

NOVA must open and drive the app through the frozen contract (read-only
external client).

1. Bind with the exact frozen identity; an API version other than 1 must be
   rejected.
2. Dispatch only search, saved, set, and cancel (`setDestination` accepts the
   opaque `destination_id`).
3. NOVA calls `openNavigation()` on the `ACCEPTED` result for `setDestination`,
   not on final confirmation — so the service must accept and record a
   destination before an Activity exists (`NavigationRuntime.kt:41-49,103-141`).
4. Callbacks are retained until a final mapped status arrives; the service
   coalesces duplicates by `(requestId, operation)`.
5. Confirm NOVA's `CONTROL_COCKPIT_APPS` signature permission is granted (same
   signing certificate as Navigation), and that Navigation requests but does
   not redefine it (`app/src/main/AndroidManifest.xml:8,42`).

## Stage G — Release and signing gate

1. `:app:assembleRelease` builds successfully; the output is unsigned and is
   not a deploy candidate until team signing policy is supplied.
2. `lintDebug` is green with no issues.
3. Inspect the merged APK manifest: package `com.hypernova.navigation`, exported
   `MainActivity` (MAIN/LAUNCHER + OPEN), exported service behind
   `CONTROL_COCKPIT_APPS`, `allowBackup="false"`.
4. Verify APK signing and, for cross-app runs, that NOVA, Launcher, and
   Navigation share compatible certificates so the signature permission is
   actually granted.
5. Re-run Stages A–F against the signed artifact used for delivery.
6. Report results in a separate device-validation record; do not fold build
   success into a runtime PASS claim.

## Non-claims

- No device/runtime PASS is recorded by this document.
- No SHA-1, API key, or secret value is asserted.
- The AAOS RPi5 image is unchanged; this plan does not validate the
  platform-signed image path.
