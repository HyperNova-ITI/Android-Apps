# HyperNova Navigation — Complete Technical Guide

## 1. Purpose of This Document

This guide is for an engineer joining the project after the standalone
navigation work was completed. It explains what the application does, how its
files fit together, how its real online data flow works, and which features
are deliberately outside the current scope.

The source code is the primary reference for this guide. Historical details
come from `README.md`, `README_CHANGES.md`, `SCREEN_STATE_MATRIX.md`, and
`AGENT_CHECKPOINT.md` only when they agree with the current implementation.

## 2. Application Overview

HyperNova Navigation is a portrait Android Automotive navigation application
for the HyperNova cockpit. It is a standalone app: a user can search for a
real place, select it, and see a real driving route without an AI node.

| Item | Current value |
|---|---|
| Package / application ID | `com.hypernova.navigation` |
| Activity entry point | `com.hypernova.navigation.MainActivity` |
| Public Launcher action | `com.hypernova.navigation.action.OPEN` |
| Display target | 1080 × 1920 portrait |
| Start point | ITI Smart Village, `30.07112, 31.02075` |
| UI technology | Kotlin, Android Views/XML, ViewBinding |
| Map renderer | MapLibre Android 13.3.1 |

The configured start point is used internally for all routes in this demo.
The app truthfully presents this as an ITI start point and route preview; it
does not claim GPS, vehicle movement, traffic, or spoken guidance.

```text
HyperNova Launcher / Android system uiMode
                 |
                 v
       HyperNova Navigation app
                 |
       +---------+----------+-----------+
       |                    |           |
       v                    v           v
 MapLibre/OpenFreeMap   Nominatim    Overpass / OSRM
       map               text search   nearby POIs / routes
```

Future NOVA AI and AIDL work is intentionally separate. It is not a hidden
dependency of the current app.

## 3. Final User Experience

Normal destination flow:

```text
Home
  -> Search Destination
  -> enter a name/address or choose a category
  -> Results
  -> select a destination
  -> Calculating Route
  -> Route Preview
  -> Start Route (truthful preview mode)
  -> Route Overview
  -> End Route or Clear Route
  -> Home
```

There are two search types:

```text
Text: "Cairo University"
  -> Nominatim geocoding -> real named-place results

Category: Fuel, Food, Parking, Hospital, Shopping
  -> Overpass OSM query around ITI -> real nearby POIs
```

Both kinds of selected result use exactly the same OSRM route pipeline.

## 4. Project History and Evolution

The reports and current files show this sequence of development:

1. A basic MapLibre `MapView` was added to the existing Android project.
2. OpenFreeMap styles provided public map styling and tiles.
3. ITI Smart Village became the configured start point.
4. `NominatimClient` added real text/place/address geocoding.
5. Search-result and destination markers were added.
6. `OsrmClient` added real route geometry.
7. Real distance, duration, ETA, alternatives, and steps were parsed.
8. Route Preview, Active Route Preview, and Route Overview were added.
9. The UI was expanded to the current 12 visible screen presentations.
10. Intentional light and dark resource palettes were introduced.
11. Theme ownership moved to Android system `uiMode`; Navigation no longer
    owns a local theme preference.
12. Real first-launch Home and Work defaults plus user persistence were added.
13. Nearby categories moved from weak text-like geocoding queries to real
    Overpass OSM queries.
14. Request generations, cancellation, retry/fallback, progressive radius,
    cache rules, and visible error states improved category reliability.
15. Charging was removed from the product; Fuel remains in Home and Search.
16. Start-status wording was cleaned up to use ITI-specific, truthful labels.

## 5. Technology Stack

| Technology | Why it is used | Where it appears |
|---|---|---|
| Kotlin | Application language | All `.kt` source files |
| Android Views/XML | Existing UI approach; suited to the current app | `res/layout`, `MainActivity` |
| ViewBinding | Type-safe access to layout views | `build.gradle.kts`, generated bindings in `MainActivity` |
| AndroidX / AppCompat | Lifecycle, compatibility, back handling, system theme | Gradle dependencies, `MainActivity` |
| Material Components | Cards, buttons, dialogs, text fields | layouts and `values/styles.xml` |
| MapLibre | Interactive, real map renderer | `NavigationMapController`, `MapView` |
| OpenFreeMap | Public map style/tile service | map style URL constants |
| Nominatim | Real text/address geocoding | `data/nominatim/NominatimClient.kt` |
| Overpass API | Real nearby OpenStreetMap POI queries | `data/overpass/OverpassClient.kt` |
| OSRM | Real driving routes and maneuvers | `data/osrm/OsrmClient.kt` |
| SharedPreferences | Small local user settings and destinations | `NavigationPreferences.kt` |
| JUnit 4 | Local unit tests | `app/src/test` |
| Cuttlefish / ADB | Automotive emulator installation and validation | commands in this guide |

## 6. High-Level Architecture

```text
XML views + ViewBinding
          |
          v
MainActivity
  - renders NavigationUiState
  - owns Android/MapView lifecycle
          |
          v
NavigationRepository
  - background execution, throttling, cache, request generations
          |
   +------+------+------+------+
   |             |             |
   v             v             v
Nominatim     Overpass       OSRM       NavigationPreferences
text search   nearby POIs    routes     local user data

MainActivity -> NavigationMapController -> MapLibre -> OpenFreeMap
```

`MainActivity` composes screens and reacts to callbacks. It does not build
raw HTTP requests or MapLibre sources/layers. `NavigationRepository` is the
boundary between UI and providers. Provider clients parse provider-specific
JSON into domain models. `NavigationMapController` owns map state separately
from Android view state.

## 7. Complete Project Tree

