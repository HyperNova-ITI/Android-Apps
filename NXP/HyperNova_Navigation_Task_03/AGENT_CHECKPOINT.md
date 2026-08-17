# HyperNova Navigation — Safe Checkpoint

Date: 2026-07-23  
Workspace: `/home/ayman/ITI/Android-Apps/HyperNova_Navigation_Task_03`

## What has been completed

- Read the current `README.md`, `README_CHANGES.md`,
  `SCREEN_STATE_MATRIX.md`, implementation files, resources, tests, and
  project status before editing.
- Built, installed, launched, and visually inspected the pre-change
  baseline on Cuttlefish.
- Reproduced an application-side category issue: every category button was
  `enabled=false` during `SEARCHING`, so repeat taps and category switching
  did not reach their listeners.
- Confirmed real external Overpass failures on Cuttlefish, including:
  - HTTP 504 from the primary endpoint;
  - primary endpoint socket timeouts;
  - fallback endpoint timeout.
- Removed the obsolete Charging product category from:
  - `NearbyCategory`;
  - Overpass mapping;
  - Home and Search layouts;
  - activity dispatch;
  - strings and accessibility resources;
  - unit tests;
  - the `ic_charging.xml` vector.
- Replaced the Home Charging shortcut with the real Fuel Overpass flow.
- Changed the visible category set to exactly Parking, Fuel, Food, Hospital,
  and Shopping.
- Removed the prohibited start-point phrase from app resources and the main
  README/state-matrix documentation. The latest repository search before
  this checkpoint returned no match for
  `fixed[[:space:]_-]*origin`.
- Added bounded Overpass reliability behavior:
  - primary attempt 1;
  - short backoff after a retryable failure;
  - primary attempt 2;
  - one fallback attempt;
  - retries only for timeout, connection failure, HTTP 429, and retryable
    HTTP 5xx;
  - permanent 4xx and malformed responses are not retried;
  - raw provider bodies are not exposed to the user.
- Added safe endpoint/attempt/radius/status/elapsed/parse-count logging.
- Split text, category, and route request generation gates.
- Added stale callback rejection and typed cancellation.
- Kept category controls enabled during loading.
- Added duplicate active-category suppression and different-category
  supersession.
- Added visible category loading, partial-result, no-results, provider-error,
  Retry, and Cancel handling.
- Corrected connectivity classification so an endpoint failure does not
  become device Offline while Android connectivity remains validated.
- Preserved real smaller-radius results when a wider request fails.
- Prevented provider failures/timeouts from being cached as successful empty
  results.
- Added/updated unit tests for the final category set, tag mappings, retry
  status classification, endpoint attempt order, malformed remarks,
  progressive radii, partial result preservation, zero results, cache
  policy, generation rejection, product resources, and prohibited wording.
- Updated `README.md`, `README_CHANGES.md`, and
  `SCREEN_STATE_MATRIX.md` for the new reliability behavior and category
  set.

## What is currently in progress

- The repeated five-run-per-category Cuttlefish matrix is incomplete.
- Parking was validated on the latest installed APK:
  - run 1: 5 km, primary attempt 1 timed out, primary attempt 2 returned
    HTTP 200, 37 parsed / 30 displayed, Results;
  - runs 2–4: 5 km memory-cache hits, 30 results, controls remained usable.
- Food run 1 was validated on the latest installed APK:
  - 5 km, primary attempt 1 returned HTTP 200;
  - 16 parsed / 16 displayed;
  - Results in about 5 seconds.
- A command intended to perform Food runs 2–5 was interrupted after
  approximately 3.4 seconds. It may have completed only some initial taps.
  Those partial actions must not be counted until the current device UI and
  logs are inspected after resuming.
- Earlier corrected-APK validation (same provider behavior, before the final
  bounded-log/cancellation-log cleanup) confirmed:
  - Fuel: primary HTTP 504, then primary retry HTTP 200, 10 real results;
  - Shopping: two primary HTTP 504 responses and a fallback timeout produced
    a visible Provider Error with Retry and Cancel;
  - Shopping Retry later returned HTTP 200 and 30 displayed results;
  - changing Hospital to Shopping invalidated the Hospital generation and
    ignored its stale callback.

## What remains

- Inspect the Cuttlefish state and logs left by the interrupted Food command.
- Complete five documented runs for each of Parking, Fuel, Food, Hospital,
  and Shopping.
