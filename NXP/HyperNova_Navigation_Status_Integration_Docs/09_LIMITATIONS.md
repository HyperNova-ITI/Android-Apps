# Limitations

## Metric meaning

`etaSeconds` is the selected OSRM route alternative's total planned duration at
calculation time. Navigation does not currently update this value as simulated
or real route progress advances. Launcher labels it as ETA and derives arrival
as `current wall-clock time + planned duration`; that arrival is presentation
only and will drift after route start.

`distanceMeters` is the selected OSRM route alternative's total planned route
distance. It is not remaining distance. Launcher does not simulate progress.

The `distanceMeters` inside `selectedDestination` has separate existing
semantics: it may be the straight-line search distance. The card uses the
top-level route distance returned by the current-state operation.

## Map and fallback meaning

The preferred preview is now a read-only MapLibre 13.3.1 map using the same
OpenFreeMap dark/positron/liberty style URLs as Navigation. Roads and labels are
map data; Launcher does not synthesize them. Internet connectivity is required
to load uncached styles and tiles.

If MapLibre cannot initialize or load a style, Launcher keeps the prior Canvas
route renderer visible. Its subtle grid is decorative and is not claimed to be
real streets. Route geometry remains authoritative in both renderers.

Navigation caps the preview at 128 evenly sampled route vertices. This bounded
shape is appropriate for the compact widget but can omit small local bends from
a very detailed route.

When `NavigationSessionState.vehiclePosition` is present, the arrow uses its
coordinate and bearing. On the current development build, that state is fed by
Navigation's `SimulatedLocationSource`; it is still the single authoritative
source shared by the full Navigation screen and Launcher. Moving to physical
GNSS requires changing Navigation's `LocationSource`, not Launcher.

Launcher uses a 550 ms visual interpolation between the previous and newest
received points. It never extrapolates beyond a received coordinate and has no
timer-based route progress. If position is absent, the first route point is a
static truthful start marker. The destination marker is the last route point.

## Process lifetime

The shared session is authoritative within Navigation's application process.
If Android kills that process, only state already restored by Navigation's
existing persistence flow can be returned. This task adds no cross-app
SharedPreferences, provider, or broadcast workaround.

## AOSP Navigation module

The current AOSP checkout has no `HyperNovaNavigation` module or destination
APK. Navigation is present on the running device only under `/data/app` as a
debug/test-only package. Consequently the updated production Navigation APK was
built and validated but could not be copied into AOSP. The Launcher import was
updated, but the complete system-image runtime scenario cannot work until the
matching Navigation APK has an authorized AOSP import location.