```text
.
├── README.md                         Product overview
├── README_CHANGES.md                 Detailed implementation history
├── SCREEN_STATE_MATRIX.md            Compact state reference
├── AGENT_CHECKPOINT.md               Earlier paused-work checkpoint
├── NAVIGATION_APP_COMPLETE_GUIDE.md  This guide
└── HyperNovaNavigation/
    ├── app/build.gradle.kts          Module build configuration
    ├── gradle/libs.versions.toml     Version catalog
    ├── app/src/main/AndroidManifest.xml
    ├── app/src/main/java/com/hypernova/navigation/
    │   ├── MainActivity.kt
    │   ├── data/{nominatim,overpass,osrm,persistence}/
    │   ├── domain/{model,repository}/
    │   └── ui/{map,state}/
    ├── app/src/main/res/{layout,drawable,values,values-night,xml}/
    ├── app/src/test/java/com/hypernova/navigation/
    ├── app/src/androidTest/java/com/hypernova/navigation/
    └── artifacts/                    Captured Cuttlefish screenshots
```

The data package contains provider and preferences code. Domain contains
provider-neutral data objects and repository policy. UI contains formatting,
theme resolution, state validation, and MapLibre rendering. Layout files are
screen panels inflated by `MainActivity`; vector drawables provide the
automotive icons. `artifacts/` is evidence, not runtime application content.

## 8. Build Configuration

`HyperNovaNavigation/app/build.gradle.kts` sets:

| Setting | Meaning |
|---|---|
| Namespace / ID | `com.hypernova.navigation` identifies generated resources and installed app |
| compileSdk | Android 36, minor API level 1, used to compile APIs |
| minSdk | 31; Android 12 is the oldest supported platform |
| targetSdk | 36; declared target behavior level |
| version | `1.0`, version code `1` |
| ViewBinding | Generates classes such as `ActivityMainBinding` |
| Java compatibility | Java 11 source and target compatibility |
| Release optimization | Explicitly disabled in this project |

The version catalog pins AGP 9.2.1, AndroidX, Material 1.14.0, MapLibre
13.3.1, `org.json`, JUnit, Espresso, and AndroidX test runner dependencies.

## 9. AndroidManifest

`app/src/main/AndroidManifest.xml` declares only the permissions needed for
online data:

| Manifest item | Purpose |
|---|---|
| `INTERNET` | OpenFreeMap, Nominatim, Overpass, and OSRM network access |
| `ACCESS_NETWORK_STATE` | Detect genuine connectivity loss |
| `.MainActivity` | Exported activity entry point |
| `singleTop` | Reuses the visible activity for repeat public launches |
| `portrait` | Keeps the Automotive UI portrait-oriented |
| `adjustResize` | Lets the search keyboard resize the window |
| `MAIN` + `LAUNCHER` | Normal app launch |
| `com.hypernova.navigation.action.OPEN` + `DEFAULT` | HyperNova Launcher contract |

There is no `HOME` intent filter, so Navigation does not replace the system
home/Launcher app. There is no separate Automotive metadata declaration in
this manifest. Backup and transfer are restricted to the named navigation
SharedPreferences file through `res/xml/backup_rules.xml` and
`res/xml/data_extraction_rules.xml`.

## 10. MainActivity Responsibilities

`MainActivity.kt` is the coordinator. Major methods include `onCreate`,
`render`, `performTextSearch`, `performNearbyCategory`,
`beginRouteCalculation`, `renderMapScene`, `handleBack`, and
`restoreUiState`.

It:

- enables `MODE_NIGHT_FOLLOW_SYSTEM` before initialization;
- creates ViewBinding, preferences, repository, state machine, and map
  controller;
- forwards the full MapView lifecycle (`onCreate`, `onStart`, `onResume`,
  `onPause`, `onStop`, `onLowMemory`, `onDestroy`);
- binds Home/Search/Results/Route panel actions;
- observes default network changes with `ConnectivityManager`;
- renders `NavigationUiState` into the appropriate XML panel;
- handles Back, Cancel, End Route, Home/Work configuration, and recents;
- saves results, destination, route plan, category/radius, and safe UI state
  in the Activity instance bundle;
- restores settled data after `uiMode` recreation but converts transient
  search/calculation states to safe Search/Results states;
- exposes debug state controls only in a debuggable app.

Raw HTTP, provider parsing, map layer construction, persistence serialization,
and transition rules have been moved into dedicated classes.

## 11. Domain Models

`NavigationModels.kt` defines the app's shared vocabulary.

| Model / enum | Meaning |
|---|---|
| `GeoPoint` | Latitude/longitude pair |
| `GeoDistance` | Haversine distance calculation in metres |
| `Place` | A named place/POI plus coordinates, provider identity, tags, address, and optional straight-line distance |
| `PlaceProvider` | `NOMINATIM`, `OVERPASS`, or `VERIFIED_OSM` |
| `NearbyCategory` | Visible category plus exact OSM selector and safe fallback name |
| `RouteStep` | One real OSRM maneuver, road, distance, duration, type, modifier, exit |
| `RouteAlternative` | Geometry, distance, duration, and steps for one route |
| `RoutePlan` | Alternatives and the selected index |
| `NavigationScreen` | The screen-state enum |
| `SavedDestinationTarget` | `HOME` or `WORK` assignment target |
| `NavigationUiState` | Current screen plus query, results, selection, route, message, and category progress |
| `FailureKind` | Network, timeout, no route, malformed response, provider, cancelled, or unknown |
| `NavigationDataException` | Typed provider failure with HTTP/retry context |
| `NavigationJson` | JSON snapshots for `Place` and `RoutePlan` restoration |

`Place.id` favors a stable provider ID and falls back to an OSM identity or
coordinates. This is why recents, selection, and deduplication can work
across provider responses.

