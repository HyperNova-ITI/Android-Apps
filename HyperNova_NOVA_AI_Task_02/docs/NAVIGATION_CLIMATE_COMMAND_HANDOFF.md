# NOVA command integration handoff: Navigation and Climate

Status: **FROZEN — Demo API v1**

Owners: Ayman (Navigation), Mahgoub (Climate), Mostafa/NOVA (Android broker and Pi agent)

Target: Android 16/API 36 on the NXP i.MX 8QM guest; Android 16/API 36 emulator for laptop
integration.

The compiled source of truth is
[HyperNova_Contracts](../../HyperNova_Contracts/README.md). Any incompatible change requires API v2
and agreement from all three application owners.

## 1. Ownership

```text
Pi voice agent
  → understands the driver and selects a typed operation
  → NOVA Android command broker
  → Navigation app OR Climate app
  → real domain confirmation
  → NOVA Android
  → Pi final response and TTS
```

| Component | Responsibility |
|---|---|
| Pi agent | Wake word, ASR, conversation context, intent/arguments, initial safety policy, and final response |
| NOVA Android | JSON/AIDL adapter, service binding, request correlation, timeouts, and honest UI state |
| Navigation | Search, saved places, opaque destination IDs, route calculation, active guidance, and navigation state |
| Climate | Capabilities, HVAC state, command validation, TC397 transport, and hardware confirmation |
| TC397 | Physical actuation, rejection, constraints, and final physical safety authority |
| Launcher | Open apps and read status snapshots; never route AI commands or contact TC397 |

NOVA never contacts TC397 directly. Climate is the only owner of that path.

## 2. Common result rules

- Contract API version is `1`.
- Every call carries a caller-generated UUID `requestId`.
- `requestId` passes unchanged through Pi JSON, NOVA Android, AIDL, and the final response.
- A callback can send `accepted`, then exactly one final result.
- Final states are `confirmed`, `rejected`, `unavailable`, `timeout`, or `cancelled`.
- `accepted` means only “work started.” It never means success.
- Duplicate `requestId` values must return cached progress/final state without repeating route
  creation or a TC397 command.
- Services cache accepted/final results for duplicate requests for at least ten minutes.
- Services move map, network, database, and hardware work off Binder threads.
- Only the destination app composes domain data. NOVA never fabricates a destination, ETA,
  temperature, or hardware result.

Shared signature permission:

```text
com.hypernova.permission.CONTROL_COCKPIT_APPS
```

## 3. Frozen Navigation showcase

Service identity:

```text
Package: com.hypernova.navigation
Open action: com.hypernova.navigation.action.OPEN
Service: com.hypernova.navigation.service.NavigationCommandService
Bind action: com.hypernova.navigation.action.BIND_COMMAND
AIDL: com.hypernova.contracts.navigation.INavigationCommandService
```

Exact AIDL:

```aidl
interface INavigationCommandService {
    int getApiVersion();

    void searchDestinations(
        String requestId,
        String query,
        INavigationCommandCallback callback
    );

    void getSavedDestinations(
        String requestId,
        INavigationCommandCallback callback
    );

    void setDestination(
        String requestId,
        String destinationId,
        INavigationCommandCallback callback
    );

    void cancelNavigation(
        String requestId,
        INavigationCommandCallback callback
    );
}
```

The callback has one method:

```aidl
oneway interface INavigationCommandCallback {
    void onResult(in NavigationResult result);
}
```

### Scenario A: search, offer four choices, select one

```text
Driver: "Find coffee shops near me"
Pi → Android: search_destinations("coffee shops near me")
Android → Navigation: searchDestinations(...)
Navigation → callback: up to four ranked real results
NOVA: "I found four: 1 …, 2 …, 3 …, or 4 …?"
Driver: "Take me to the second one"
Pi resolves "second" to result[1].id
Pi → Android: set_destination(destination_id)
Android → Navigation: setDestination(...)
Navigation calculates and starts guidance
Navigation → callback: confirmed + destination + ETA + distance
NOVA opens Navigation and speaks the confirmed route
```

Search rules:

- Blank query → `rejected/INVALID_ARGUMENT`.
- Return zero to four results. Four is the fixed maximum.
- Preserve real search-provider ranking.
- No results → `rejected/NO_RESULTS`.
- Every result contains:
  - opaque `id`;
  - `source`;
  - driver-facing `title`;
  - address/description `subtitle`;
  - optional `category`;
  - optional distance in meters.
- Search IDs remain valid for at least ten minutes.
- Search timeout is ten seconds.
- NOVA presents results in returned order. “First/second/third/fourth” maps to array indices 0–3.

### Scenario B: list saved destinations, then select one

```text
Driver: "Show my saved destinations"
Pi → Android: get_saved_destinations
Android → Navigation: getSavedDestinations(...)
Navigation → callback: up to four real saved destinations
NOVA: "You have Home, Work, and …"
Driver: "Take me home"
Pi selects the returned Home ID
Pi → Android: set_destination(home_id)
Navigation starts the route and confirms it
```

Frozen saved ordering:

1. Home, if configured.
2. Work, if configured.
3. Most recently used saved favorites until the four-result limit is reached.

Home and Work come from Driver Profile. Other favorites are real destinations explicitly saved
through Navigation. Missing saved places are omitted and never faked. Save real search results before
the demo rather than adding hardcoded demo addresses.

No saved places returns `rejected/NO_SAVED_DESTINATIONS`.

### `setDestination` rule

`setDestination` accepts only an opaque ID returned by `searchDestinations` or
`getSavedDestinations`. It does not accept raw LLM text, coordinates invented by NOVA, or a spoken
list index.

The service can return `accepted` during route calculation. It returns `confirmed` only when active
guidance has started. The confirmed `NavigationResult` includes:

```text
selectedDestination
navigationState = ACTIVE
real etaSeconds, or -1 if unavailable
real distanceMeters, or -1 if unavailable
```

Route calculation timeout is twenty seconds. Cancelling while already idle returns confirmed with
idle state.

When the call becomes accepted, NOVA may launch `com.hypernova.navigation.action.OPEN` so the
calculating-route and active-guidance screens form part of the demo.

### Navigation failure behavior

| Situation | Final result |
|---|---|
| No search result | `rejected/NO_RESULTS` |
| No saved destinations | `rejected/NO_SAVED_DESTINATIONS` |
| Unknown or expired ID | `rejected/DESTINATION_EXPIRED` |
| GPS unavailable | `unavailable/LOCATION_UNAVAILABLE` |
| Route cannot be calculated | `rejected/ROUTE_NOT_FOUND` |
| Required offline data absent | `unavailable/OFFLINE_DATA_UNAVAILABLE` |
| Route calculation exceeds timeout | `timeout/TIMEOUT` |

### Ayman's definition of done

- Implements the frozen AIDL from `HyperNova_Contracts`; no private copy.
- Search returns at most four real ranked results.
- Saved results use the frozen ordering.
- Result IDs can be used by `setDestination`.
- A duplicate request cannot create a duplicate route.
- Confirmed is sent only after route state is active.
- Search, saved, set, cancel, no-results, expired-ID, GPS-off, and service-rebind tests pass.
- App UI and callback result come from the same navigation repository.

## 4. Frozen Climate showcase

Service identity:

```text
Package: com.hypernova.climate
Open action: com.hypernova.climate.action.OPEN
Service: com.hypernova.climate.service.ClimateCommandService
Bind action: com.hypernova.climate.action.BIND_COMMAND
AIDL: com.hypernova.contracts.climate.IClimateCommandService
```

Exact AIDL:

```aidl
interface IClimateCommandService {
    int getApiVersion();

    void getCapabilities(
        String requestId,
        IClimateCommandCallback callback
    );

    void getCurrentState(
        String requestId,
        IClimateCommandCallback callback
    );

    void setPowerEnabled(
        String requestId,
        boolean enabled,
        IClimateCommandCallback callback
    );

    void setTargetTemperature(
        String requestId,
        int zone,
        float temperatureC,
        IClimateCommandCallback callback
    );

    void setFanLevel(
        String requestId,
        int fanLevel,
        IClimateCommandCallback callback
    );

    void setAcEnabled(
        String requestId,
        boolean enabled,
        IClimateCommandCallback callback
    );

    void setAutoModeEnabled(
        String requestId,
        boolean enabled,
        IClimateCommandCallback callback
    );

    void setRecirculationEnabled(
        String requestId,
        boolean enabled,
        IClimateCommandCallback callback
    );
}
```

