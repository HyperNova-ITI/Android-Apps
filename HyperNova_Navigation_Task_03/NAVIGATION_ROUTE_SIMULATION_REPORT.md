# HyperNova Navigation Route Simulation Report

Date: 2026-07-31  
Target: `HyperNova_Navigation_Task_03/HyperNovaNavigation`  
Reference reviewed read-only: `Android-feature-navigation/MotorGuardApp`

## Result

HyperNova Navigation now has an application-scoped route-driving simulator. When the authoritative `NavigationSession` becomes `ACTIVE`, whether from the Navigation UI or AIDL `setDestination()`, the simulator follows the exact selected OSRM route geometry. It emits smoothly interpolated vehicle positions, smoothed road bearings, speed, traveled distance, remaining distance, and progress. MapLibre renders a rotating cyan vehicle arrow, a quantized passed-route line, and a throttled follow camera. Arrival and cancellation update the same repository/session state used by UI and AIDL.

No AIDL contract, search provider, nearby behavior, OSRM behavior, Gradle dependency, manifest, or other HyperNova application was changed.

## Architecture Before

- `HyperNovaNavigationApplication` owned one application-scoped `NavigationRepository`.
- Both `MainActivity` and `NavigationCommandService` already used that repository.
- OSRM returned `RoutePlan` / selected `RouteAlternative`, and `NavigationSession` held `IDLE`, `CALCULATING`, `ROUTE_PREVIEW`, `ACTIVE`, or `ERROR`.
- MapLibre rendered route alternatives, the selected route, origin, destination, and search markers.
- Active navigation had no changing vehicle position, no arrived backend state, and no follow camera.

## Architecture After

```text
UI Start Route                         NOVA / AIDL setDestination()
      |                                          |
      +-------------------+----------------------+
                          v
               NavigationRepository
                          |
                         OSRM
                          |
              selected RouteAlternative
                          |
               NavigationSession ACTIVE
                          |
                          v
                LocationSource interface
                          |
                          v
              SimulatedLocationSource
                          |
               VehiclePosition @ 10 Hz
                 /                    \
                v                      v
       NavigationSession         Route progress
                |
                v
           MainActivity
                |
                v
      NavigationMapController
          |              |
          v              v
   MapLibre arrow   Follow camera
```

`SimulatedLocationSource` is selected explicitly in `HyperNovaNavigationApplication`. A future NXP `GnssLocationSource` can implement the same `LocationSource` interface and be supplied at that single application composition point.

## Reference Concepts Adapted

The reference was studied rather than copied. The following concepts were adapted to HyperNova's callback/executor architecture:

- Haversine route-segment distance and cumulative route distance.
- Linear interpolation within each OSRM geometry segment.
- Bearing from the current route segment.
- Shortest angular delta and 0.18-per-tick bearing smoothing, including north wrap-around.
- A central 100 ms tick and 8x desk/demo speed factor.
- Base speed derived from OSRM distance/duration, with fallback only when route timing is unavailable.
- Arrival slowdown and an exact final destination emission at zero speed.
- One replaceable simulation controller so a new route cancels the previous route loop.
- Persistent MapLibre sources/layers, with only the vehicle source, rotation, passed geometry, and camera updated while driving.
- A generated arrow bitmap with collision disabled and map-aligned bearing rotation.
- A 70% screen-height follow anchor and bearing-oriented camera.

Unlike the Compose/coroutine reference, HyperNova's implementation uses its existing callback/executor concurrency style and does not add a dependency or a second navigation engine.

## Simulation Lifecycle

1. UI `activateCurrentRoute()` or AIDL `startNavigation()` transitions the shared session to `ACTIVE` only after OSRM supplies a real `RoutePlan`.
2. The repository passes `routePlan.selected` to the one application-scoped `LocationSource`.
3. `RouteSimulationController` replaces any previous engine and emits the route origin.
4. `SimulatedLocationSource` advances every 100 ms on one managed scheduled executor.
5. `RouteSimulationEngine` advances by base speed × elapsed time × 8x, interpolates within the current segment, and emits `VehiclePosition`.
6. Repository updates are posted to the main looper and written into `NavigationSessionState`; stale positions from a replaced source are rejected.
7. At the end, the exact last OSRM point is emitted with speed zero, progress 1.0, and remaining distance zero. The authoritative session becomes `ARRIVED` and AIDL state mapping reports `STATE_ARRIVED`.
8. Cancellation clears the location source before setting the session to `IDLE`. Generation checks and future cancellation prevent further accepted updates.

Activity recreation does not create another simulator because the source belongs to the application-scoped repository. Opening Navigation after an AIDL route starts reads the current shared session and vehicle position.

## Route Math and Movement

Pure Android-independent utilities implement:

- `distanceMeters(a, b)`
- `bearingDegrees(a, b)`
- `lerp(a, b, fraction)`
- `cumulativeDistances(points)`
- `pointAlong(points, cumulative, traveledMeters)`
- `angleDelta(current, target)`

`pointAlong` clamps traveled distance, finds the containing route segment, and interpolates latitude/longitude between its endpoints. The vehicle therefore advances continuously along OSRM geometry rather than jumping between shape points. Empty, single-point, duplicate-distance, before-start, and beyond-end inputs are handled safely.

`VehiclePosition` contains geographic point, smoothed bearing, displayed base speed, traveled meters, remaining meters, progress fraction, current route segment, and arrival state. The 8x factor accelerates simulation time; it does not falsely multiply the displayed driving speed.

## MapLibre Rendering and Camera

`NavigationMapController` now installs these once per style load:

- vehicle GeoJSON source and topmost symbol layer;
- generated 58 dp cyan arrow bitmap with a dark outline and translucent halo;
- passed-route GeoJSON source and line layer.