## 12. Navigation State Machine

`NavigationStateMachine.kt` validates normal transitions. Operational errors
are allowed from every state; debug override bypasses normal validation only
in debuggable builds.

| State | Trigger | Data | Main UI | Next states | Current status |
|---|---|---|---|---|---|
| `HOME` | cold start, clear/end | ITI, recents, Home/Work | shortcuts and map | Search, route calculation | Real |
| `SEARCH` | Search/Back/configure | query, recents, categories | field and category grid | Searching, Results, Home | Real |
| `SEARCHING` | provider started | query/category/radius | loading/cancel | Results, Search, Home | Real internal loading |
| `RESULTS` | provider success | real `Place` list | markers/list/select | Search, calculating, Home | Real |
| `CALCULATING_ROUTE` | destination confirmed | real destination | route spinner/cancel | Preview, Results, errors | Real |
| `ROUTE_PREVIEW` | OSRM success | `RoutePlan` | route, ETA, alternatives | Active, Overview, Search, Home | Real |
| `ROUTE_ACTIVE` | Start Route | first real maneuver | preview controls | Overview, Preview, Home | Real preview |
| `ROUTE_OVERVIEW` | overview action | OSRM steps | scrollable maneuvers | Active/Preview/Home | Real |
| `REROUTING` | future/debug | existing route if any | reusable status | Active/error/Home | Debug-only presentation |
| `ARRIVED` | future/debug | route summary | arrival presentation | Home | Debug-only presentation |
| `LOCATION_UNAVAILABLE` | defensive/debug | start configuration | retry/settings | Home/Search | Defensive state |
| `OFFLINE` | connectivity loss/debug | connectivity | retry/home | safe prior flow | Real error state |
| `ROUTE_ERROR` | OSRM failure/debug | failure/destination | retry/change/clear | calculate/Search/Results/Home | Real error state |

```text
HOME -> SEARCH -> SEARCHING -> RESULTS -> CALCULATING_ROUTE
                                      -> ROUTE_PREVIEW -> ROUTE_ACTIVE
                                              |               |
                                              +-> OVERVIEW <-+

Any operational state -> OFFLINE / LOCATION_UNAVAILABLE / ROUTE_ERROR
ROUTE_ACTIVE -> REROUTING or ARRIVED only in future real movement work
```

## 13. Free-Text Search with Nominatim

1. The user enters text such as `Cairo University` and submits via button or
   IME action.
2. `MainActivity.performTextSearch` rejects blank input and enters
   `SEARCHING`.
3. `NavigationRepository.searchTextPlace` cancels nearby work, advances the
   text generation, checks its small in-memory cache, and throttles requests.
4. `NominatimClient.search` performs a GET to
   `https://nominatim.openstreetmap.org/search`.
5. It sends the documented HyperNova User-Agent, JSON accept header, locale,
   `countrycodes=eg`, `addressdetails=1`, `dedupe=1`, `limit=8`, and a broad
   Cairo/Giza viewbox bias. `bounded=0` means a valid Egyptian result outside
   that box remains eligible.
6. JSON is parsed into typed `Place` values with OSM type/ID and display
   address.
7. The repository delivers the current generation on the main thread. Stale
   callbacks are logged and ignored.
8. Results are displayed with real markers. A Nominatim result has no
   pre-route distance field unless supplied by the model; driving data comes
   only after OSRM.

Nominatim is a geocoder for names and addresses, not a complete nearby POI
catalog. Therefore it is intentionally not used for category buttons.

## 14. Nearby Category Search with Overpass

Overpass was added because a text phrase like “food near Smart Village” can
miss nearby POIs. It queries OpenStreetMap tags around real coordinates.

| Visible category | Current selector |
|---|---|
| Parking | `nwr["amenity"="parking"]` |
| Fuel | `nwr["amenity"="fuel"]` |
| Food | `nwr["amenity"~"^(restaurant|fast_food|cafe|food_court)$"]` |
| Hospital | `nwr["amenity"~"^(hospital|clinic|doctors)$"]` |
| Shopping | `nwr["shop"]` |

`OverpassClient` POSTs form field `data` rather than making an oversized URL.
It uses the same HyperNova User-Agent and finite 7-second connect/16-second
read timeouts. The query is structurally like:

```overpass
[out:json][timeout:25];
(
  nwr["amenity"="fuel"](around:5000,30.071120,31.020750);
);
out center tags 100;
```

The primary endpoint is `https://overpass-api.de/api/interpreter`; the
fallback is `https://overpass.private.coffee/api/interpreter`. For timeout,
connection failure, HTTP 429, or retryable 5xx, the bounded plan is primary
attempt 1, primary attempt 2 after a small backoff, then one fallback attempt.
Permanent client errors and malformed responses do not retry.

Nearby radius progresses sequentially through 5, 10, 25, and 50 km. It stops
when it has at least 10 useful results or after the maximum radius; the UI
shows no more than 30 results. Nodes use `lat`/`lon`; ways and relations use
`center.lat`/`center.lon`. Invalid elements are ignored. Stable OSM IDs and
semantic name/location keys deduplicate data. Missing names use a truthful
category fallback, and address parts are assembled only from OSM tags.

The app calculates Haversine distance from ITI, labels this as nearby/
straight-line distance, sorts ascending, and sends the selected POI to OSRM
for driving data. Public OSM data can be incomplete, busy, throttled, or
temporarily unavailable; the app never substitutes invented POIs.

## 15. Search Reliability and Request Lifecycle

`NavigationRepository` has three independent `RequestGenerationGate`s:
one each for text search, category search, and routing. `next()` starts a new
generation; `cancel()` invalidates the old one. Delivery checks that a
callback still belongs to the current generation.

For categories, `searchNearbyCategory` handles repeated actions as follows:

```text
same category + same origin while active -> keep current request
different category -> cancel/supersede old work, start a new generation
Back/Cancel -> interrupt work, invalidate generation, return to safe UI
```

The repository has a three-thread executor and posts UI callbacks using a
main-thread `Handler`. It throttles Nominatim at 1100 ms and Overpass at
900 ms. Text cache keys are normalized query text. Nearby cache keys include
category, latitude, longitude, and effective radius. Only non-empty real
nearby success is cached; provider failures/timeouts are never cached as a
successful empty result.

`NearbyResultAccumulator` preserves real smaller-radius results if a later
wider radius fails. This yields partial real Results rather than converting a
provider problem into “No results.” Zero completed results at 50 km becomes
No Results; a provider failure before any valid result becomes Provider Error.
Logging uses `HyperNovaOverpass`, `HyperNovaNavigationRepository`, and
`HyperNovaCategorySearch` without raw response bodies.

## 16. Route Calculation with OSRM

After selection, `MainActivity.beginRouteCalculation` enters
`CALCULATING_ROUTE` immediately and calls `NavigationRepository.calculateRoute`.
`OsrmClient.route` builds this shape:

```text
https://router.project-osrm.org/route/v1/driving/
  originLongitude,originLatitude;destinationLongitude,destinationLatitude
  ?alternatives=true&steps=true&overview=full&geometries=geojson
```

OSRM requires longitude,latitude order; Android models store latitude,
longitude, so `OsrmClient` deliberately reverses them while creating the URL.
It uses 12-second connect and 20-second read timeouts and parses:

- `routes` into alternatives, sorted by duration;
- GeoJSON coordinates into route points;
- metres and seconds as driving distance/duration;
- route legs and steps;
- maneuver type, modifier, exit, road/ref, and destinations;
- a readable instruction such as turn, merge, roundabout, or arrive.

Search-result straight-line distance is geometric proximity. Route Preview
distance and duration are driving values returned by OSRM. A failure becomes
a typed timeout/network/provider/malformed/no-route result and stale route
callbacks cannot overwrite a newer calculation.

## 17. MapLibre and OpenFreeMap

`NavigationMapController` initializes MapLibre from the layout `MapView`.
It chooses the style from `SystemThemeResolver.isNightMode`:

| System mode | Preferred URL |
|---|---|
| Dark | `https://tiles.openfreemap.org/styles/dark` |
| Light | `https://tiles.openfreemap.org/styles/positron` |
| Style failure fallback | `https://tiles.openfreemap.org/styles/liberty` |

It uses GeoJSON sources and style layers rather than deprecated marker APIs.
Sources hold the origin, destination, result markers, selected result,
selected route, alternatives, and calculation preview. Layers draw an origin
ring/point, destination and result circles, cyan route casing/line,
low-emphasis alternatives, and a dashed calculation line.

`setScene` stores the whole map scene, and `reapplyScene` restores it after a
style reload. `addSourceIfMissing` and layer checks avoid duplicate-source or
duplicate-layer errors. `fitSearchResults` and `fitRoute` use explicit card-
aware padding so geometry remains visible behind top/bottom panels.

## 18. Screen-by-Screen UI Explanation

### Home

`panel_home.xml` is the cold-start presentation. It shows current time,
online/offline status, ITI start status, Search Destination, Home, Work,
Fuel, Recent, persisted recents, route summary, and map controls. Its data is
local preferences plus current route/network state.

### Search

`panel_search.xml` has a text field, IME search, clear behavior, loading
card, inline error/retry, recents, categories, and Cancel. Category progress
includes category name and current radius. It uses Nominatim or Overpass.

### Search Results

`panel_results.xml` shows query/category context, real result count, optional
partial-result note, scrollable result cards, map markers, selected state,
Select Destination, and Search Again. `item_place_result.xml` renders name,
category, address, and clearly pre-route distance when present.

### Calculating Route

`panel_calculating.xml` retains the map, identifies ITI and destination,
shows a progress indicator, and allows cancellation while OSRM runs.

### Route Preview

`panel_route_preview.xml` shows real destination/address, selected route
metrics, device-clock ETA, fastest route, a second card only when a real
alternative exists, Start Route, Overview, and Change Destination.

### Active Route Preview

`panel_active_route.xml` shows the first real OSRM maneuver, real distance,
duration, ETA, Route Overview, End Route, and persisted mute-indicator state.
It explicitly remains preview mode with no live movement.

### Route Overview

`panel_route_overview.xml` has total metrics and a scrollable list of real
steps using `item_maneuver.xml`; it returns to its owning Preview/Active view
or ends the route.

### Rerouting and Arrived

`panel_special_state.xml` renders both, but current fixed-position demo code
does not automatically enter them. They are useful debug/future presentation
states, not claims of real movement.

### Location Unavailable, Offline, and Route Error

The same special layout is configured with different icon, status, message,
and actions. Offline means Android connectivity was lost; it does not promise
offline maps/routing. Route Error permits retry, change destination, or clear.

## 19. Home, Work, Fuel, and Recent Destinations

`DemoDestinations.kt` contains verified default values used only when the
preference key does not exist:

| Shortcut | Name/address | Coordinates | Source identity |
|---|---|---|---|
| Home | Sheikh Zayed, Giza, 12588, Egypt | `30.0483470, 30.9832235` | Nominatim/OSM node `330194927` |
| Work | Valeo, F22 Side Cairo Alexandria Desert Road, Sheikh Zayed, Giza 15311, Egypt | `30.0787385, 31.0179107` | verified OSM way `648005400` |

`initializeDemoDefaultsAndRetireLocalTheme` seeds defaults only for absent
keys. Long-press Home or Work enters the normal real search assignment flow;
a saved user value is never replaced later. A normal Home/Work tap starts the
real route flow immediately.

