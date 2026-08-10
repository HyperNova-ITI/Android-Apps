# HyperNova Navigation — implementation changes

This file documents the work completed on the existing HyperNova Navigation application during the standalone-navigation implementation pass. It is intentionally separate from the product-focused [README.md](README.md).

## Category reliability and product cleanup — 2026-07-23

This focused pass corrected both provider-side and application-side causes
behind category taps that appeared intermittent.

Application-side findings and fixes:

- category buttons had been disabled during `SEARCHING`, so repeat taps and
  attempts to switch categories never reached their listeners;
- an interrupted executor path could finish without a terminal callback;
- any `FailureKind.NETWORK` was presented as full-device Offline even when
  Android connectivity remained validated and only an Overpass endpoint was
  unavailable;
- all search work shared one generation/future, obscuring text/category
  ownership;
- provider errors were retried too broadly, including permanent errors;
- a wider-radius provider failure retained real data internally but gave the
  user no partial-result explanation.

The corrected lifecycle uses independent text-search, category-search, and
route generation gates. Category controls remain enabled during loading.
Repeating the active category does not create a parallel request; a different
category cancels or supersedes the older generation, whose callbacks are
ignored. Back and Cancel always leave a safe rendered state.

Overpass now follows this bounded strategy:

```text
primary attempt 1
  -> retryable failure + short backoff
primary attempt 2
  -> retryable failure
fallback attempt 1
  -> success or typed provider failure
```

Only connection failure, timeout, HTTP 429, and retryable HTTP 5xx responses
advance the strategy. Malformed data and permanent 4xx responses are not
retried. Logs include generation, category, radius, safe endpoint label,
attempt, response code, elapsed time, parsed count, deduplicated count, cache
decision, cancellation, fallback, and terminal outcome without raw response
bodies.

The final visible category list is:

```text
Parking
Fuel
Food
Hospital
Shopping
```

Charging was removed from the domain enum, Overpass mapping, Home and Search
layouts, activity dispatch, strings, tests, accessibility resources, and
vector resources. The Home shortcut now uses Fuel and immediately starts the
real Overpass Fuel flow.

All app UI now uses `ITI START`, `START: ITI SMART VILLAGE`, or route-preview
wording. It never claims GPS readiness or current vehicle movement.

## Focused provider/default/theme correction — 2026-07-23

This section records the latest correction pass. The longer sections below
retain the complete standalone-application implementation history.

### Root cause corrected

The six category buttons previously constructed strings such as:

```text
food near Smart Village Egypt
shopping near Smart Village Egypt
gas station near Smart Village Egypt
```

Those strings were sent to Nominatim. Nominatim is a geocoder for named
places and addresses; it is not a complete “return all POIs around these
coordinates” category service. Valid queries could therefore return no
nearby results even when OpenStreetMap contained matching POIs.

The final provider split is explicit:

```text
free text/address -> NominatimClient -> real place coordinates
category button   -> OverpassClient  -> real nearby OSM POIs
selected place    -> OsrmClient      -> real driving route and steps
```

No category button creates a Nominatim text query now.

### Overpass implementation

Created:

```text
data/overpass/OverpassClient.kt
domain/repository/NearbySearchPolicy.kt
```

`OverpassClient`:

- sends POST requests with the query in the `data` form field;
- identifies HyperNova through its User-Agent;
- has finite 7-second connection and 16-second read timeouts;
- uses `https://overpass-api.de/api/interpreter`;
- retries `https://overpass-api.de/api/interpreter` once after a bounded
  backoff, then tries `https://overpass.private.coffee/api/interpreter`
  once, only for timeout, connection, HTTP 429, or retryable 5xx failure;
- parses nodes from `lat`/`lon`;
- parses ways and relations from `center.lat`/`center.lon`;
- parses useful OSM tags without inventing missing values;
- safely ignores malformed elements;
- deduplicates stable OSM elements and semantic duplicates;
- returns typed `Place` objects with provider and OSM identity;
- calculates and sorts by Haversine distance from ITI;
- contains no fake fallback POIs.

Exact category mapping:

| UI category | Overpass selector |
|---|---|
| Parking | `nwr["amenity"="parking"]` |
| Fuel | `nwr["amenity"="fuel"]` |
| Food | `nwr["amenity"~"^(restaurant\|fast_food\|cafe\|food_court)$"]` |
| Hospital | `nwr["amenity"~"^(hospital\|clinic\|doctors)$"]` |
| Shopping | `nwr["shop"]` |