The callback has one method:

```aidl
oneway interface IClimateCommandCallback {
    void onResult(in ClimateResult result);
}
```

### Frozen demo phrases

| Driver phrase | Typed call |
|---|---|
| “What is the climate set to?” | `getCurrentState` |
| “Set the climate to 22 degrees” | `setTargetTemperature(ZONE_ALL, 22.0)` |
| “Set my side to 21 degrees” | `setTargetTemperature(ZONE_DRIVER, 21.0)` |
| “Turn the climate on/off” | `setPowerEnabled(true/false)` |
| “Set the fan to level 3” | `setFanLevel(3)` |
| “Turn the A/C on/off” | `setAcEnabled(true/false)` |
| “Enable/disable automatic climate” | `setAutoModeEnabled(true/false)` |
| “Enable/disable recirculation” | `setRecirculationEnabled(true/false)` |

NOVA calls `getCapabilities` after binding and after backend reconnection. It does not advertise or
call unsupported controls, zones, fan levels, or temperature values.

Climate validates:

- the operation is supported;
- requested zone is supported;
- temperature is within the advertised min/max and aligned to the step;
- fan level is within `0..maximumFanLevel`;
- the vehicle backend is connected.

Every mutating operation becomes `confirmed` only after TC397 acknowledgement or authoritative
property readback matching the requested value.

State and capability queries time out after two seconds. Mutations time out after five seconds.
`ZONE_ALL` means every available cabin zone. If climate power is off, `setTargetTemperature` turns
it on as part of the same high-level command and confirms only after both power and target values are
authoritative.

### Climate v1 exclusions

These can remain inside Mahgoub's app but are not part of the NOVA Demo API v1:

```text
airflow direction
fresh air
zone sync
front/rear/max defrost
driver/passenger seat heating
```

They should not delay the frozen showcase. Adding them to NOVA later requires a compatible contract
extension or API v2.

### Climate failure behavior

| Situation | Final result |
|---|---|
| Unsupported operation/property | `rejected/UNSUPPORTED_OPERATION` |
| Unsupported zone | `rejected/UNSUPPORTED_ZONE` |
| Value outside capability range | `rejected/OUT_OF_RANGE` |
| TC397 explicitly rejects | `rejected/HARDWARE_REJECTED` |
| Backend disconnected | `unavailable/SERVICE_UNAVAILABLE` |
| Confirmation not received in time | `timeout/TIMEOUT` |

### Mahgoub's definition of done

- Implements the frozen AIDL from `HyperNova_Contracts`; no private copy.
- Implements state and capabilities from the real climate repository.
- Maps every advertised v1 mutation to the TC397 layer.
- Deduplicates `requestId` before transmitting.
- Returns confirmed only after controller ACK/readback.
- Demonstrates positive, rejected, timeout, unsupported, duplicate, and disconnected cases.
- App UI, Launcher snapshot, and NOVA callback share one authoritative climate state.

## 5. Pi JSON mapping

The existing TCP 8765 JSON Lines channel carries `command_request` and `command_result`.

### Navigation search

```json
{
  "type": "command_request",
  "v": 1,
  "seq": 42,
  "turn_id": "turn-101",
  "request_id": "req-101",
  "domain": "navigation",
  "operation": "search_destinations",
  "args": {
    "query": "coffee shops near me"
  }
}
```

Confirmed search result:

```json
{
  "type": "command_result",
  "v": 1,
  "seq": 18,
  "turn_id": "turn-101",
  "request_id": "req-101",
  "domain": "navigation",
  "operation": "search_destinations",
  "status": "confirmed",
  "message": "I found four destinations",
  "data": {
    "destinations": [
      {
        "id": "opaque-nav-id-1",
        "title": "Coffee Lab",
        "subtitle": "Example address",
        "category": "Coffee shop",
        "distance_meters": 1800
      }
    ]
  }
}
```

The array contains at most four entries. The example shows one entry only to keep the protocol
sample short.

### Saved destinations