Fuel opens Search and starts a real Fuel Overpass search. Recent opens Search
and lists actual selected destinations. `addRecent` puts the latest at the
front, removes an existing matching ID, and retains at most six. Cold start
returns to Home rather than a temporary loading/error state.

## 20. Persistence

`NavigationPreferences` uses SharedPreferences file
`hypernova_navigation_preferences`.

| Key | Stored data | Why |
|---|---|---|
| `guidance_muted` | Boolean | UI mute-indicator preference |
| `home_destination` | serialized `Place` | user/default Home |
| `work_destination` | serialized `Place` | user/default Work |
| `recent_destinations` | JSON array of Places | actual history, maximum six |
| `last_safe_screen` | Home or Search name | safe startup context |
| legacy `theme` | removed on initialization | retires old app-local theme choice |

Places/routes in instance state use `NavigationJson`. Loading, cancelled,
offline, and route-error situations are intentionally not permanent startup
states. Backup/transfer only includes this preferences XML file.

## 21. Dark and Light Mode

Navigation follows Android, not an internal app theme setting:

```kotlin
AppCompatDelegate.setDefaultNightMode(
    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
)
```

The HyperNova Launcher/system changes `uiMode`; `SystemThemeResolver` reads
the current configuration. Resource names are shared between
`values/colors.xml` and `values-night/colors.xml`, so layouts automatically
receive intentional light/dark values. Themes in `values/themes.xml` and
`values-night/themes.xml` adjust system bar icon contrast too. Map style is
reloaded to Dark or Positron accordingly, with Liberty fallback.

Android normally recreates the Activity during a theme configuration change.
The Activity snapshot restores settled results/destination/route data, while
an in-flight search becomes safe Search and route calculation becomes Results.
There is no local Navigation theme toggle or persisted night-mode selection.

Test manually through the Launcher or:

```bash
adb -s 0.0.0.0:6520 shell cmd uimode night yes
adb -s 0.0.0.0:6520 shell cmd uimode night no
```

## 22. Error Handling

| Problem | Classification / UI | Recovery |
|---|---|---|
| No validated network | `OFFLINE` | Retry/return to safe screen when network returns |
| Nominatim timeout/network | inline Search failure | Retry text search |
| Overpass timeout/429/retryable 5xx | bounded retry/fallback, then inline provider error | Retry or Cancel |
| Overpass malformed/permanent client failure | provider error without blind retry | Retry after user action |
| Completed zero category results | no-results message after 50 km | Retry/Back |
| Wider radius fails after valid data | real partial Results | select result or retry wider flow |
| OSRM no route/failure | `ROUTE_ERROR` | Try Again, Change Destination, Clear Route |
| Cancellation | generation invalidated | safe Search/Results/Home state |
| Stale callback | ignored and logged | no UI overwrite |
| Map style failure | Liberty fallback, then map error card | Retry map load |
| Activity recreation | serialized settled state | render restored safe state |

## 23. Debug and Special-State Testing

In a debug APK, long-press the `HyperNova Navigation` title to open the
12-state selector. It is unavailable in a non-debuggable build. Results,
calculation, preview, active, and overview require corresponding real results
or a real route; debug controls do not fabricate provider data.

An intent extra can select a presentation state:

```bash
adb -s 0.0.0.0:6520 shell am start --user 10 \
  -n com.hypernova.navigation/.MainActivity \
  --es debug_state REROUTING
```

Accepted names are the `NavigationScreen` enum names except `SEARCHING` is
not listed in the visible selector. Debug Rerouting/Arrived are visual/future
states only; no movement is simulated.

## 24. Cuttlefish Environment

Fresh verification on 2026-07-24 found:

| Item | Value |
|---|---|
| ADB serial | `0.0.0.0:6520` |
| Product | `hypernova_cockpit_x86_64` |
| Model | HyperNova Cockpit |
| Device | `trout_x86_64` |
| Android version | 14 |
| Current Android user | 10 |
| Boot state | `1` |
| Display | 1080 × 1920 |
| Network | validated Ethernet |

The host also had an unauthorized USB device, so commands must use `-s
0.0.0.0:6520` to avoid the “more than one device” error.

```bash
cd HyperNovaNavigation
./gradlew assembleDebug
adb -s 0.0.0.0:6520 install --user 10 -r -t \
  app/build/outputs/apk/debug/app-debug.apk
adb -s 0.0.0.0:6520 shell am force-stop --user 10 com.hypernova.navigation
adb -s 0.0.0.0:6520 shell am start --user 10 -W \
  -n com.hypernova.navigation/.MainActivity
```

## 25. Build, Test, and Lint

Current verification commands, run from `HyperNovaNavigation/` on
2026-07-24:

```bash
./gradlew assembleDebug
./gradlew test
./gradlew lint
```

| Check | Result |
|---|---|
| `assembleDebug` | `BUILD SUCCESSFUL` in 11 seconds; 39 tasks |
| `test` | `BUILD SUCCESSFUL` in 8 seconds; 34 tests, 0 failures/errors |
| `lint` | completed; 0 errors, 66 warnings |

Lint reports are under `app/build/reports/lint-results-debug.{html,txt,xml}`.
The warnings are non-blocking; this guide does not hide them or alter product
code to change warning counts. `README_CHANGES.md` records an earlier
validation snapshot of 23 tests and 46 warnings. That is historical evidence,
not the current result: this fresh run measured 34 tests and 66 warnings with
zero errors.

## 26. Existing Unit Tests