The result model can retain:

- stable provider ID, OSM type, and OSM ID;
- name, brand, and operator;
- category and subcategory;
- coordinates;
- assembled OSM address;
- phone, website, and opening-hours text when tagged;
- straight-line distance;
- provider source.

Missing names use a truthful category description such as
`Unnamed parking area`; missing addresses say that the address is
unavailable in OpenStreetMap. Ratings, reviews, photos, traffic, and fake
opening state were not added.

### Progressive radius and cache behavior

Category searches run sequentially:

```text
5 km -> 10 km -> 25 km -> 50 km
```

- Search stops as soon as at least 10 useful results exist.
- Search never expands beyond 50 km.
- Only the first 30 distance-sorted results are displayed.
- The UI says `Searching ... within 5 km` and
  `Expanding search to ...` using the actual current radius.
- Successful non-empty results are cached in memory with category, start
  coordinates, and effective radius in the key. Provider failures and
  timeouts are never cached as successful empty results.
- Requests are throttled and stale generations cannot update the UI.
- If a wider-radius request fails after a smaller radius returned real
  POIs, those verified smaller-radius results are retained instead of being
  discarded.
- A true zero-result search after 50 km enters the no-results
  presentation; it does not inject local fallback places.

### Verified first-launch Home and Work

Coordinates were resolved from real OpenStreetMap provider records during
implementation, not guessed.

Home:

```text
Name:       Sheikh Zayed
Address:    Sheikh Zayed, Giza, 12588, Egypt
Latitude:   30.0483470
Longitude:  30.9832235
Provider:   Nominatim / OpenStreetMap
OSM record: node 330194927
```

Work:

```text
Name:       Valeo
Address:    F22, Side Cairo, Alexandria Desert Road,
            Sheikh Zayed, Giza, 15311, Egypt
Latitude:   30.0787385
Longitude:  31.0179107
Provider:   Nominatim lookup + verified OpenStreetMap tags
OSM record: way 648005400
OSM tags:   name=Valeo, name:ar=فاليو, name:en=F22
```

`DemoDestinations` applies these only when the corresponding preference key
does not exist. Existing Cairo University Home/Work selections on the test
device were deliberately preserved during the normal upgrade test. A
separate backed-up fresh-data test confirmed that the first launch displayed
`Home — Sheikh Zayed` and `Work — Valeo`. User assignments remain
changeable through long-press and are never replaced on later launches.

### System theme integration

Navigation-specific theme ownership was removed:

- removed the top-bar theme button;
- retired the theme vector;
- removed the current app-local theme preference;
- removes the legacy `theme` key safely when preferences initialize;
- uses `AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM`;
- resolves night mode from `resources.configuration.uiMode`;
- keeps the existing `values/` and `values-night/` palettes;
- selects OpenFreeMap Dark for system dark mode;
- selects OpenFreeMap Positron for system light mode;
- retains Liberty as the verified map-style fallback.

The installed production Launcher was inspected at:

```text
/system_ext/priv-app/HyperNovaLauncher
```

It has the privileged
`android.permission.MODIFY_DAY_NIGHT_MODE` permission granted and already
changes Android system night mode through its existing controller. No
Launcher source or private Navigation-only broadcast was added.

The actual Launcher button was tested in both directions:

```text
Launcher “Switch to Dark mode”  -> cmd uimode reports yes
Launcher “Switch to Light mode” -> cmd uimode reports no
```

Navigation followed both changes, its map style changed, and a live
`ROUTE_OVERVIEW` state survived each Activity recreation. Direct
`adb shell cmd uimode night yes/no` testing produced the same result.

### Focused Cuttlefish evidence

Environment:

```text
Serial:       0.0.0.0:6520
Product:      hypernova_cockpit_x86_64
Model:        HyperNova_Cockpit
Device:       trout_x86_64
Android user: 10
Boot:         completed
Display:      1080 x 1920
Network:      validated Ethernet inside Android
```

Verified real flows:

- Parking: 30 real Overpass results; selected unnamed tagged parking
  element; OSRM returned 606 m / 2 min.
- Food: 16 real Overpass results within 5 km, including Smart Deli,
  Tabali Smart Village, and Cilantro; Smart Deli routed through OSRM at
  877 m / 2 min.
