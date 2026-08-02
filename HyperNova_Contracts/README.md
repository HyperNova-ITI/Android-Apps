# HyperNova cross-app contracts

Status: **Demo API v1 with additive read-only Navigation status and route-preview extensions**

This Android library is the source of truth for NOVA command integration with HyperNova Navigation
and HyperNova Climate.

Any incompatible method, model, status, or semantic change requires API v2. Implementations may add
internal behavior without changing this ABI.

Implementation handoffs:

- [Ayman — Navigation service generation and implementation guide](docs/AYMAN_NAVIGATION_SERVICE_GUIDE.md)
- [Mahgoub — Climate service generation and TC397 implementation guide](docs/MAHGOUB_CLIMATE_SERVICE_GUIDE.md)

## Build

Use any repository Gradle 9.4.1 wrapper. Configure the Android SDK through `ANDROID_HOME`,
`ANDROID_SDK_ROOT`, or the developer's untracked `local.properties`; never commit a workstation SDK
path.

```bash
cd HyperNova_NOVA_AI_Task_02
./gradlew -p ../HyperNova_Contracts :contracts:assembleDebug
```

Output:

```text
HyperNova_Contracts/contracts/build/outputs/aar/contracts-debug.aar
```

## Consume the source module

NOVA's `settings.gradle.kts` uses a repository-relative path:

```kotlin
include(":hypernova-contracts")
project(":hypernova-contracts").projectDir =
    file("../HyperNova_Contracts/contracts")
```

Navigation's nested `HyperNovaNavigation/settings.gradle.kts` uses a repository-relative path:

```kotlin
include(":hypernova-contracts")
project(":hypernova-contracts").projectDir =
    file("../../HyperNova_Contracts/contracts")
```

Then add to the application module:

```kotlin
dependencies {
    implementation(project(":hypernova-contracts"))
}
```

Climate should use the equivalent relative project path when its Gradle project is created.

Do not copy the AIDL files into each APK. All participants compile against this module.

## Common rules

- API version is `1`.
- Every asynchronous request has a caller-generated, non-empty UUID `requestId`.
- A duplicate `requestId` must not repeat route creation or a TC397 actuation.
- Accepted and final results are cached for duplicate requests for at least ten minutes.
- A callback may send `STATUS_ACCEPTED`, then exactly one final status.
- Final statuses are `STATUS_CONFIRMED`, `STATUS_REJECTED`, `STATUS_UNAVAILABLE`,
  `STATUS_TIMEOUT`, and `STATUS_CANCELLED`.
- `STATUS_ACCEPTED` is never a successful outcome.
- A service returns `STATUS_CONFIRMED` only after its domain's real source of truth confirms the
  operation.
- Callbacks are one-way. Services must move map, network, database, and hardware work off Binder
  threads.
- Services are exported only behind the signature permission
  `com.hypernova.permission.CONTROL_COCKPIT_APPS`.

## Frozen Navigation demo

Service:

```text
Interface: com.hypernova.contracts.navigation.INavigationCommandService
Package: com.hypernova.navigation
Class: com.hypernova.navigation.service.NavigationCommandService
Bind action: com.hypernova.navigation.action.BIND_COMMAND
```

Exact methods:

```aidl
int getApiVersion();
void searchDestinations(String requestId, String query, INavigationCommandCallback callback);
void getSavedDestinations(String requestId, INavigationCommandCallback callback);
void setDestination(
    String requestId,
    String destinationId,
    INavigationCommandCallback callback
);
void cancelNavigation(String requestId, INavigationCommandCallback callback);
void getCurrentNavigationState(String requestId, INavigationCommandCallback callback);
void getCurrentNavigationRoutePreview(
    String requestId,
    INavigationRoutePreviewCallback callback
);
```

`getCurrentNavigationState` is read-only. It returns a single current
`NavigationResult` snapshot and never searches, calculates, activates, changes,
or cancels a route. `getCurrentNavigationRoutePreview` is a second read-only
query that returns a dedicated `NavigationRoutePreviewResult` through
`INavigationRoutePreviewCallback`. Its preview contains up to
`MAX_ROUTE_PREVIEW_POINTS` (128) authoritative OSRM route points and an optional
current session position.

Both methods are appended to the AIDL interface. The original
`NavigationResult` constructor and Parcel wire format are unchanged, preserving
existing NOVA command-client compatibility. The shared API version remains 1;
clients that use the new method still require a Navigation service that
implements the additive transaction.

### Demo journey A: voice search and selection

```text
Driver: "Find coffee shops near me"
Pi/NOVA: searchDestinations(req-1, "coffee shops near me")
Navigation: returns up to four ranked real NavigationDestination values
NOVA: "I found four. 1 …, 2 …, 3 …, or 4 …?"
Driver: "The second one"
Pi/NOVA: setDestination(req-2, result[1].id)
Navigation: calculates and activates the route
Navigation: STATUS_CONFIRMED with destination, ETA, and distance
NOVA: opens Navigation and says the confirmed route result
```

`searchDestinations` rules:

- Trim the query and reject blank input with `INVALID_ARGUMENT`.
- Return no more than `MAX_DESTINATION_RESULTS`, fixed at four.
- Preserve provider ranking.
- Use real provider results only.
- A successful empty search returns `STATUS_REJECTED/NO_RESULTS`.
- Each item contains an opaque ID, title, subtitle/address, category, source, and optional distance.
- Search destination IDs remain usable for at least ten minutes.
- Search timeout is ten seconds.

### Demo journey B: saved destination and selection

```text
Driver: "Show my saved destinations"
Pi/NOVA: getSavedDestinations(req-3)
Navigation: returns up to four real saved NavigationDestination values
NOVA: "You have Home, Work, and …"
Driver: "Take me home"
Pi/NOVA: setDestination(req-4, home.id)
Navigation: calculates and activates the route
Navigation: STATUS_CONFIRMED with destination, ETA, and distance
```

Saved result order is frozen:

1. Home, when configured.
2. Work, when configured.
3. Most recently used saved favorites until the four-result limit is reached.

Home and Work come from the Driver Profile owner. Other favorites are real destinations explicitly
saved by the user through Navigation. Missing entries are omitted; they are never faked. For a demo,
save real search results before presenting the journey.

No saved destinations returns `STATUS_REJECTED/NO_SAVED_DESTINATIONS`.

`setDestination` accepts only an opaque ID previously returned by search or saved destinations. It
must not accept arbitrary LLM text, coordinates invented by NOVA, or a list index. NOVA resolves
“the second one” to the second returned ID before calling AIDL.

It may return `STATUS_ACCEPTED` while calculating. It returns `STATUS_CONFIRMED` only when guidance
is active. The confirmed result includes the selected destination and real ETA/distance when
available.

Route calculation timeout is twenty seconds. Cancelling with no active route returns confirmed with
idle state because the requested end state is already true.

When `setDestination` becomes accepted, NOVA may open `com.hypernova.navigation.action.OPEN` so the
driver sees the calculating-route state followed by active guidance.

## Frozen Climate demo

Service:

```text
Interface: com.hypernova.contracts.climate.IClimateCommandService
Package: com.hypernova.climate
Class: com.hypernova.climate.service.ClimateCommandService
Bind action: com.hypernova.climate.action.BIND_COMMAND
```

Exact methods:

```aidl
int getApiVersion();
void getCapabilities(String requestId, IClimateCommandCallback callback);
void getCurrentState(String requestId, IClimateCommandCallback callback);
void setPowerEnabled(String requestId, boolean enabled, IClimateCommandCallback callback);
void setTargetTemperature(
    String requestId,
    int zone,
    float temperatureC,
    IClimateCommandCallback callback
);
void setFanLevel(String requestId, int fanLevel, IClimateCommandCallback callback);
void setAcEnabled(String requestId, boolean enabled, IClimateCommandCallback callback);
void setAutoModeEnabled(String requestId, boolean enabled, IClimateCommandCallback callback);
void setRecirculationEnabled(
    String requestId,
    boolean enabled,
    IClimateCommandCallback callback
);
```

Showcase commands:

| Driver phrase | AIDL call |
|---|---|
| “What is the climate set to?” | `getCurrentState` |
| “Set the climate to 22 degrees” | `setTargetTemperature(ZONE_ALL, 22.0f)` |
| “Set my side to 21 degrees” | `setTargetTemperature(ZONE_DRIVER, 21.0f)` |
| “Turn the climate on” | `setPowerEnabled(true)` |
| “Set the fan to level 3” | `setFanLevel(3)` |
| “Turn on the A/C” | `setAcEnabled(true)` |
| “Enable automatic climate” | `setAutoModeEnabled(true)` |
| “Turn on recirculation” | `setRecirculationEnabled(true)` |

The AI calls `getCapabilities` after binding and after backend reconnection. It must not advertise or
call unsupported controls, zones, fan levels, or temperature values.

Every mutation returns `STATUS_CONFIRMED` only after TC397 acknowledgement or authoritative property
readback. Binder delivery and vehicle-frame transmission are only accepted/in-progress states.
State/capability queries time out after two seconds; mutations time out after five seconds.

For Demo API v1, `ZONE_ALL` means every available cabin zone. If Climate is off,
`setTargetTemperature` turns it on as part of the same correlated high-level command and confirms
only after both power and the target value are authoritative. Any partial controller failure returns
a rejected or timeout result with the latest real state.

Defrost, airflow, zone sync, fresh air, and seat heating remain internal Climate features and are not
part of NOVA Demo API v1. Adding them to the AI contract requires API v2 or a backward-compatible v1
extension agreed by all owners.

## Result models

`NavigationResult` includes:

```text
requestId, operation, status, message, errorCode
destinations (zero to four)
selectedDestination
navigationState
etaSeconds
distanceMeters
```

`ClimateResult` includes:

```text
requestId, operation, status, message, errorCode
capabilities
confirmedState
```

Unavailable numeric state uses the documented sentinel (`Float.NaN` or `-1`). Do not replace
unknown values with demo numbers.

## Implementation ownership

- Ayman implements `INavigationCommandService.Stub` over the real navigation repository.
- Mahgoub implements `IClimateCommandService.Stub` over the climate repository and TC397 backend.
- NOVA binds to both services, translates Pi JSON messages into typed calls, correlates callbacks,
  and waits for a final status.
- Launcher reads app status separately; it does not invoke these mutation services.