| Test file | What it protects |
|---|---|
| `DemoDestinationsTest.kt` | defaults are used only when values are absent; user values win |
| `NavigationFormattersTest.kt` | ETA, 12-hour output, distance format, Haversine zero case |
| `NavigationJsonTest.kt` | enriched Place survives snapshot serialization |
| `NavigationPreferenceContractTest.kt` | local theme key is legacy, not current persistence |
| `NavigationStateMachineTest.kt` | normal and invalid transitions, operational interruption |
| `NearbyCategoryTest.kt` | exact five categories and OSM selectors |
| `NearbySearchPolicyTest.kt` | radii, stopping, cap/sorting, partial result, cache policy |
| `OverpassClientTest.kt` | node/way/relation parsing, malformed data, dedupe, address, retry policy |
| `PlaceModelTest.kt` | Place display/address fallback behavior |
| `ProductResourceContractTest.kt` | product strings/resources reflect final category/writing contract |
| `RequestGenerationGateTest.kt` | generation advancement and stale rejection |
| `SystemThemeResolverTest.kt` | Android night-mask interpretation |
| `ExampleInstrumentedTest.kt` | minimal on-device package-name check |

The current automated suite is mainly local unit coverage. It does not replace
full Espresso/UI automation for map/network/keyboard behavior, so Cuttlefish
manual verification remains important.

## 27. End-to-End Test Scenarios

Use this practical checklist:

- Cold-start Home: map loads, ITI start status, Home/Work/Fuel/Recent visible.
- Text: search `Cairo University`, select a result, calculate a route.
- Categories: run Parking, Fuel, Food, Hospital, Shopping; see immediate
  loading and terminal Results/No Results/Provider Error/Cancelled state.
- Select a category POI and confirm real OSRM route line/metrics.
- Preview and Overview: start route, inspect first step and scroll maneuvers.
- Home and Work: route to their saved/default real destinations.
- Recents: select a place, relaunch, verify it persists.
- Theme: change to dark/light, verify cards, dialogs, map style, and route
  survive recreation.
- Errors: disconnect network or use debug Offline/Route Error; verify Retry,
  Back, Cancel, and Clear actions.
- Reliability: rapid same/different category taps, Back during loading,
  force-stop/relaunch, and provider Retry.

## 28. What Is Real and What Is Not

| Feature | Current status | Data source | Notes |
|---|---|---|---|
| Map | Real | MapLibre + OpenFreeMap | Online style/tiles |
| Text search | Real | Nominatim | Egypt-filtered/bias |
| Nearby categories | Real | Overpass + OSM | Five visible categories |
| Markers | Real | returned coordinates | GeoJSON layers |
| Route / geometry | Real | OSRM | Driving profile |
| Route distance/duration | Real | OSRM | Not pre-route proximity |
| Alternatives | Real if supplied | OSRM | Hidden when absent |
| Maneuvers | Real | OSRM steps | No automatic progression |
| Home/Work | Real saved/default places | OSM/Nominatim verification | User can replace |
| Recents | Real user selections | Local storage | No seeded fake recents |
| Light/dark | Real system following | Android uiMode | Launcher/system authority |
| Ratings/reviews | Not implemented | — | No fake values |
| Traffic | Not implemented | — | No fake status |
| Live GPS/movement | Not implemented | — | ITI preview model |
| Auto rerouting/arrival | Not implemented | — | debug/future presentation only |
| Spoken guidance | Not implemented | — | mute is UI preference only |
| Offline maps/routing | Not implemented | — | cached tiles may happen, no promise |
| AIDL / AI | Not implemented | — | future boundary |

## 29. Current Limitations

- The ITI Smart Village start point is configured internally; there is no
  live device/vehicle location.
- No vehicle movement, automatic maneuver progress, real arrival, or automatic
  rerouting occurs.
- New searches, routes, and map styling need public online providers.
- Public Nominatim, Overpass, OSRM, and OpenFreeMap can throttle, timeout, or
  return temporary provider failures.
- OpenStreetMap POI completeness depends on community mapping.
- There are no ratings, reviews, traffic, downloadable offline maps, offline
  routing engine, spoken guidance, AIDL, NOVA AI, or Raspberry Pi protocol.

## 30. Future AIDL and AI Integration Boundary

Future architecture can look conceptually like:

```text
AI Node -> NOVA Android app -> future AIDL Navigation service
                                  |
                                  v
                       NavigationRepository + domain models
                                  |
                                  v
                 Nominatim / Overpass / OSRM and preferences

Android Navigation UI -> NavigationMapController -> MapLibre
```

An AIDL adapter should call stable domain/repository operations such as text
search, nearby category search, route calculation, and saved destinations.
It should not tightly couple IPC to MapLibre sources/layers, provider JSON
parsing, HTTP clients, or SharedPreferences. No final protocol is defined
here, and none is implemented in this application.

## 31. Common Developer Workflows

```bash
# From HyperNovaNavigation/
./gradlew assembleDebug
./gradlew test
./gradlew lint

# Discover an active user/device first
adb devices -l
adb -s 0.0.0.0:6520 shell am get-current-user

# Install, launch, and public action launch
adb -s 0.0.0.0:6520 install --user 10 -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s 0.0.0.0:6520 shell am force-stop --user 10 com.hypernova.navigation
adb -s 0.0.0.0:6520 shell am start --user 10 -n com.hypernova.navigation/.MainActivity
adb -s 0.0.0.0:6520 shell am start --user 10 -a com.hypernova.navigation.action.OPEN

# Diagnostics and screenshot
adb -s 0.0.0.0:6520 logcat | rg 'HyperNova|HN-'
adb -s 0.0.0.0:6520 exec-out screencap -p > screenshot.png
adb -s 0.0.0.0:6520 shell cmd uimode night yes
adb -s 0.0.0.0:6520 shell cmd uimode night no

# Warning: this deletes the app's Home/Work/recents preferences for this user
adb -s 0.0.0.0:6520 shell pm clear --user 10 com.hypernova.navigation
```