- Shopping: 30 real `shop=*` results within 5 km, including On the Run
  and Carrefour; On the Run routed through OSRM at 2.9 km / 7 min.
- Hospital: 8 real results within 5 km; a busy wider-radius provider
  request failed and the app correctly retained the 8 real smaller-radius
  results.
- Text search: `Cairo University` returned three real Nominatim results;
  selecting the first returned a real 28.8 km / 27 min OSRM route and a
  real OSRM alternative.
- Home: Sheikh Zayed routed through OSRM at 8.9 km / 18 min.
- Work: Valeo routed through OSRM at 1.4 km / 4 min.
- Active Route Preview and the real OSRM maneuver overview remained
  functional after the provider/theme changes.

During the same session the public Overpass instances intermittently
returned real 504/timeout failures for Fuel. The application displayed its
truthful provider-error UI; no fake fuel stations were substituted. Public
provider availability is an external limitation and is documented rather
than hidden.

Representative focused screenshots:

```text
HyperNovaNavigation/artifacts/screenshots/correction_baseline.png
HyperNovaNavigation/artifacts/screenshots/system_dark_home_intermediate.png
HyperNovaNavigation/artifacts/screenshots/light_home_verified_defaults_2.png
HyperNovaNavigation/artifacts/screenshots/food_loading_5km_intermediate.png
HyperNovaNavigation/artifacts/screenshots/dark_food_results_real.png
HyperNovaNavigation/artifacts/screenshots/light_shopping_results_real.png
HyperNovaNavigation/artifacts/screenshots/dark_parking_results_real.png
HyperNovaNavigation/artifacts/screenshots/hospital_results_real_2.png
HyperNovaNavigation/artifacts/screenshots/light_home_sheikh_zayed_route.png
HyperNovaNavigation/artifacts/screenshots/light_work_valeo_route.png
HyperNovaNavigation/artifacts/screenshots/dark_route_preview_overpass_2.png
HyperNovaNavigation/artifacts/screenshots/light_route_preview_system.png
HyperNovaNavigation/artifacts/screenshots/overpass_provider_timeout_real.png
HyperNovaNavigation/artifacts/screenshots/final_system_dark_home_restored_user_data.png
```

Every screenshot listed above was opened and visually inspected.

## Work boundaries

The existing Android project was extended in place. No new project was created, the package and public launcher action were preserved, and the working real-data navigation path was retained.

The following were deliberately not implemented:

- AIDL, Binder, or another IPC service;
- NOVA AI or conversational behavior;
- Raspberry Pi communication;
- a WebSocket, HTTP, or local server;
- live GPS or simulated vehicle movement;
- automatic maneuver progression, arrival, or rerouting;
- traffic data;
- ratings or reviews;
- downloadable offline maps;
- hard-coded fake places, routes, distances, durations, or recents.

Pre-existing working-tree changes in `AndroidManifest.xml`, `MainActivity.kt`, and `activity_main.xml` were inspected before editing and were not reset, cleaned, or discarded.

## Initial project investigation

Before implementation, the following work was completed:

- read the original repository README in full;
- inspected the complete project tree;
- inspected the manifest, Gradle configuration, version catalog, activity, XML layouts, strings, colors, themes, drawables, tests, and existing networking/map logic;
- reviewed the approved 12-screen UI reference image;
- ran `git status` and preserved the dirty worktree;
- built the original implementation;
- installed and ran the original APK on Cuttlefish;
- confirmed that its real MapLibre/OpenFreeMap map loaded;
- confirmed Cuttlefish networking from inside Android;
- verified that OpenFreeMap Dark, Positron, and Liberty style endpoints responded;
- captured and visually inspected the baseline application.

## Product behavior implemented

### 1. Navigation Home

The Home screen now contains:

- automotive top app bar with Back, HyperNova identity, current time, network status, and ITI start status; Navigation follows the Launcher/System theme and has no local theme toggle;
- truthful `ITI START` wording instead of a false GPS-ready claim;
- destination search card;
- Home, Work, Fuel, and Recent shortcuts;
- locally persisted real recent destinations;
- Home and Work configuration from a real selected result;
- long-press reconfiguration for Home and Work;
- MapLibre preview centered on ITI Smart Village;
- fixed ITI origin marker;
- real active-route summary when a route exists;
- primary Search Destination action.

The configured start point remains:

```text
ITI Smart Village
30.07112, 31.02075
```

### 2. Search Destination

The dedicated Search state now supports:

- focused search input and IME search action;
- clear-input and cancel actions;
- empty-query validation;
- loading presentation;
- empty-result and provider-error messages;
- real recent destinations;
- real Overpass category searches for Parking, Fuel, Food, Hospital, and Shopping;
- English place/address searches including Cairo University, Smart Village, Mit Habib, restaurants, gas stations, and hospitals.

No nearby businesses are injected locally.

### 3. Search Results

The Results state now shows:

- the submitted query;
- real Nominatim place names, addresses, and coordinates;
- a scrollable result list;
- selected-result styling;
- real map markers for all returned coordinates;
- a highlighted selected marker;
- explicitly labeled straight-line distance from ITI;
- Select Destination and Search Again actions;
- loading, empty, and error transitions.

No ratings or review counts are displayed.

### 4. Calculating Route

The app enters the calculating state immediately after destination confirmation. It:

- keeps the map visible;
- shows the ITI start point and selected destination;
- starts a real OSRM driving request off the main thread;
- allows cancellation;
- requests full GeoJSON geometry, alternatives, steps, and overview data;
- ignores stale route responses;
- classifies timeout, network, malformed-response, and no-route failures.

### 5. Route Preview

The route preview contains:

- complete real route geometry;
- route casing and cyan selected route;
- ITI start and destination markers;
- destination name and address;
- real driving distance and duration;
- arrival time calculated from the device clock and formatted according to the device 12/24-hour preference;
- fastest route selection;
- a real alternative only when OSRM returns one;
- Start Route, Route Overview, Change Destination, and Clear behavior;
- map bounds fitted with top/bottom padding for visible cards.

No traffic status is shown.

### 6. Active Navigation Preview

Because there is no movement source, this state is intentionally a route preview from ITI Smart Village. It:

- says `ROUTE ACTIVE`, `ITI START`, and `no live movement`;
- displays the first real OSRM maneuver;
- displays the real road name when supplied;
- uses `Road name unavailable` rather than inventing a road;
- keeps the real route geometry visible;
- shows real total distance, duration, and arrival time;
- provides Route Overview and End Route;
- persists a mute indicator preference while clearly avoiding a spoken-guidance claim;
- does not auto-progress route steps.

### 7. Route Overview

The overview now contains:

- destination;
- total real distance;
- total real duration;
- calculated arrival time;
- scrollable real OSRM step list;
- maneuver icons and descriptions;
- real road names when present;
- step distances;
- Return to Route;
- End Route.

Long English and provider-returned Arabic road/address text were checked on the 1080 × 1920 target.

### 8–12. Special and error states

Polished reusable presentations were implemented for:

- Rerouting;
- Arrived;
- Location Unavailable;
- Offline / Network Unavailable;
- Route Error.

The Route Error state provides Try Again, Change Destination, and Clear Route. Offline behavior does not promise offline routing or offline map downloads. Location Unavailable provides retry and settings behavior.

Rerouting and Arrived are debug-only presentations in the current ITI start-point demo. They are never triggered automatically or presented as real vehicle events.

## State model

`NavigationScreen` now defines:

```text
HOME
SEARCH
SEARCHING
RESULTS
CALCULATING_ROUTE
ROUTE_PREVIEW
ROUTE_ACTIVE
ROUTE_OVERVIEW
REROUTING
ARRIVED
LOCATION_UNAVAILABLE
OFFLINE
ROUTE_ERROR
```

`SEARCHING` is an internal loading condition rendered inside the Search screen. The 12 visible product screens are documented in [SCREEN_STATE_MATRIX.md](SCREEN_STATE_MATRIX.md), including trigger, data source, actions, failures, and real/debug status.

The state machine validates normal production transitions. Debug builds can explicitly override presentation state without creating fake provider data.

## Debug-state support

A hidden selector is available only when the application is debuggable:

1. Launch the debug APK.
2. Long-press the `HyperNova Navigation` top-bar title.
3. Select one of the 12 states.

States that need results, a destination, or a route require that real data to be loaded first.

States can also be selected through the activity extra:

```bash
adb shell am start --user 10 \
  -n com.hypernova.navigation/.MainActivity \
  --es debug_state ROUTE_ERROR
```

The debug selector does not add fake recents, falsify connectivity, create a fake route, or appear in production UI.

