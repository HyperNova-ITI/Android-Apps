# HyperNova Navigation — Android Studio Layout Preview Fix

## 1. Problem

`activity_main.xml` directly instantiated MapLibre `MapView`. Android Studio Layout Editor lacks the MapLibre runtime/native asset environment, so preview could fail with a null asset stream before drawing the rest of the screen.

## 2. Root Cause

```text
Layout Editor -> XML MapView -> MapLibre runtime asset lookup -> failure
```

This was a design-time custom-view problem, not a routing, OpenFreeMap, or state-rendering problem. Cuttlefish works because `MapLibre.getInstance(this)` runs in the real Android runtime.

## 3. Previous XML Structure

```xml
<org.maplibre.android.maps.MapView
    android:id="@+id/mapView"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

## 4. Final XML Structure

```text
FrameLayout #mapContainer
  ImageView #mapDesignPreview
    runtime: GONE
    design time: tools:src + tools:visibility=visible

scrim, top bar, stateHost, controls, map state card
```

`stateHost` also has a `panel_home` include that is runtime `GONE` and design-time visible through `tools:visibility`. The real renderer removes stateHost children before showing the current application state. No MapLibre `MapView` XML tag remains in `app/src/main/res`.

## 5. Runtime MapView Creation

`MainActivity` now owns `private lateinit var mapView: MapView`. After `MapLibre.getInstance(this)`, binding inflation, and `setContentView`, it creates one MapView, assigns the real map content description, adds it to `binding.mapContainer` with match-parent `FrameLayout.LayoutParams`, calls `mapView.onCreate(savedInstanceState)`, and passes that instance to `NavigationMapController`.

The map is not created in `render()`. `mapDesignPreview` uses only `tools:src` and remains `GONE` at runtime.

## 6. MapView Lifecycle Handling

| Activity callback | Runtime call |
|---|---|
| `onCreate` | `mapView.onCreate(savedInstanceState)` |
| `onStart` | `mapView.onStart()` |
| `onResume` | `mapView.onResume()` |
| `onPause` | `mapView.onPause()` |
| `onStop` | `mapView.onStop()` |
| `onLowMemory` | `mapView.onLowMemory()` |
| `onSaveInstanceState` | `mapView.onSaveInstanceState(outState)` |
| `onDestroy` | `mapView.onDestroy()` |

Search accessibility updates now target `mapView`. No callback is called twice.

## 7. NavigationMapController Integration

`NavigationMapController` was not rewritten. It receives the newly programmatic MapView. Existing Dark/Positron/Liberty style behavior, markers, route casing and lines, alternatives, camera fitting, style restoration, and duplicate-source/layer protections remain unchanged.

## 8. Preview Placeholder

Created `HyperNovaNavigation/app/src/main/res/drawable/navigation_map_preview.xml`. This is a small abstract XML vector with subtle road/grid paths and existing HyperNova semantic colors. It includes no screenshot, provider data, place label, or route claim. It is referenced only by `tools:src` and is never the runtime map.

## 9. Preview-Only Sample Data

- `activity_main.xml` uses design-time Home and placeholder views; the map loading card is hidden only in Preview.
- `panel_home.xml` has `tools:showIn` plus Home, Work, recent, and route status samples.
- `panel_search.xml` has `tools:showIn` and a sample query.
- Results, calculating, route preview, active route, route overview, and special-state panels have `tools:showIn`; existing samples use `tools:text` and `tools:visibility`.

Runtime state remains owned by `MainActivity` and `NavigationStateMachine`.

## 10. Files Created

- `HyperNovaNavigation/app/src/main/res/drawable/navigation_map_preview.xml`
- `LAYOUT_PREVIEW_FIX_REPORT.md`
- `HyperNovaNavigation/artifacts/layout_preview_fix_runtime_home.png`
- `HyperNovaNavigation/artifacts/layout_preview_fix_text_results.png`
- `HyperNovaNavigation/artifacts/layout_preview_fix_route_preview.png`
- `HyperNovaNavigation/artifacts/layout_preview_fix_route_light.png`
- `HyperNovaNavigation/artifacts/layout_preview_fix_fuel_flow.png`

## 11. Files Modified

- `HyperNovaNavigation/app/src/main/res/layout/activity_main.xml`
- `HyperNovaNavigation/app/src/main/java/com/hypernova/navigation/MainActivity.kt`
- `panel_home.xml`, `panel_search.xml`, `panel_results.xml`, `panel_calculating.xml`, `panel_route_preview.xml`, `panel_active_route.xml`, `panel_route_overview.xml`, and `panel_special_state.xml` under `HyperNovaNavigation/app/src/main/res/layout/`

No provider, SDK version, package, public Launcher action, coordinates, Home/Work default, local/system theme behavior, or map-controller behavior changed.

## 12. Build Result

`./gradlew assembleDebug` passed. The final combined `./gradlew assembleDebug test lint` command was `BUILD SUCCESSFUL`.

## 13. Test Result

`./gradlew test` passed: 34 local unit tests and zero failures.

## 14. Lint Result

`./gradlew lint` passed. The current lint text report says `0 errors, 67 warnings`; no lint error was introduced or suppressed.

## 15. Cuttlefish Runtime Validation

```text
Serial: 0.0.0.0:6520
Android user: 10
Boot completed: 1
Display: 1080 x 1920
```

The host also had an unauthorized USB device, so validation used `adb -s 0.0.0.0:6520`. The APK installed and launched cold. Visual inspection of `layout_preview_fix_runtime_home.png` confirmed a real interactive OpenFreeMap/MapLibre surface beneath Home; the preview placeholder was absent. UI Automator saw the real focusable map child inside `mapContainer` with content description `Interactive OpenStreetMap navigation map`.

Additional final-APK checks:

- `Cairo University` returned three real Nominatim results.
- Its selected result rendered OSRM Route Preview with `28.8 km`, `27 min`, and a real `28.7 km`, `28 min` alternative.
- Home Fuel opened a category search and returned ten real Overpass results.
- `cmd uimode night no` and `night yes` recreated safely.
- Logcat showed no app fatal exception, ANR, MapView lifecycle error, or MapLibre duplicate source/layer error.

## 16. Android Studio Preview Validation

The terminal environment does not expose an Android Studio GUI, so a visual IDE render cannot be truthfully asserted programmatically. Source-level validation is complete: no MapLibre MapView XML tag remains, `activity_main.xml` uses framework views at the map position, `tools:src` is present, and ViewBinding generated successfully.

Manual IDE validation steps:

1. Open `app/src/main/res/layout/activity_main.xml` in Android Studio.
2. Select Design or Split. The abstract placeholder and Home panel should render without a MapLibre asset/instantiation error.
3. Toggle Preview Night mode and confirm color-qualified resources update.
4. Open every major `panel_*.xml`; `tools:showIn="@layout/activity_main"` supplies context and `tools:text` supplies samples.
5. Change a margin, padding, or text size to confirm direct Preview refresh.

## 17. Dark and Light Preview Validation

No local theme setting was restored. The placeholder uses existing color names with light and night values, so Android Studio Preview follows its normal night selector. Cuttlefish `cmd uimode night no/yes` testing verified safe Activity recreation after the refactor.

## 18. Remaining Limitations

- A terminal cannot operate Android Studio's GUI; the manual IDE steps above remain the final local visual confirmation.
- The placeholder is intentionally abstract and must never become a runtime fallback map.
- Existing scope limits remain: no live GPS/movement, traffic, ratings, offline routing, AIDL, or AI integration.

## 19. Exact Git Status and Diff Summary

The worktree was already substantially dirty before this focused fix and was preserved. `git diff --stat` lists tracked files only, so new source/resource/report files appear under `git status --short` until staged. The final response includes the exact current status and diff summary captured after this report was created.