## 32. Troubleshooting

| Issue | Probable cause | Diagnostic | Safe response |
|---|---|---|---|
| Map does not load | style/network service failure | `logcat`, map error card | Retry; controller tries Liberty once |
| Text search empty/error | query/provider/network | `HyperNovaNavigationRepository` logs | retry with a specific Egyptian name |
| Category timeout | busy public Overpass | `HyperNovaOverpass` logs | wait for bounded retry/fallback, then Retry |
| Category appears unresponsive | stale build/UI or active duplicate | `HyperNovaCategorySearch` logs | wait for visible terminal state; use different category or Back |
| No route | OSRM has no usable driving route | Route Error message/log | Change Destination or retry |
| Theme mismatch | app not recreated/system mode not changed | `cmd uimode night yes/no` | relaunch; verify launcher system mode |
| Wrong Android user | multiple Android users | `am get-current-user` | install/launch with detected user |
| APK installs but launch fails | wrong serial/package/component | `adb devices -l`, `am start -W` | use explicit `-s`, package/component |
| Duplicate map layers | custom map change bypassed checks | logcat MapLibre errors | use `addSourceIfMissing` / layer existence checks |
| Keyboard missing | focus/IME/layout issue | view hierarchy/logcat | enter Search, refocus input; `adjustResize` is declared |
| Home/Work unexpected | saved user value intentionally wins | app data/preferences | long-press shortcut to set real result; clear data only if intended |
| Screenshot looks old | captured too early or wrong device | timestamp, `mCurrentFocus` | wait after launch and use explicit serial |

## 33. File-by-File Reference

| File | Responsibility | Called by | Calls/uses | Notes |
|---|---|---|---|---|
| `MainActivity.kt` | renderer, lifecycle, event coordination | Android | repository, preferences, map, panels | large coordinator, not provider parser |
| `data/nominatim/NominatimClient.kt` | text GET request/parser | repository | Nominatim | typed Place/failures |
| `data/overpass/OverpassClient.kt` | category POST/query/parser/retry policy | repository | Overpass endpoints | node/way/relation support |
| `data/osrm/OsrmClient.kt` | driving route request/parser | repository | OSRM | longitude-first URL |
| `data/persistence/NavigationPreferences.kt` | settings/destinations/recents | activity | SharedPreferences/JSON | max six recents |
| `NavigationPreferenceContract.kt` | preference key names | preferences/tests | — | legacy theme key retained only for removal |
| `domain/model/NavigationModels.kt` | shared data, JSON, distance | all layers | `org.json` | provider-neutral core |
| `DemoDestinations.kt` | verified Home/Work defaults | preferences/tests | models | does not overwrite user data |
| `NavigationRepository.kt` | execution, cache, generation, callbacks | activity | all provider clients | provider/UI boundary |
| `NearbySearchPolicy.kt` | radii, cap, partial accumulator | repository/tests | models | 5/10/25/50 km |
| `RequestGenerationGate.kt` | stale-callback guard | repository/tests | atomic integer | separate gates per work type |
| `ui/NavigationFormatters.kt` | metric/ETA/maneuver display | activity/tests | models | no provider access |
| `ui/SystemThemeResolver.kt` | night-mask check | activity/map/tests | Android Configuration | follows system |
| `ui/state/NavigationStateMachine.kt` | legal transitions | activity/tests | screen enum | debug override supported |
| `ui/map/NavigationMapController.kt` | GeoJSON map scene/styles/camera | activity | MapLibre/OpenFreeMap | owns map layer IDs |
| `res/layout/activity_main.xml` | root MapView, panels, controls | activity | top bar/state host | permanent map shell |
| `res/layout/panel_*.xml` | state-specific cards | activity | ViewBinding | dynamically inflated |
| `res/layout/item_*.xml` | result/recent/maneuver rows | activity | ViewBinding | list rows |
| `res/layout/view_top_bar.xml` | identity/status/back bar | activity | strings/icons | debug long-press target |
| `res/layout/view_route_metric.xml` | reusable metric cell | route panels | ViewBinding | distance/duration/ETA |
| `res/drawable/ic_*.xml` | vector UI icons | layouts/activity | Android drawables | no emoji production icons |
| `res/values/strings.xml` | user-visible English text | all UI | resources | current wording contract |
| `res/values/colors.xml`, `values-night/colors.xml` | light/dark palette | views/map | resources | same semantic names |
| `res/values/themes.xml`, `values-night/themes.xml` | DayNight theme/system bars | manifest | Material theme | no local switch |
| `res/values/styles.xml` | shared button/card styles | layouts | Material widgets | touch-size styling |
| `res/xml/*backup*` | restrict backup/transfer | manifest | SharedPreferences | only nav preferences |
| `AndroidManifest.xml` | permissions/activity/actions | Android | resources | no HOME filter |
| `app/build.gradle.kts` | module plugins/SDK/dependencies | Gradle | catalog | ViewBinding enabled |
| `gradle/libs.versions.toml` | versions/aliases | Gradle script | dependencies | includes MapLibre |
| `app/src/test/...Test.kt` | local behavior contracts | Gradle test | production code | table in section 26 |
| `app/src/androidTest/...` | basic instrumentation smoke test | connected test | app context | not part of `./gradlew test` |
| `README*.md`, `SCREEN_STATE_MATRIX.md` | existing docs/history | engineers | current source | historical claims need source check |
| `artifacts/` | screenshot evidence | engineers | Cuttlefish | not an app dependency |

## 34. Complete Data Flows

### Text search flow

```text
Search input -> MainActivity.performTextSearch
 -> NavigationRepository.searchTextPlace
 -> NominatimClient.search -> typed Place list
 -> main Handler + generation check -> RESULTS + MapLibre markers
```

