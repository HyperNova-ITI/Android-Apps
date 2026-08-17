# HyperNova Navigation Status Integration

This directory documents the read-only current-navigation-state, route-preview,
and live-position path completed through 2026-08-02 across HyperNova Contracts,
Navigation, and Launcher.

The implementation fixes the case where a route created inside Navigation is
ACTIVE but Launcher previously knew only the state integer. Launcher now asks
Navigation for authoritative metadata and route-preview snapshots containing
the current destination, the selected OSRM route's planned duration and total
distance, and bounded real route geometry. Launcher renders the geometry with a
MapLibre road map with the Canvas renderer retained as a safe fallback. The
live vehicle arrow consumes the same `NavigationSessionState.vehiclePosition`
used by Navigation; Launcher does not call OSRM, request device location, or
maintain a navigation session.

Documents:

- `01_PROBLEM.md` — root cause and scope
- `02_ARCHITECTURE.md` — components and ownership
- `03_CONTRACT_CHANGE.md` — additive AIDL operation
- `04_NAVIGATION_IMPLEMENTATION.md` — service-side mapping
- `05_LAUNCHER_IMPLEMENTATION.md` — client and HOME rendering
- `06_RUNTIME_FLOW.md` — lifecycle and Binder sequence
- `07_BUILD_AND_DEPLOY.md` — builds, validation, AOSP status, and commands
- `08_TEST_SCENARIO.md` — automated and manual verification
- `09_LIMITATIONS.md` — truthful metric semantics and deployment gap
- `10_ROLLBACK.md` — source and APK rollback
- `11_ROUTE_PREVIEW.md` — geometry transport, validation, projection, and visual evidence
- `12_PROPOSED_AOSP_NAVIGATION_MODULE.md` — unapplied proposal for the missing AOSP import
- `12_LARGE_HOME_NAVIGATION_WIDGET.md` — dominant MapLibre HOME widget and lifecycle
- `13_LIVE_POSITION_AND_HEADING.md` — versioned route/progress observer and marker flow
- `14_DASHBOARD_LAYOUT.md` — responsive production card order and sizing

Runtime evidence:

- `runtime-launcher-route-preview.png` — active real route rendered on HOME
- `runtime-launcher-route-cleared.png` — route line absent after finishing navigation

No Media, Settings, Climate, Phone, NOVA AI, Weather, or Driver Profile source
was modified for this integration.
