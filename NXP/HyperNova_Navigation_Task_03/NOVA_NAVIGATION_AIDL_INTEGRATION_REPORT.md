# HyperNova Navigation AIDL Integration Report

Date: 2026-07-31
Contract: Frozen Demo API v1

## Outcome

HyperNova Navigation now implements the server side of the shared
`INavigationCommandService` contract. The existing UI and the AIDL service
use the same application-scoped `NavigationRepository`, provider clients,
destination store, route generation gates, OSRM routing path, persisted
places, and authoritative navigation session.

No source file in `HyperNova_Contracts` required a semantic or build change.
Navigation consumes that module directly and Gradle generates the AIDL
Binder classes.

## Architecture

Before:

```text
MainActivity
  |
  +--> activity-owned NavigationRepository
  +--> activity-owned NavigationUiState
  +--> activity-owned route activation
```

After:

```text
NOVA
  |
  | AIDL
  v
NavigationCommandService
  |
  v
NavigationCommandController
  |
  v
Application-scoped NavigationRepository
  |
  +--> Nominatim / Overpass search providers
  |
  +--> DestinationStore
  |
  +--> NavigationPreferences
  |
  +--> OSRM
  |
  v
Authoritative NavigationSession
  |
  +--> MainActivity UI
  |
  +--> NavigationResult callback
```

`HyperNovaNavigationApplication` creates the one repository and preferences
instance. `MainActivity` no longer creates or closes a private repository.
It observes `NavigationSession` changes, so a route started or cancelled
through AIDL is rendered by the existing route screens and MapLibre map flow.

## AIDL flow

The exported service implements exactly:

- `getApiVersion()`
- `searchDestinations()`
- `getSavedDestinations()`
- `setDestination()`
- `cancelNavigation()`

`onBind()` returns the generated Binder only for
`NavigationContract.BIND_COMMAND_ACTION`.

Binder methods only validate lightweight arguments and enqueue work. Search,
preferences access, destination resolution, and route calculation run beyond
the Binder thread. Callback delivery catches dead-client `RemoteException`
and runtime callback failures.

Valid asynchronous commands emit `STATUS_ACCEPTED`, then one final result.
Invalid arguments are rejected asynchronously without starting backend work.

## Search behavior

- Text search delegates to the existing `NavigationRepository` and real
  Nominatim client.
- Provider order is preserved.
- Results are capped with
  `NavigationContract.MAX_DESTINATION_RESULTS` (four).
- Empty results return `STATUS_REJECTED/NO_RESULTS`.
- Provider failures use frozen contract status/error values.
- The service deadline uses
  `NavigationContract.SEARCH_TIMEOUT_MILLIS`.
- No natural-language intent parsing or category-specific command path was
  added.
- Existing generic Overpass nearby categories remain on their existing
  shared repository path.

## Destination IDs

`DestinationStore` owns all AIDL-visible IDs.

- Search results receive random opaque `nav-*` tokens.
- Search mappings retain the original `Place` and expire after
  `NavigationContract.SEARCH_RESULT_TTL_MILLIS`.
- Expired IDs return `DESTINATION_EXPIRED`.
- Never-issued or removed IDs return `DESTINATION_NOT_FOUND`.
- Saved IDs are opaque SHA-256-derived tokens based on the saved entry
  identity and source. They are stable across process restarts while the
  same saved entry exists.
- Raw coordinates, provider IDs, and list indexes are not exposed as AIDL
  identity.
- The store and mapping logic are category-independent.

## Saved destinations

The repository reads the existing real Navigation persistence in this order:

1. Home, when configured.
2. Work, when configured.
3. Real recent destinations.
4. Maximum four total.

Duplicates are removed by place identity. Missing Home/Work values are
omitted. Fresh installations no longer auto-create the legacy hardcoded demo
Home/Work values; existing user-persisted values are preserved.

## Destination setup and route preview

`setDestination()` resolves only a Navigation-issued ID, then delegates to
the shared repository and existing OSRM client.

The repository transitions the shared session through:

```text
IDLE -> CALCULATING -> ROUTE_PREVIEW
```

The AIDL result is `STATUS_CONFIRMED` only when the repository snapshot is
`ROUTE_PREVIEW`, contains the same destination token, and has a real route plan.
The result uses OSRM's real selected-route duration and distance for
`etaSeconds` and `distanceMeters`.

The driver starts guidance from the existing UI preview step:

```text
ROUTE_PREVIEW -> ACTIVE
```

Route-alternative selection is written back to the shared session. AIDL never starts trip
simulation as a side effect of setting the destination.

## Cancellation

`cancelNavigation()` cancels the current repository route generation and
transitions the same `NavigationSession` to `IDLE`. Confirmation is returned
only after the authoritative state is idle. Cancelling while already idle is
idempotently confirmed.

No separate AIDL-active flag exists.

## Request deduplication and deadlines