### Category search flow

```text
Fuel/Food/etc. -> MainActivity.performNearbyCategory
 -> repository generation/cache/progress
 -> Overpass POST at 5/10/25/50 km
 -> typed, deduplicated, distance-sorted Places
 -> RESULTS or partial Results / No Results / Provider Error / Cancelled
```

### Destination selection flow

```text
tap result -> selectedResultId + selected marker
 -> Select Destination -> addRecent or save Home/Work
 -> beginRouteCalculation
```

### Route calculation flow

```text
destination + ITI GeoPoint -> repository route generation
 -> OsrmClient GET -> RoutePlan alternatives + steps
 -> ROUTE_PREVIEW -> map route casing/line + metrics/ETA
```

### Theme-change flow

```text
Launcher/system changes uiMode -> Android recreates Activity
 -> saved NavigationUiState restored safely
 -> SystemThemeResolver -> correct resource palette/map style
 -> NavigationMapController reapplies scene
```

### Persistence flow

```text
selected real place -> NavigationPreferences.addRecent
Home/Work assignment -> serialized Place
Activity recreation -> Bundle NavigationJson
cold launch -> SharedPreferences defaults + HOME screen
```

### Debug-state flow

```text
debug title long-press or debug_state extra
 -> verify debuggable + required real data
 -> cancel active work -> state-machine debug override -> render panel
```

## 35. Questions & Answers

1. **Why are MapLibre and OpenFreeMap separate?** MapLibre renders maps; OpenFreeMap supplies public styles/tiles.
2. **Why not use Nominatim for categories?** It geocodes names/addresses, while Overpass queries OSM tags around coordinates.
3. **Why is Overpass sometimes slow?** It is a public shared OSM query service and can be busy or throttled.
4. **Straight-line versus driving distance?** Straight-line is Haversine proximity; driving distance is real OSRM road-route length.
5. **Why does OSRM use longitude,latitude?** Its HTTP route API follows GeoJSON coordinate order.
6. **Why generation IDs?** They prevent an older response from replacing newer UI data.
7. **Why GeoJSON route lines?** MapLibre sources/layers efficiently render and restore geometry.
8. **Why does the Activity recreate on theme change?** Android applies configuration-specific resources through recreation.
9. **Where are Home and Work stored?** The navigation SharedPreferences JSON values.
10. **Why is there no rating?** No ratings provider is in scope, and the app does not invent values.
11. **Why is there no live movement?** This standalone demo has no live GPS/vehicle source.
12. **What will AIDL add later?** A stable IPC boundary for another Android component, not a replacement for provider/map code.
13. **Can it run without an AI node?** Yes; that is the current design.
14. **What happens offline?** Online search/routing stop and a truthful Offline state appears; no offline routing is promised.
15. **How does Launcher open it?** With `com.hypernova.navigation.action.OPEN`.
16. **Why detect the Android user before install?** AAOS can have multiple users; installing to the wrong user makes the app unavailable to the active driver.
17. **What is debug-only?** Rerouting/Arrived presentations and the selector; real route data is still required where relevant.
18. **Provider failure versus no results?** Failure means service could not complete; no results means a completed search found none by 50 km.
19. **How are stale callbacks prevented?** Repository generation gates validate each main-thread delivery.
20. **Where would a future nearby-rating provider go?** Behind a new repository/client boundary, merged into domain models without changing route/map rendering.

## 36. Glossary

| Term | Simple meaning |
|---|---|
| AOSP | Android Open Source Project platform code |
| AAOS | Android Automotive OS |
| Activity | Android screen/controller component |
| ViewBinding | Generated typed references to XML views |
| MapView | Android view containing the interactive map |
| MapLibre | Open-source map-rendering SDK |
| OpenFreeMap | Public map style/tile service |
| OpenStreetMap | Community-maintained geographic data |
| Nominatim | OpenStreetMap name/address geocoder |
| Overpass | API for querying OSM tags/geographic data |
| OSRM | Open Source Routing Machine service |
| Geocoding | Turning a name/address into coordinates |
| POI | Point of interest, such as a café or fuel station |
| GeoJSON | Common JSON geographic geometry format |
| Source | MapLibre data container |
| Layer | MapLibre drawing rule for a source |
| Marker | Visible map point representation |
| Haversine distance | Great-circle straight-line geographic distance |
| Driving distance | Length of a route on roads |
| Route geometry | Ordered coordinates that draw a route |
| Maneuver | One navigation instruction/turn |
| State machine | Rules for allowed screen transitions |
| Repository | Class that coordinates data operations for UI |
| Domain model | Provider-neutral application data object |
| SharedPreferences | Android small key/value local storage |
| uiMode | Android configuration including light/dark mode |
| Cuttlefish | Android virtual device/emulator platform |
| ADB | Android Debug Bridge command-line tool |
| AIDL | Android Interface Definition Language for IPC |
| Binder | Android's inter-process communication mechanism |
| stale callback | Late response that no longer belongs to current UI work |
| request generation | Increasing ID used to identify current work |

## 37. Final Summary

HyperNova Navigation currently delivers a polished standalone Automotive
navigation experience built from real public map, search, POI, and routing
providers. The core pieces work together through typed domain models,
repository-managed asynchronous requests, a validated UI state machine,
persisted user destinations, and a GeoJSON-based MapLibre scene.

The next logical phase is to define a future AIDL/NOVA adapter around the
existing domain and repository operations, without coupling that work to the
map renderer or provider parsers. This guide was verified on **2026-07-24**
against source at commit `e7bf8fb` in a deliberately dirty, uncommitted
working tree. Application behavior was not intentionally changed while
creating this documentation.