Each position-only update changes the vehicle GeoJSON point and symbol `iconRotate`; it does not rebuild the style, routes, panels, or all layers. Passed-route geometry is updated in 25 m buckets to limit GeoJSON churn.

During `ROUTE_PREVIEW`, the existing route overview camera remains unchanged. During `ACTIVE`, camera updates are throttled to 250 ms with 220 ms easing, use the smoothed vehicle bearing and a 42° tilt, and add top padding so the vehicle sits near 70% of screen height. `ROUTE_OVERVIEW` continues showing bounds and suppresses follow until the user returns to active guidance.

## Files Created

- `app/src/main/java/com/hypernova/navigation/domain/model/VehiclePosition.kt`
- `app/src/main/java/com/hypernova/navigation/domain/simulation/LocationSource.kt`
- `app/src/main/java/com/hypernova/navigation/domain/simulation/RouteSimulationConfig.kt`
- `app/src/main/java/com/hypernova/navigation/domain/simulation/RouteSimulationMath.kt`
- `app/src/main/java/com/hypernova/navigation/domain/simulation/RouteSimulationEngine.kt`
- `app/src/main/java/com/hypernova/navigation/domain/simulation/RouteSimulationController.kt`
- `app/src/main/java/com/hypernova/navigation/domain/simulation/SimulatedLocationSource.kt`
- `app/src/main/java/com/hypernova/navigation/ui/map/VehicleArrowBitmap.kt`
- `app/src/test/java/com/hypernova/navigation/domain/simulation/RouteSimulationMathTest.kt`
- `app/src/test/java/com/hypernova/navigation/domain/simulation/RouteSimulationEngineTest.kt`
- `app/src/test/java/com/hypernova/navigation/domain/simulation/RouteSimulationControllerTest.kt`
- `../NAVIGATION_ROUTE_SIMULATION_REPORT.md` (this report)

## Files Modified

- `app/src/main/java/com/hypernova/navigation/HyperNovaNavigationApplication.kt`
- `app/src/main/java/com/hypernova/navigation/MainActivity.kt`
- `app/src/main/java/com/hypernova/navigation/domain/model/NavigationBackendModels.kt`
- `app/src/main/java/com/hypernova/navigation/domain/model/NavigationModels.kt`
- `app/src/main/java/com/hypernova/navigation/domain/repository/NavigationRepository.kt`
- `app/src/main/java/com/hypernova/navigation/domain/repository/NavigationSession.kt`
- `app/src/main/java/com/hypernova/navigation/service/NavigationResultFactory.kt`
- `app/src/main/java/com/hypernova/navigation/ui/map/NavigationMapController.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/com/hypernova/navigation/NavigationResultFactoryTest.kt`
- `app/src/test/java/com/hypernova/navigation/NavigationSessionTest.kt`

## Tests

New coverage includes:

- increasing cumulative distances;
- first-point and last-point interpolation;
- a valid intermediate interpolated point;
- approximate east bearing;
- shortest angular delta across 359° to 1°;
- end-of-route clamping;
- empty and single-point safety;
- simulation origin, intermediate movement, exact arrival, progress, and zero arrival speed;
- controller start, cancel, arrival stop, and route replacement;
- authoritative session `ACTIVE` to `ARRIVED` transition and cancellation;
- AIDL `ARRIVED` contract-state mapping.

All existing tests remain enabled. Result: **73 tests, 0 failures, 0 errors, 0 skipped**.

## Build and Runtime Validation

Final command:

```bash
./gradlew \
  :hypernova-contracts:assembleDebug \
  :app:testDebugUnitTest \
  :app:assembleDebug
```

Result: **BUILD SUCCESSFUL**.  
APK: `app/build/outputs/apk/debug/app-debug.apk`

ADB target `0.0.0.0:6520`, active AAOS user 10:

- `adb install -r` succeeded; no uninstall or data clear was performed.
- Navigation launched and remained the resumed activity without a runtime crash.
- The existing AIDL contract client connected and passed API version 1.
- Real text search returned a Navigation-issued destination ID.
- AIDL `setDestination()` returned `CONFIRMED / ACTIVE` with real OSRM values: 1,435 m and 225 s.
- Simulation logged 86 route points, approximately 1,436 m of route geometry, and speed factor 8x.
- Opening Navigation displayed the same AIDL-started active route.
- The simulator logged arrival and the UI changed to `ARRIVED` / `You have arrived`.
- A second AIDL route was started and cancelled; callback returned `CONFIRMED / IDLE`, and the simulator logged immediate cancellation with no arrival from that run.
- The final rebuilt APK, including the updated active-simulation label, was installed successfully.

## Known Limitations

- This is an internal desk/demo source, not Android mock GPS and not real GNSS.
- Simulation timing and the 8x factor are centralized in code; no user-facing speed control is included.
- Speed slows near arrival. Maneuver-specific slowdown was not added because HyperNova's current `RouteStep` does not expose OSRM geometry indices; inventing that association would be unreliable.
- Progress data and passed-route coloring are implemented, but the existing active panel still displays the route's original total distance/duration and first instruction rather than live maneuver/remaining metrics.
- Follow is automatic while active. A manual follow/recenter toggle was intentionally not added because it would require unrelated UI redesign.

## Out of Scope / Future NXP Work

A future NXP integration should implement `LocationSource` as a real `GnssLocationSource` and select it in `HyperNovaNavigationApplication`. It should emit the same `VehiclePosition` model or adapt real fixes into it, allowing repository/session, AIDL, UI, progress, arrow rendering, and camera follow to remain unchanged.

No NOVA, Launcher, Media, Settings, Climate, Contracts, or other sibling-project source was modified.