## Architecture changes

Provider, persistence, state, map, and formatting responsibilities were extracted from the activity:

```text
com.hypernova.navigation
├── MainActivity.kt
├── data
│   ├── nominatim/NominatimClient.kt
│   ├── osrm/OsrmClient.kt
│   └── persistence/NavigationPreferences.kt
├── domain
│   ├── model/NavigationModels.kt
│   └── repository/NavigationRepository.kt
└── ui
    ├── NavigationFormatters.kt
    ├── map/NavigationMapController.kt
    └── state/NavigationStateMachine.kt
```

### Nominatim client

`NominatimClient` now owns real place search:

- Egypt country filtering;
- a broad Cairo/Giza viewbox bias without excluding other valid Egyptian matches;
- eight-result limit;
- HyperNova user agent;
- finite connection/read timeouts;
- JSON validation and typed failures.

### OSRM client

`OsrmClient` now owns real route calculation:

- driving profile;
- `alternatives=true`;
- `steps=true`;
- `overview=full`;
- `geometries=geojson`;
- route geometry parsing;
- distance/duration parsing;
- alternative parsing and duration ordering;
- maneuver type/modifier/road/step-distance parsing;
- no-route and malformed-response handling;
- HyperNova user agent and finite timeouts.

### Repository

`NavigationRepository` provides:

- background executor network work;
- main-thread result delivery;
- cancellation of previous search/route work;
- generation IDs so stale callbacks cannot overwrite newer requests;
- Nominatim call throttling;
- small in-memory search-result cache;
- lifecycle-safe shutdown.

### Domain model

Typed models were added for:

- geographic points;
- places;
- route steps;
- alternatives;
- selected route plans;
- themes;
- saved-destination targets;
- failure kinds;
- complete navigation UI state;
- instance-state JSON snapshots.

### Activity/UI controller

`MainActivity` now coordinates:

- state rendering;
- MapLibre and Activity lifecycle;
- connectivity callbacks;
- Back behavior;
- keyboard behavior;
- saved-instance restoration;
- immediate theme recreation;
- debug-state dispatch;
- Home/Work/recent interactions;
- confirmation dialogs.

It no longer owns raw Nominatim, OSRM, or MapLibre source/layer construction.

## Map implementation

`NavigationMapController` replaced deprecated marker-style behavior with reusable GeoJSON sources and style layers for:

- origin ring;
- origin point;
- search results;
- selected search result;
- destination;
- selected-route casing;
- selected-route cyan line;
- real alternatives at lower opacity;
- dashed calculation line.

The controller:

- restores markers and routes after every style reload;
- avoids duplicate source/layer creation;
- fits full route bounds around visible UI cards;
- updates selected routes;
- keeps alternatives lower emphasis;
- reloads the correct style during theme changes;
- tries Liberty if the preferred OpenFreeMap style fails.

Style selection:

```text
Dark mode  -> OpenFreeMap Dark
Light mode -> OpenFreeMap Positron
Fallback   -> OpenFreeMap Liberty
```

All three style URLs were verified from inside the running Cuttlefish device.

## Persistence changes

`NavigationPreferences` persists:

- up to six actual selected recent destinations;
- verified default or user-configured Home destination;
- verified default or user-configured Work destination;
- mute-indicator preference;
- only safe startup state information.

The legacy Navigation-only theme key is removed safely. Android system
`uiMode`, controlled by the HyperNova Launcher/System, is the only current
theme source.

No loading or error state becomes the permanent cold-start screen. A normal cold start returns to Home.

Backup/data-transfer XML was changed from generated placeholder content to explicit backup of only:

```text
hypernova_navigation_preferences.xml
```

## Dark and light theme work

The UI has two intentionally designed themes rather than a color inversion.

Dark mode uses:

- deep navy background;
- dark blue elevated cards;
- cool thin borders;
- cyan actions and routes;
- white primary text;
- muted blue-gray secondary text;
- green, amber, and red semantic states;
- OpenFreeMap Dark.

Light mode uses:

- pale blue-gray surfaces;
- white/elevated cards;
- dark navy text;
- retained cyan action emphasis;
- adjusted semantic colors and borders;
- OpenFreeMap Positron.

System theme changes:

- apply immediately through `MODE_NIGHT_FOLLOW_SYSTEM`;
- recreate the activity safely;
- preserve real results/route state during recreation;
- select matching OpenFreeMap light/dark styles from the current Android configuration.

