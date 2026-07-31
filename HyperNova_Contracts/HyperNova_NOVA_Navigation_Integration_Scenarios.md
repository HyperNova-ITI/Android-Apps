# HyperNova NOVA AI ↔ Navigation Integration Scenarios

## Purpose

This document defines how **NOVA / the AI Agent** and **HyperNova Navigation** should work together.

The integration is generic. It must work for fuel, hospitals, food, parking, shopping, named destinations, saved destinations, and future categories without creating separate AIDL flows for every place type.

## 1. Responsibility split

NOVA / AI Agent owns:

```text
Natural-language understanding
Conversation context
Follow-up understanding
Reference resolution
Intent selection
Tool selection
User-facing conversational responses
```

Examples NOVA should understand:

```text
"Find the nearest gas station."
"Find hospitals nearby."
"Find somewhere to eat."
"Take me to the closest one."
"Take me to the second one."
"Go there."
"How far is it?"
"Cancel it."
```

Navigation owns:

```text
Destination search
Nearby-place backend
Saved destinations
Opaque destination IDs
Route calculation
OSRM
ETA
Distance
Navigation state
Cancellation
Navigation UI
```

Navigation must not become an NLP engine.

## 2. Final architecture

```text
Driver
  |
  | Natural language
  v
AI Agent
  |
  | Intent + entities + context
  v
NOVA Android
  |
  | AIDL
  v
NavigationCommandService
  |
  v
NavigationCommandController
  |
  v
NavigationRepository
  |
  +--> Nominatim
  +--> Overpass
  +--> DestinationStore
  +--> Saved destinations
  +--> OSRM
  |
  v
NavigationSession
  |
  +--> MainActivity UI
  |
  +--> NavigationResult callback
          |
          v
        NOVA
```

## 3. Shared contract

Source of truth:

```text
HyperNova_Contracts/contracts
```

Navigation API:

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

Callback:

```aidl
oneway interface INavigationCommandCallback {
    void onResult(in NavigationResult result);
}
```

Current API:

```text
Frozen Demo API v1
API_VERSION = 1
```

## 4. Binding information

```text
Package:
com.hypernova.navigation

Service:
com.hypernova.navigation.service.NavigationCommandService

Bind action:
com.hypernova.navigation.action.BIND_COMMAND

Permission:
com.hypernova.permission.CONTROL_COCKPIT_APPS
```

The permission uses signature protection.

## 5. Generic destination principle

Navigation should think in terms of:

```text
Place
Destination
SavedDestination
Route
ActiveNavigation
```

not:

```text
HospitalFlow
FuelFlow
FoodFlow
```

Current Navigation categories include:

```text
Parking
Fuel
Food
Hospital
Shopping
```

These are data/categories handled by generic search and route infrastructure.

## 6. Opaque destination IDs

Navigation owns destination identity.

Example result:

```text
Title: F22
ID: nav-abc123
```

NOVA must preserve the returned ID and send it back when the user selects that destination:

```text
setDestination("nav-abc123")
```

NOVA must not replace that ID with:

```text
list index
raw coordinates
provider ID
invented ID
```

## Scenario 1 — Named destination search

User:

```text
"Find Valeo."
```

NOVA:

```text
Intent = destination search
Query  = "Valeo"
```

AIDL:

```text
searchDestinations(requestId, "Valeo", callback)
```

Navigation returns real results, for example:

```text
1. F22
   id = nav-A

2. Valeo Smart Village
   id = nav-B
```

NOVA presents the results to the user.

## Scenario 2 — Select by number

Previous results:

```text
1. F22                  -> nav-A
2. Valeo Smart Village  -> nav-B
```

User:

```text
"Take me to the second one."
```

NOVA resolves:

```text
second one
    |
    v
nav-B
```

Then calls:

```text
setDestination(requestId, "nav-B", callback)
```

Navigation does not interpret the phrase `second one`.

## Scenario 3 — Closest result follow-up

User:

```text
"Find nearby gas stations."
```

NOVA stores the returned candidates and their distances, for example:

```text
A -> nav-A -> 1.2 km
B -> nav-B -> 2.5 km
C -> nav-C -> 4.0 km
```

User:

```text
"Take me to the closest one."
```

