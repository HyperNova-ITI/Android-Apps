# Existing Navigation Integration Audit

Date: 2026-08-19  
Status: Phase 0 source audit complete

## Scope and authority

This audit covers the current source under `HyperNova_Contracts`,
`HyperNova_Navigation_Task_03`, `HyperNova_NOVA_AI_Task_02`,
`HyperNova_Launcher_Task_01`,
`HyperNova_Navigation_Status_Integration_Docs`, `HyperNova_RPi5_AAOS`, and
`HyperNova_Nova_Visuals`.

`HyperNova_Contracts/contracts` is the wire-contract authority. Legacy app code
and integration documents explain behavior but cannot override that module.
No inspected existing file was modified.

## Frozen external identity

| Item | Required value | Source |
|---|---|---|
| Application/package | `com.hypernova.navigation` | `NavigationContract.java:5` |
| Open action | `com.hypernova.navigation.action.OPEN` | `NavigationContract.java:6` |
| Command service FQCN | `com.hypernova.navigation.service.NavigationCommandService` | `NavigationContract.java:7-8` |
| Bind action | `com.hypernova.navigation.action.BIND_COMMAND` | `NavigationContract.java:9-10` |
| API version | `1` | `HyperNovaContract.java:3-5` |
| Control permission | `com.hypernova.permission.CONTROL_COCKPIT_APPS` | `HyperNovaContract.java:7-8` |

The new APK must keep this identity exactly. NOVA and Launcher both construct an
explicit `ComponentName(PACKAGE_NAME, COMMAND_SERVICE)` and also set the frozen
bind action. See `NavigationCommandClient.kt:119-129` and
`NavigationStatusClient.kt:239-249`.

## Current AIDL surface

`INavigationCommandService.aidl:7-46` defines nine methods:

1. `int getApiVersion()`
2. `searchDestinations(requestId, query, callback)`
3. `getSavedDestinations(requestId, callback)`
4. `setDestination(requestId, destinationId, callback)`
5. `cancelNavigation(requestId, callback)`
6. `getCurrentNavigationState(requestId, callback)`
7. `getCurrentNavigationRoutePreview(requestId, callback)`
8. `registerNavigationStatusCallback(callback)`
9. `unregisterNavigationStatusCallback(callback)`

The service interface itself is not `oneway`. Its callback interfaces are:

- `INavigationCommandCallback.onResult(NavigationResult)` — one-way;
- `INavigationRoutePreviewCallback.onResult(NavigationRoutePreviewResult)` —
  one-way;
- `INavigationStatusCallback.onRouteSnapshot(...)` and
  `onProgressSnapshot(...)` — one-way.

The four original command operations retain API v1 behavior. Current-state,
route-preview, and observer APIs are additive. The `NavigationResult` field and
Parcel order remains unchanged; route geometry uses separate parcelables. See
`HyperNova_Navigation_Status_Integration_Docs/03_CONTRACT_CHANGE.md:36-47` and
the ABI checks in `NavigationContractInstrumentedTest.java:23-52,73-165`.

## Result and state semantics

Shared status values are `ACCEPTED`, `CONFIRMED`, `REJECTED`, `UNAVAILABLE`,
`TIMEOUT`, and `CANCELLED` (`HyperNovaContract.java:10-15`). The public
navigation states are `IDLE`, `CALCULATING`, `ACTIVE`, `ARRIVED`, and `ERROR`
(`NavigationContract.java:32-36`). There is intentionally no public
`ROUTE_PREVIEW` constant.

For valid legacy commands, the provider normally returns `ACCEPTED` quickly and
then one final result. Invalid arguments can be rejected directly. The one-shot
state and preview queries return one final response without an `ACCEPTED`
intermediate result (`NavigationCommandController.kt:211-269`).

The legacy implementation maps its internal prepared `ROUTE_PREVIEW` state to
contract `STATE_IDLE`. `setDestination` is confirmed only after a route plan is
authoritatively ready, and that confirmation reports `STATE_IDLE`, ETA, and
distance without starting guidance (`NavigationResultFactory.kt:85-124` and
`NavigationCommandPolicies.kt:19-26`). This separation is compatibility-critical:

```text
setDestination ACCEPTED -> NOVA opens Navigation immediately
route calculation continues in the shared application session
setDestination CONFIRMED -> route preview is ready
driver action -> start guidance -> public ACTIVE state
```

## Destination identity

`NavigationDestination` contains `id`, `source`, `title`, `subtitle`, `category`,
and `distanceMeters` (`NavigationDestination.java:12-33`). Its contract comment
states that the ID is opaque to NOVA, search IDs remain valid for at least the
contract TTL, and saved IDs remain valid while the destination exists
(`NavigationDestination.java:6-10`). `distanceMeters == -1` means unavailable.

The current constants require at most four search results, a ten-minute search
token TTL, a ten-second search timeout, and a twenty-second route timeout
(`NavigationContract.java:19-23`). The legacy token store generates random
search IDs and stable hashed saved IDs; `setDestination` distinguishes expired
and unknown tokens (`DestinationStore.kt:16-25,32-90,130-156`). The new provider
must preserve the external semantics but will map its own opaque token to an
internal Google place record and Google Place ID.

## Request IDs and idempotency

The frozen dedup retention is ten minutes (`HyperNovaContract.java:17`). Legacy
command identity is `(requestId, operation)` plus an argument fingerprint:

- same key and fingerprint while in flight: replay `ACCEPTED` and coalesce the
  callback;
- same key and fingerprint after completion: replay the final result;
- same key with different arguments: reject as invalid;
- expired registry entry: treat as a new logical request.

See `RequestRegistry.kt:26-107` and
`NavigationCommandController.kt:506-557`. Binder work is dispatched off Binder
threads by a bounded executor. The new implementation must preserve these
properties and must not block a Binder thread on Places or route calculation.

## NOVA assumptions

`NavigationCommandClient`:

- binds using the exact frozen identity;
- rejects an API version other than 1 (`NavigationCommandClient.kt:31-47`);
- dispatches only search, saved, set, and cancel (`:164-191`);
- retains callbacks until a final mapped status (`:144-162`);
- calls `openNavigation()` on the `ACCEPTED` result for `setDestination`, not on
  final confirmation (`:152-158,199-208`);
- starts the Activity with the frozen OPEN action, package restriction, and
  `FLAG_ACTIVITY_NEW_TASK`.

Therefore the service cannot require an Activity to exist before accepting and
recording a destination request. On first use, Google terms may require an
Activity before the Navigator can finish initialization; the request must remain
queued in shared application state so NOVA's immediate OPEN completes that flow.

The Android NOVA command model currently carries either a search `query` or an
opaque `destination_id` (`CommandModels.kt:7-8` and
`CommandWireCodec.kt:75-83`). No `place_id`, Maps-grounding field, or
Maps-grounded destination representation exists in the inspected NOVA Android
source/docs. The external Gemini agent implementation is not present in this
workspace, so its current raw grounding payload cannot be asserted. The v1
query/token flow is nevertheless fully specified and requires no NOVA change.

## Launcher assumptions

Launcher registers the additive observer and immediately performs an
authoritative one-shot current-state query after binding
(`NavigationStatusClient.kt:141-165`). It requests the one-shot route preview
for confirmed `CALCULATING`, `ACTIVE`, or `ARRIVED` snapshots
(`:70-108,302-324`). Observer registration is optional at runtime so an older
service can fall back to one-shot state (`:276-299`).

Launcher rejects stale data using route ID, monotonic route version, and
monotonic progress sequence. It validates latitude/longitude, ignores negative
unavailable metrics, bounds route geometry to 128 points, and requires at least
two points (`NavigationStatusSnapshot.kt:49-129,131-255`).

The new application must publish:

- a route snapshot immediately on observer registration and whenever route
  identity or public state changes;
- a lightweight progress snapshot immediately on registration and at no more
  than the frozen one-second cadence for ordinary progress;