- Complete explicit normal tap, rapid double tap, Back/retry,
  category-switch, theme-switch, force-stop/relaunch, provider Retry, and
  return-from-Results scenarios.
- Add an explicit `final=RESULTS` log in the terminal memory-cache branch;
  that branch currently logs `cache=hit terminal=true results=N` and reaches
  Results correctly, but does not call the common final-outcome logger.
- Re-run the full unit-test suite after the final small logging changes.
- Run final `./gradlew assembleDebug test lint`.
- Reinstall the final APK.
- Validate Fuel, Food, Shopping, Parking, Hospital, Nominatim, OSRM,
  Home/Sheikh Zayed, Work/Valeo, route preview, route overview, Back,
  cancellation, and Retry on the final APK.
- Validate both `adb shell cmd uimode night yes` and `night no`.
- Capture and visually inspect the remaining required dark/light,
  category, route, Home, Work, retry, and no-results screenshots.
- Inspect final logcat for crashes, ANRs, MapLibre duplication errors, and
  unbounded stack traces.
- Finish documentation with final measured results.
- Create the mandatory root file `AGENT_NAVIGATION_FIX_REPORT.md`.
- Run the final repository-wide wording/resource verification.
- Capture exact final `git status --short` and `git diff --stat`.

## Files created in this focused pass

- `AGENT_CHECKPOINT.md`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/domain/repository/RequestGenerationGate.kt`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/ProductResourceContractTest.kt`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/RequestGenerationGateTest.kt`
- New checkpoint-era screenshots directly under
  `HyperNovaNavigation/artifacts/`, including baseline, Home, Search,
  loading, results, retry, and provider-error captures.

## Files modified in this focused pass

- `README.md`
- `README_CHANGES.md`
- `SCREEN_STATE_MATRIX.md`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/MainActivity.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/data/overpass/OverpassClient.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/domain/model/NavigationModels.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/domain/repository/NavigationRepository.kt`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/domain/repository/NearbySearchPolicy.kt`
- `HyperNovaNavigation/app/src/main/res/layout/panel_home.xml`
- `HyperNovaNavigation/app/src/main/res/layout/panel_results.xml`
- `HyperNovaNavigation/app/src/main/res/layout/panel_search.xml`
- `HyperNovaNavigation/app/src/main/res/layout/view_top_bar.xml`
- `HyperNovaNavigation/app/src/main/res/values/strings.xml`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/NearbyCategoryTest.kt`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/NearbySearchPolicyTest.kt`
- `HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/OverpassClientTest.kt`

Removed/retired in this focused pass:

- `HyperNovaNavigation/app/src/main/res/drawable/ic_charging.xml`

The worktree already contained extensive uncommitted application work before
this focused pass. None of it was reset, cleaned, committed, or discarded.

## Current git status

The exact `git status --short` immediately before creating this checkpoint
is below. `?? AGENT_CHECKPOINT.md` is additionally expected after this file
was created.

```text
 M HyperNovaNavigation/app/build.gradle.kts
 M HyperNovaNavigation/app/src/main/AndroidManifest.xml
 M HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/MainActivity.kt
 M HyperNovaNavigation/app/src/main/res/layout/activity_main.xml
 M HyperNovaNavigation/app/src/main/res/values-night/themes.xml
 M HyperNovaNavigation/app/src/main/res/values/colors.xml
 M HyperNovaNavigation/app/src/main/res/values/strings.xml
 M HyperNovaNavigation/app/src/main/res/values/themes.xml
 M HyperNovaNavigation/app/src/main/res/xml/backup_rules.xml
 M HyperNovaNavigation/app/src/main/res/xml/data_extraction_rules.xml
 D HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/ExampleUnitTest.kt
 M HyperNovaNavigation/gradle/libs.versions.toml
 M README.md