NOVA resolves:

```text
closest one
    |
    v
nav-A
```

Then calls:

```text
setDestination(nav-A)
```

The same logic should work for fuel, hospitals, food, parking, shopping, and any other destination category.

## Scenario 4 — Hospital follow-up

User:

```text
"Find hospitals nearby."
```

NOVA stores the returned choices.

User:

```text
"Take me to the closest one."
```

NOVA chooses the matching Navigation-issued destination ID and sends only that ID to Navigation.

## Scenario 5 — Food follow-up

User:

```text
"Find somewhere to eat nearby."
```

AI interprets the place intent.

Navigation returns candidate destinations.

User:

```text
"Take me to the first one."
```

NOVA maps the first result to its opaque destination ID and calls `setDestination()`.

## Scenario 6 — Saved Home

User:

```text
"Take me home."
```

NOVA may call:

```text
getSavedDestinations()
```

Navigation returns only real configured saved destinations.

Expected priority:

```text
Home
Work
Recent/Favorite
```

Maximum four.

If Home is not configured, NOVA must not invent a Home address.

## Scenario 7 — Saved Work

User:

```text
"Navigate to work."
```

If Work exists, NOVA uses its Navigation-issued destination ID and calls `setDestination()`.

If it does not exist, NOVA should tell the user it is not configured.

## Scenario 8 — Route start

NOVA calls:

```text
setDestination(requestId, destinationId, callback)
```

Expected progression:

```text
STATUS_ACCEPTED
STATE_CALCULATING
```

then:

```text
STATUS_CONFIRMED
STATE_ACTIVE
```

NOVA should only tell the user that navigation has started after receiving:

```text
STATUS_CONFIRMED
+
STATE_ACTIVE
```

## Scenario 9 — Ask about current route distance

Navigation may return:

```text
selectedDestination = F22
distanceMeters = 1400
etaSeconds = 240
navigationState = ACTIVE
```

User:

```text
"How far is it?"
```

`it` refers to the current active destination/route.

NOVA should answer from Navigation data, for example:

```text
"About 1.4 kilometers."
```

NOVA must not invent the distance.

## Scenario 10 — Ask about ETA

User:

```text
"How long will it take?"
```

NOVA uses the current route context.

Example:

```text
etaSeconds = 240
```

NOVA may answer:

```text
"About 4 minutes."
```

## Scenario 11 — Cancel follow-up

While Navigation is active:

```text
User:
"Cancel it."
```

NOVA resolves:

```text
it = current active navigation
```

Then:

```text
cancelNavigation(requestId, callback)
```

Expected final state:

```text
STATUS_CONFIRMED
STATE_IDLE
```

## Scenario 12 — Cancel while already idle

User:

```text
"Cancel navigation."
```

If Navigation is already idle, the desired state is already satisfied.

The operation should remain idempotent.

## Scenario 13 — Follow-up with "there"

User:

```text
"Find Valeo."
```

NOVA presents results.

User:

```text
"Go there."
```

NOVA must resolve what `there` refers to using conversation context.

Navigation receives only the final destination ID.

## Scenario 14 — Expired destination

An old ID returns:

```text
DESTINATION_EXPIRED
```

NOVA should repeat destination search.

It must not fabricate or reconstruct the expired ID.

## Scenario 15 — No results

Navigation returns:

```text
NO_RESULTS
```

NOVA should tell the user that no real destination was found.

It must not create fake places.

## Scenario 16 — Route failure

Possible errors include:

```text
ROUTE_NOT_FOUND
LOCATION_UNAVAILABLE
OFFLINE_DATA_UNAVAILABLE
TIMEOUT
SERVICE_UNAVAILABLE
INTERNAL_ERROR
```

NOVA converts the real result into user-facing wording.

Example:

```text
ROUTE_NOT_FOUND
```

Possible response:

```text
"I found the place, but I couldn't calculate a route to it."
```

The wording belongs to NOVA. The truth comes from Navigation.

## 7. Conversation context NOVA should maintain

Conceptually:

```text
NavigationConversationContext
    |
    +--> lastSearchRequestId
    +--> lastSearchQuery
    +--> lastSearchResults
    +--> selectedDestinationId
    +--> activeDestination
    +--> navigationState
    +--> latestEtaSeconds
    +--> latestDistanceMeters
```