Themed resources were added or completed in:

- `values/colors.xml`;
- `values-night/colors.xml`;
- `values/themes.xml`;
- `values-night/themes.xml`;
- `values/styles.xml`.

## Automotive UI and accessibility work

The layouts were designed for 1080 × 1920 portrait Automotive use:

- minimum practical 48–56 dp controls;
- card-aware map layout;
- scrollable result and maneuver lists;
- safe multiline/ellipsized addresses;
- portrait lock retained;
- edge-to-edge insets retained;
- focusable/clickable Material controls;
- content descriptions on icon-only controls;
- proper vector icons rather than emoji;
- text raised to at least 11 sp where lint identified smaller values;
- RTL padding symmetry;
- AppCompat compound drawable attributes;
- baseline-alignment optimizations;
- plural resource for result counts;
- localized format resources instead of concatenated display strings.

## Manifest/integration work

The manifest retains:

```text
Package:       com.hypernova.navigation
Main activity: com.hypernova.navigation.MainActivity
Public action: com.hypernova.navigation.action.OPEN
```

Additional manifest behavior:

- `singleTop` prevents duplicate activity instances when the public action is delivered;
- portrait orientation remains explicit;
- `adjustResize` supports the search keyboard;
- Internet and network-state permissions remain scoped to the real online providers.

No HOME intent filter was added.

## Tests added

The generated placeholder `ExampleUnitTest.kt` was removed and replaced with real tests:

- `DemoDestinationsTest.kt`
  - verified defaults fill only absent Home/Work values;
  - user-saved values are retained;
- `NavigationFormattersTest.kt`
  - route arrival calculation;
  - 12-hour device-time formatting;
  - distance formatting;
  - zero straight-line distance;
- `NavigationJsonTest.kt`
  - enriched provider/OSM place data survives state snapshots;
- `NavigationPreferenceContractTest.kt`
  - current persisted keys contain no local theme;
  - the legacy theme key remains known only for retirement;
- `NavigationStateMachineTest.kt`
  - accepted production transitions;
  - rejected invalid transitions;
  - debug override behavior;
- `NearbyCategoryTest.kt`
  - exact category-to-OSM selector mapping;
- `NearbySearchPolicyTest.kt`
  - 5/10/25/50 km sequence;
  - stop, maximum-radius, cap, and distance-order behavior;
- `OverpassClientTest.kt`
  - node coordinates;
  - way/relation centers;
  - malformed element handling;
  - address construction;
  - deduplication and distance sorting;
- `PlaceModelTest.kt`
  - provider display-name parsing;
  - address/name fallback behavior.
- `SystemThemeResolverTest.kt`
  - Android light, dark, and undefined night-mask resolution.

## Build and static validation

Final command:

```bash
cd HyperNovaNavigation
./gradlew assembleDebug test lint
```

Final result:

```text
BUILD SUCCESSFUL in 27s
assembleDebug: passed
test:          passed (23 tests, 0 failures)
lint:          passed
```

Lint result:

```text
0 errors, 46 warnings
```

The warning count was reduced from the original baseline during hardening.
The final 46 warnings are non-blocking and intentional for the requested
project:

- pinned/current-tool notices for AGP, SDK, and MapLibre;
- required portrait orientation;
- unused documented design tokens;
- existing logging/KTX suggestions;
- MapView/root-background overdraw suggestions.

No lint errors were suppressed.

Reports:

```text
HyperNovaNavigation/app/build/reports/lint-results-debug.html
HyperNovaNavigation/app/build/reports/lint-results-debug.txt
HyperNovaNavigation/app/build/reports/lint-results-debug.xml
```

## Cuttlefish validation

Detected environment:

```text
ADB serial:     0.0.0.0:6520
Product:        hypernova_cockpit_x86_64
Model:          HyperNova_Cockpit
Device:         trout_x86_64
Android user:   10
Boot completed: 1
Display:        1080 × 1920
```

Final install:

```bash
adb install --user 10 -r -t \
  app/build/outputs/apk/debug/app-debug.apk
```

Result:

```text
Performing Streamed Install
Success
```

Final component launch:

```bash
adb shell am start --user 10 -W \
  -n com.hypernova.navigation/.MainActivity
```

Result:

```text
Status: ok
LaunchState: COLD
Activity: com.hypernova.navigation/.MainActivity
```