`RequestRegistry` uses the pair `(operation, requestId)` as request identity
and retains the argument fingerprint, accepted result, and final result.

- A duplicate in-flight command joins the existing request.
- A duplicate completed command receives the cached final result.
- Reusing the same operation/request ID with different arguments is rejected.
- Final results are retained for
  `HyperNovaContract.REQUEST_DEDUP_TTL_MILLIS`.
- Search and route work are not started twice for a duplicate.
- Scheduled search and route deadlines race atomically with normal
  completion, so only one final result wins.
- A route timeout cancels only the matching route generation and cannot
  cancel a newer route.

## Service security

The manifest:

- declares `com.hypernova.permission.CONTROL_COCKPIT_APPS` with
  `signature` protection;
- exports `NavigationCommandService` only behind that permission;
- registers the frozen bind action;
- keeps `MainActivity` as `singleTop`;
- does not declare `CATEGORY_HOME`.

## Files modified

- `HyperNovaNavigation/settings.gradle.kts`
- `HyperNovaNavigation/app/build.gradle.kts`
- `HyperNovaNavigation/app/src/main/AndroidManifest.xml`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/MainActivity.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/data/persistence/NavigationPreferences.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/domain/model/NavigationModels.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/domain/repository/NavigationRepository.kt`

## Files created

- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/HyperNovaNavigationApplication.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/domain/model/NavigationBackendModels.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/domain/repository/DestinationSearchPolicy.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/domain/repository/DestinationStore.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/domain/repository/NavigationSession.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/domain/repository/SavedDestinationSelector.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/service/NavigationCommandController.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/service/NavigationCommandPolicies.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/service/NavigationCommandService.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/service/NavigationResultFactory.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/service/RequestRegistry.kt`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/CommandTimeoutPolicyTest.kt`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/DestinationSearchPolicyTest.kt`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/DestinationStoreTest.kt`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/NavigationResultFactoryTest.kt`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/NavigationServiceManifestTest.kt`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/NavigationSessionTest.kt`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/RequestRegistryTest.kt`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/SavedDestinationSelectorTest.kt`
- `NOVA_NAVIGATION_AIDL_INTEGRATION_REPORT.md`

## Tests

Focused coverage was added for:

- blank, zero, one, and more-than-four search results;
- provider order preservation;
- opaque ID issuance, lookup, expiry, and unknown IDs;
- category-independent destination mapping;
- stable saved IDs and invalidation after saved-entry removal;
- missing Home and Work;
- Home/Work/recent ordering and maximum count;
- duplicate search and route request IDs;
- conflicting request-ID reuse;
- final-result cache duration;
- route confirmation only while authoritative state is active;
- cancellation while active and already idle;
- frozen timeout and result status mapping;
- manifest action, signature permission, and absence of `CATEGORY_HOME`.

Validation result:

```text
./gradlew :app:testDebugUnitTest
BUILD SUCCESSFUL
57 tests, 0 failures, 0 errors
```

## Build result

```text
./gradlew :hypernova-contracts:assembleDebug :app:assembleDebug
BUILD SUCCESSFUL
```

Generated outputs:

- `HyperNova_Contracts/contracts/build/outputs/aar/hypernova-contracts-debug.aar`
- `HyperNovaNavigation/app/build/outputs/apk/debug/app-debug.apk`

The merged Navigation manifest was also inspected and contains the
application owner, protected service, and frozen bind action.

## Known limitations

- Navigation still uses the existing fixed ITI origin because the current app
  has no live vehicle/GNSS location source. AIDL deliberately reuses that same
  route path instead of introducing a second origin model.
- Search token mappings are held for the application process lifetime. A
  process restart requires the client to repeat search; saved IDs remain
  deterministic across restarts.
- Recents are the current app's only persisted non-Home/Work saved-place
  collection; there is no separate favorites database yet.
- Network-provider behavior and cross-process Binder calls require device or
  emulator validation; local unit tests do not call public Nominatim, Overpass,
  or OSRM endpoints.
- Consuming the frozen shared module raises Navigation's minimum SDK from 31
  to the contracts module's agreed API 35 baseline.

## OUT OF SCOPE — work for the NOVA developer

NOVA must eventually:

- request `com.hypernova.permission.CONTROL_COCKPIT_APPS` and be signed with
  the trusted HyperNova certificate;
- bind explicitly with the frozen action, Navigation package, and service
  class;
- generate non-empty unique request IDs and handle `ACCEPTED` plus the final
  callback;
- retain returned opaque destination IDs and choose the intended ID for
  contextual phrases such as "closest" or "the second one";
- handle Binder death, reconnect, retry, timeout, rejected, and unavailable
  results;
- optionally open `NavigationContract.OPEN_ACTION` after route acceptance so
  the driver can see calculation and active guidance.

No NOVA source, Gradle file, client binding, NLP logic, prompt, adapter, or
command router was inspected or modified as part of this work.