?? AGENT_CHECKPOINT.md
?? HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/data/
?? HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/domain/
?? HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/ui/
?? HyperNovaNavigation/app/src/main/res/drawable/ic_back.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_close.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_error.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_food.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_fuel.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_home.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_hospital.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_location_off.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_maneuver_left.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_maneuver_right.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_maneuver_straight.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_navigation.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_parking.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_place.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_recent.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_refresh.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_route_overview.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_search.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_shopping.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_success.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_volume.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_volume_off.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_warning.xml
?? HyperNovaNavigation/app/src/main/res/drawable/ic_work.xml
?? HyperNovaNavigation/app/src/main/res/layout/item_maneuver.xml
?? HyperNovaNavigation/app/src/main/res/layout/item_place_result.xml
?? HyperNovaNavigation/app/src/main/res/layout/item_recent_destination.xml
?? HyperNovaNavigation/app/src/main/res/layout/panel_active_route.xml
?? HyperNovaNavigation/app/src/main/res/layout/panel_calculating.xml
?? HyperNovaNavigation/app/src/main/res/layout/panel_home.xml
?? HyperNovaNavigation/app/src/main/res/layout/panel_results.xml
?? HyperNovaNavigation/app/src/main/res/layout/panel_route_overview.xml
?? HyperNovaNavigation/app/src/main/res/layout/panel_route_preview.xml
?? HyperNovaNavigation/app/src/main/res/layout/panel_search.xml
?? HyperNovaNavigation/app/src/main/res/layout/panel_special_state.xml
?? HyperNovaNavigation/app/src/main/res/layout/view_route_metric.xml
?? HyperNovaNavigation/app/src/main/res/layout/view_top_bar.xml
?? HyperNovaNavigation/app/src/main/res/values-night/colors.xml
?? HyperNovaNavigation/app/src/main/res/values/styles.xml
?? HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/DemoDestinationsTest.kt
?? HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/NavigationFormattersTest.kt
?? HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/NavigationJsonTest.kt
?? HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/NavigationPreferenceContractTest.kt
?? HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/NavigationStateMachineTest.kt
?? HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/NearbyCategoryTest.kt
?? HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/NearbySearchPolicyTest.kt
?? HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/OverpassClientTest.kt
?? HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/PlaceModelTest.kt
?? HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/ProductResourceContractTest.kt
?? HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/RequestGenerationGateTest.kt
?? HyperNovaNavigation/app/src/test/java/com/hypernova/navigation/SystemThemeResolverTest.kt
?? HyperNovaNavigation/artifacts/
?? README_CHANGES.md
?? SCREEN_STATE_MATRIX.md
```

`git diff --check` produced no output at this checkpoint.

## Build, test, and lint status

- Baseline `./gradlew assembleDebug`: passed.
- Corrected source `./gradlew assembleDebug`: passed.
- Latest corrected-source build before the repeated final run:
  `BUILD SUCCESSFUL in 2s` with 39 tasks.
- `./gradlew testDebugUnitTest`: passed after the main reliability and test
  additions.
- The unit tests have not yet been rerun after the final cancellation/log
  refinements; a final test result must therefore not be claimed yet.
- Lint has not been run for this focused pass.
- An earlier standalone-app pass had lint success, but that is not a
  substitute for the required final focused-pass lint run.

## Cuttlefish test status

- Serial: `0.0.0.0:6520`
- Product: `hypernova_cockpit_x86_64`
- Model: `HyperNova Cockpit`
- Device: `trout_x86_64`
- Android user: `10`
- Boot state: `1` / completed
- Display: `1080x1920`
- Network: validated Ethernet inside Android
- Latest corrected APK: installed successfully for user 10
- Main activity: launched successfully
- Baseline and corrected Home screenshots: captured and inspected
- Corrected Home showed Fuel, no Charging, and `ITI START`
- Loading-state accessibility tree showed all five category buttons enabled
- Real provider Retry state: captured and inspected
- Parking results: captured
- Food results: captured
- Full required screenshot set: not complete
- Full repeated category table: not complete
- Dark/light final pass: not complete
- Nominatim/OSRM/Home/Work regression on the final focused APK: not complete

## Errors currently being investigated

1. Public Overpass instability is real and reproducible:
   primary HTTP 504, primary socket timeout, and fallback timeout.
   The corrected UI handles these visibly, but every category still requires
   its complete repeated test record.
2. The terminal memory-cache path should emit the same explicit
   `final=RESULTS` log as the network-result path. Its UI behavior is already
   correct.
3. The interrupted Food repeat command may have performed a subset of its
   taps. Its state must be inspected, not assumed.
4. The mandatory final lint run and complete final logcat audit have not
   happened.

## Exact next step to execute

After the user explicitly asks to resume:

1. Inspect the current Cuttlefish UI and `HyperNovaCategorySearch` logcat to
   determine exactly how much of the interrupted Food command ran.
2. Do not count ambiguous partial taps.
3. Add the missing common final-outcome log call to the terminal memory-cache
   branch in `NavigationRepository`.
4. Run the focused unit tests before continuing the remaining repeated
   category matrix.