```json
{
  "type": "command_request",
  "v": 1,
  "seq": 43,
  "turn_id": "turn-102",
  "request_id": "req-102",
  "domain": "navigation",
  "operation": "get_saved_destinations",
  "args": {}
}
```

### Set destination

```json
{
  "type": "command_request",
  "v": 1,
  "seq": 44,
  "turn_id": "turn-103",
  "request_id": "req-103",
  "domain": "navigation",
  "operation": "set_destination",
  "args": {
    "destination_id": "opaque-nav-id-1"
  }
}
```

### Climate temperature

```json
{
  "type": "command_request",
  "v": 1,
  "seq": 45,
  "turn_id": "turn-104",
  "request_id": "req-104",
  "domain": "climate",
  "operation": "set_temperature",
  "args": {
    "zone": "all",
    "temperature_c": 22.0
  }
}
```

Confirmed climate result:

```json
{
  "type": "command_result",
  "v": 1,
  "seq": 19,
  "turn_id": "turn-104",
  "request_id": "req-104",
  "domain": "climate",
  "operation": "set_temperature",
  "status": "confirmed",
  "message": "Climate set to 22°C",
  "data": {
    "confirmed_state": {
      "power_enabled": true,
      "driver_temperature_c": 22.0,
      "fan_level": 3,
      "ac_enabled": true,
      "auto_enabled": false,
      "recirculation_enabled": false
    }
  }
}
```

## 6. NOVA implementation

NOVA Android will:

1. Consume the `HyperNova_Contracts` module.
2. Receive and validate `command_request`.
3. Bind to the exact service for the domain.
4. Translate normalized JSON arguments into the typed AIDL call.
5. Track `requestId`, `turn_id`, service death, timeout, and duplicate callbacks.
6. Translate AIDL results into `command_result`.
7. Keep the UI in executing until a final result.
8. Open Navigation when `setDestination` is accepted.
9. Return the final result to the Pi.

The Pi agent will:

1. Replace integrated-domain `MockIVI` success with Android command requests.
2. Retain the four returned navigation choices in conversation context.
3. Resolve “the second one” into an opaque returned ID.
4. Wait for the final result before composing and speaking success.
5. Ask for clarification when a selection does not match the current result set.

## 7. Android manifest

Navigation:

```xml
<service
    android:name=".service.NavigationCommandService"
    android:exported="true"
    android:permission="com.hypernova.permission.CONTROL_COCKPIT_APPS">
    <intent-filter>
        <action android:name="com.hypernova.navigation.action.BIND_COMMAND" />
    </intent-filter>
</service>
```

Climate uses `.service.ClimateCommandService` and
`com.hypernova.climate.action.BIND_COMMAND`.

NOVA declares package/action visibility for both destinations and requests the control permission.
The permission is signature-level; production APKs must use the approved common signing key.

## 8. Android version

```text
compileSdk = 36
targetSdk = 36
minSdk = 35
```

Android 15/API 35 can remain a secondary compatibility test. Frozen-contract acceptance happens on
the Android 16/API 36 `HyperNova_API_36` emulator and later the Android 16 ARM64 NXP guest.

The AIDL contains no Trout-, emulator-, hypervisor-, map-provider-, or TC397-transport-specific API.

## 9. Remaining hardware configuration

The app contract is frozen. Mahgoub and the vehicle team still provide runtime configuration:

- which frozen Climate v1 operations the first TC397 firmware supports;
- real min/max/step and maximum fan level;
- payload IDs and values inside the Climate backend;
- ACK, rejection, and readback mapping;
- confirmed hardware timeout.

Unsupported capabilities must be reported honestly. They do not change the AIDL.

## 10. First integrated demo definition of done

- Shared contracts AAR builds and all three apps use it.
- “Find coffee near me” returns up to four real results.
- “The second one” starts the selected real route.
- “Show saved destinations” returns real Home/Work/favorites in frozen order.
- Navigation opens and displays calculating followed by active guidance.
- At least one supported Climate mutation reaches TC397 and is confirmed.
- Climate rejection and timeout paths are demonstrated.
- Duplicate requests do not duplicate route or vehicle actions.
- No component reports success on Binder delivery or controller transmission.
- NOVA UI and speech use the final confirmed/rejected result.
- No driver-facing UI exposes Pi, Android, NXP, network, or audio topology.