This enables follow-ups such as:

```text
"the first one"
"the second one"
"the closest one"
"there"
"it"
```

## 8. requestId rules

Each new logical request should use a unique, non-empty `requestId`.

Example:

```text
search-<uuid>
route-<uuid>
cancel-<uuid>
```

If the same operation/requestId is sent again, Navigation may treat it as a retry of the same logical request.

Do not reuse a requestId for different arguments or a different command.

## 9. Callback rules

AIDL commands are asynchronous.

Typical flow:

```text
NOVA
  |
  | searchDestinations(...)
  v
Navigation

Navigation
  |
  | ACCEPTED
  v
NOVA

Navigation performs work

Navigation
  |
  | CONFIRMED / REJECTED / TIMEOUT
  v
NOVA
```

NOVA should correlate callbacks using:

```text
requestId
operation
```

## 10. Important statuses

```text
STATUS_ACCEPTED
STATUS_CONFIRMED
STATUS_REJECTED
STATUS_UNAVAILABLE
STATUS_TIMEOUT
STATUS_CANCELLED
```

For route start:

```text
ACCEPTED
```

means the request was accepted, not that the route is already active.

Final success requires:

```text
CONFIRMED + ACTIVE
```

## 11. Navigation states

```text
STATE_IDLE
STATE_CALCULATING
STATE_ACTIVE
STATE_ARRIVED
STATE_ERROR
```

Typical route start:

```text
IDLE
  |
  v
CALCULATING
  |
  v
ACTIVE
```

Cancellation:

```text
ACTIVE
  |
  v
IDLE
```

## 12. Frozen API v1 limitation

Current v1 exposes:

```text
searchDestinations(requestId, query, callback)
```

It does not expose structured parameters such as:

```text
category
radius
sortBy
```

Navigation already has generic nearby-category infrastructure internally, but neither developer should silently redesign the frozen v1 contract.

A future contract version can expose structured nearby search while reusing the same backend.

## 13. What NOVA must not do

NOVA must not bypass Navigation by calling:

```text
OSRM directly
Nominatim directly
Navigation internal providers directly
```

NOVA must not invent:

```text
destination IDs
ETA
distance
route success
saved places
```

NOVA must not claim navigation started before:

```text
STATUS_CONFIRMED
STATE_ACTIVE
```

## 14. What Navigation must not do

Navigation must not implement NLP rules such as:

```text
if query contains "closest"
if query contains "hospital"
if query contains "gas station"
if query contains "there"
```

Conversation interpretation belongs to NOVA.

## 15. Full demo conversation

```text
Driver:
"Find nearby gas stations."

        ↓

AI Agent:
understands the nearby-place intent

        ↓

NOVA:
requests Navigation search
stores returned results + IDs

        ↓

Navigation:
returns real destinations

        ↓

Driver:
"Take me to the closest one."

        ↓

NOVA:
uses previous results
resolves "closest one" -> nav-A

        ↓

NOVA:
setDestination(nav-A)

        ↓

Navigation:
calculates route
STATE_ACTIVE
real ETA + distance

        ↓

NOVA:
"Starting navigation to <destination>.
It's about 4 minutes away."

        ↓

Driver:
"How far is it?"

        ↓

NOVA:
uses active route context

        ↓

"About 1.4 kilometers."

        ↓

Driver:
"Cancel it."

        ↓

NOVA:
cancelNavigation()

        ↓

Navigation:
STATE_IDLE
```

This demonstrates:

```text
AI understanding
+
conversation memory
+
reference resolution
+
AIDL tool invocation
+
real Navigation execution
+
grounded response
```

## 16. Integration success criteria

```text
Search request
    |
    v
Real Navigation results
    |
    v
NOVA retains opaque IDs
    |
    v
Follow-up reference resolved by AI
    |
    v
setDestination(returned ID)
    |
    v
Navigation confirms ACTIVE
    |
    v
NOVA uses real ETA/distance
    |
    v
Follow-up cancel
    |
    v
Navigation returns IDLE
```

The same architecture should work for:

```text
Fuel
Hospital
Food
Parking
Shopping
Named destinations
Saved destinations
Future destination categories
```