Public action verification:

```bash
adb shell am start --user 10 -W \
  -a com.hypernova.navigation.action.OPEN
```

Result:

```text
Status: ok
Activity: com.hypernova.navigation/.MainActivity
```

### Real provider flow validated

The following exact user flow was executed on Cuttlefish:

1. cold-start Home;
2. open Search and show the keyboard;
3. submit a real `Cairo University` Nominatim query;
4. receive three real results;
5. select a real Cairo University result;
6. enter Calculating Route;
7. receive real OSRM routes;
8. render a 28.8 km / 27 minute fastest route;
9. render OSRM's real 28.7 km / 28 minute alternative;
10. inspect the cyan route geometry and destination marker;
11. start Active Route Preview;
12. inspect the first real maneuver;
13. open and scroll the real maneuver list;
14. switch dark to light during the route;
15. verify route state survived recreation;
16. force-stop and verify the light theme, real recent, Home, and Work persisted;
17. verify safe cold start returns to Navigation Home;
18. verify End Route confirmation and Clear Route behavior;
19. exercise the special/debug states.

The final ETA formatting was specifically verified against the Cuttlefish user's 12-hour clock: the header showed `4:49 PM` and the route arrival showed `5:15 PM`.

### Log findings

Runtime inspection found:

- no app `FATAL EXCEPTION`;
- no ANR;
- no MapLibre source/layer duplication errors;
- no activity-recreation crash;
- no stale callback overwrite;
- no critical Nominatim or OSRM parsing error in the successful flow.

Observed non-app/device warnings:

- Cuttlefish HWUI wide-gamut configuration fallback;
- Android system TaskPersister missing-recents-directory warning;
- an AccessibilityManager timing warning.

These came from the emulator/system processes and did not crash or impair the app.

## Screenshot artifacts

All screenshot artifacts are under:

```text
HyperNovaNavigation/artifacts/screenshots/
```

Captured files:

- `dark_home.png`
- `dark_home_final.png`
- `light_home.png`
- `light_home_exact_final.png`
- `search.png`
- `search_loading.png`
- `search_results.png`
- `calculating_route.png`
- `calculating_route_light.png`
- `calculating_route_final.png`
- `dark_route_preview.png`
- `dark_route_preview_final.png`
- `dark_route_preview_exact_final.png`
- `light_route_preview.png`
- `active_route_preview.png`
- `active_route_preview_exact_final.png`
- `route_overview.png`
- `route_overview_exact_final.png`
- `light_route_overview_exact_final.png`
- `correction_baseline.png`
- `food_loading_5km_intermediate.png`
- `fuel_progressive_loading.png`
- `dark_food_results_real.png`
- `light_shopping_results_real.png`
- `dark_parking_results_real.png`
- `hospital_results_real_2.png`
- `overpass_provider_timeout_real.png`
- `system_dark_home_intermediate.png`
- `light_home_verified_defaults_2.png`
- `light_home_sheikh_zayed_route.png`
- `light_work_valeo_route.png`
- `dark_route_preview_overpass_2.png`
- `light_route_preview_system.png`
- `end_route_confirmation.png`
- `debug_rerouting.png`
- `debug_arrived.png`
- `debug_location_unavailable.png`
- `debug_offline.png`
- `debug_route_error.png`

Every screenshot was visually inspected individually or in a labeled 5 × 5 contact sheet. The exact-final images verify the last compiled/installed APK.

## Files created

### Kotlin source

- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/data/nominatim/NominatimClient.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/data/overpass/OverpassClient.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/data/osrm/OsrmClient.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/data/persistence/NavigationPreferenceContract.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/data/persistence/NavigationPreferences.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/domain/model/DemoDestinations.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/domain/model/NavigationModels.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/domain/repository/NearbySearchPolicy.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/domain/repository/NavigationRepository.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/ui/NavigationFormatters.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/ui/SystemThemeResolver.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/ui/map/NavigationMapController.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/ui/state/NavigationStateMachine.kt`

### Layout resources

- `item_maneuver.xml`
- `item_place_result.xml`
- `item_recent_destination.xml`
- `panel_active_route.xml`
- `panel_calculating.xml`
- `panel_home.xml`
- `panel_results.xml`
- `panel_route_overview.xml`
- `panel_route_preview.xml`
- `panel_search.xml`
- `panel_special_state.xml`
- `view_route_metric.xml`
- `view_top_bar.xml`

All layout files above are under `HyperNovaNavigation/app/src/main/res/layout/`.

### Vector resources

- `ic_back.xml`
- `ic_close.xml`
- `ic_error.xml`
- `ic_food.xml`
- `ic_fuel.xml`
- `ic_home.xml`
- `ic_hospital.xml`
- `ic_location_off.xml`
- `ic_maneuver_left.xml`
- `ic_maneuver_right.xml`
- `ic_maneuver_straight.xml`
- `ic_navigation.xml`
- `ic_parking.xml`
- `ic_place.xml`
- `ic_recent.xml`
- `ic_refresh.xml`
- `ic_route_overview.xml`
- `ic_search.xml`
- `ic_shopping.xml`
- `ic_success.xml`
- `ic_volume.xml`
- `ic_volume_off.xml`
- `ic_warning.xml`
- `ic_work.xml`

All vector files above are under `HyperNovaNavigation/app/src/main/res/drawable/`.

### Theme/test/documentation/artifacts

- `HyperNovaNavigation/app/src/main/res/values-night/colors.xml`
- `HyperNovaNavigation/app/src/main/res/values/styles.xml`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/NavigationFormattersTest.kt`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/DemoDestinationsTest.kt`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/NavigationJsonTest.kt`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/NavigationPreferenceContractTest.kt`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/NavigationStateMachineTest.kt`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/NearbyCategoryTest.kt`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/NearbySearchPolicyTest.kt`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/OverpassClientTest.kt`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/PlaceModelTest.kt`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/SystemThemeResolverTest.kt`
- `HyperNovaNavigation/artifacts/screenshots/*.png`
- `SCREEN_STATE_MATRIX.md`
- `README_CHANGES.md`

## Files modified

- `README.md`
- `README_CHANGES.md`
- `SCREEN_STATE_MATRIX.md`
- `HyperNovaNavigation/app/build.gradle.kts`
- `HyperNovaNavigation/gradle/libs.versions.toml`
- `HyperNovaNavigation/app/src/main/AndroidManifest.xml`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/MainActivity.kt`
- `HyperNovaNavigation/app/src/main/res/layout/activity_main.xml`
- `HyperNovaNavigation/app/src/main/res/values/colors.xml`
- `HyperNovaNavigation/app/src/main/res/values/strings.xml`
- `HyperNovaNavigation/app/src/main/res/values/themes.xml`
- `HyperNovaNavigation/app/src/main/res/values-night/themes.xml`
- `HyperNovaNavigation/app/src/main/res/xml/backup_rules.xml`
- `HyperNovaNavigation/app/src/main/res/xml/data_extraction_rules.xml`

## File replaced/retired

- Removed the generated placeholder `ExampleUnitTest.kt`.
- Retired `ic_theme.xml` because Navigation no longer owns a theme control.
- Added the real unit-test files listed above.

## Existing configuration intentionally preserved

No product/runtime dependency changes were made to:

- SDK versions;
- MapLibre version 13.3.1;
- application ID/package;
- public launcher action;
- fixed ITI origin;
- the HyperNova Launcher;
- the AOSP source tree;
- Cuttlefish host networking.

## Remaining limitations

- ITI Smart Village start point only;
- no live GPS;
- no vehicle motion simulation;
- no automatic maneuver advancement;
- no automatic rerouting;
- no automatic arrival;
- no traffic;
- no ratings/reviews;
- no offline routing engine;
- no downloadable offline maps;
- online providers are required for new search/routes;
- public Overpass instances can be temporarily busy, throttle, or time out;
- no spoken guidance;
- no AIDL;
- no NOVA AI integration;
- no Raspberry Pi communication.

These are intentional scope boundaries, not unfinished placeholders in the standalone navigation flow.

## Exact working-tree summary — 2026-07-23

`git diff --stat` for tracked files:

```text
13 files changed, 2728 insertions(+), 3237 deletions(-)
```

Tracked status:

```text
12 modified files
1 deleted generated placeholder test
```

Untracked files retained in the working tree:

```text
109 total
63 app source/resource/test files
44 screenshot artifacts
2 root documentation files
```

No reset, clean, destructive checkout, unrelated-file deletion, commit, or
discard operation was performed. This summary reflects the full pre-existing
standalone implementation plus the focused correction; the detailed lists
above identify the files created and modified.