- a newer empty/idle snapshot on cancellation;
- route geometry that is real Google route geometry, never a fabricated line.

## Manifest, permission, and launch behavior

The legacy Navigation manifest establishes the drop-in shape:

- exported `MainActivity`, `singleTop`, portrait;
- MAIN/LAUNCHER plus OPEN/DEFAULT intent filters;
- no HOME category;
- exported `.service.NavigationCommandService` protected by
  `CONTROL_COCKPIT_APPS` and filtered by BIND action.

NOVA defines `CONTROL_COCKPIT_APPS` with `protectionLevel="signature"`
(`HyperNova_NOVA_AI_Task_02/app/src/main/AndroidManifest.xml:8-10`). Navigation
must request but must not redefine it. Launcher requests the permission and
declares package/action visibility for Navigation
(`HyperNova_Launcher_Task_01/app/src/main/AndroidManifest.xml:20-22,68-78`).

The shared cockpit controller opens sibling apps with package-restricted public
actions, uses HOME for the Launcher, and uses `NEW_TASK | CLEAR_TOP |
SINGLE_TOP` (`CockpitNavigationController.java:19-34,95-113`). The new UI must
reuse the shared visual module and retain those Back/Home conventions.

## Single-session ownership

The reusable architectural fact in the legacy app is ownership, not its runtime
providers: `HyperNovaNavigationApplication` creates one repository, and both
Activity and Binder service observe/control it (`HyperNovaNavigationApplication.kt:8-27`,
`NavigationCommandService.kt:100-111`, `MainActivity.kt:207-212`). The new app
must likewise have one application-scoped navigation session and one Google
Navigator. It must not copy MapLibre, Nominatim, Overpass, OSRM,
`SoftwareRouteOverlay`, or the simulated-production provider.

## Signing and AAOS image assumptions

The current RPi tree imports the Navigation APK as module
`HyperNovaNavigation`, re-signs it with the platform certificate, installs it to
`system_ext`, disables dex preopt, and includes it in `PRODUCT_PACKAGES`
(`vendor/hypernova/rpi5/apps/HyperNovaNavigation/Android.bp:1-14` and
`vendor/hypernova/rpi5/hypernova_rpi5_content.mk:5-14`). NOVA and Launcher are
also platform-signed imports. This satisfies the signature permission in the
image.

For standalone development, NOVA, Launcher, and Navigation must be signed with
compatible certificates for the signature permission to be granted. A Google
API key must include the SHA-1 of each actual signing certificate used. No SHA-1
is assumed or documented as a literal.

No file under `HyperNova_RPi5_AAOS` will change until the standalone APK is
functionally verified and explicit approval is given.

## Source/document drift

- Older guides describe only the four original command operations. The current
  AIDL source includes the additive state/preview/observer surface.
- `09_LIMITATIONS.md:51-58` says the AOSP checkout has no Navigation module. The
  current RPi tree does contain the platform-signed import and product entry;
  current source wins.
- The NOVA mock treats some unknown IDs as expired, while the real provider and
  frozen constants distinguish `DESTINATION_EXPIRED` from
  `DESTINATION_NOT_FOUND`.
- Older runtime diagrams emphasize one-shot pulls. The current Launcher also
  uses the observer flow documented in
  `13_LIVE_POSITION_AND_HEADING.md:39-73`.

## Compatibility gates for the new project

1. Reference `HyperNova_Contracts/contracts`; never copy its AIDL/parcelables.
2. Keep package, service FQCN, actions, API version, permission, status/error
   constants, result field order, token TTL, and request dedup behavior.
3. Return `ACCEPTED` for a valid new `setDestination` before Activity launch is
   required; final confirmation means preview-ready, not guidance-started.
4. Keep one application-scoped session and Google Navigator for UI, AIDL, and
   Launcher status.
5. Publish only real SDK state, metrics, positions, and route geometry.
6. Preserve signature permission and exported-component boundaries.
7. Do not modify the legacy project, contract module, NOVA, Launcher, or AAOS
   image to make the new standalone project compile.
