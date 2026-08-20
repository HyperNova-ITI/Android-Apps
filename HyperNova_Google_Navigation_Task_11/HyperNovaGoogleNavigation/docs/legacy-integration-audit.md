# OpenCode Worker 1 Integration Inventory

Date: 2026-08-19

## Worker record

- **Worker:** OpenCode Worker 1
- **Model:** `opencode/deepseek-v4-flash-free`
- **Task:** Read-only inventory of the frozen Navigation contract, legacy
  Navigation provider, NOVA client, Launcher client, and status-integration
  documentation.
- **Files touched:** None. The before/after worktree contained only the
  pre-existing unrelated untracked
  `HyperNova_Settings_Task_09/HyperNova_Settings/` tree.
- **Result:** Completed successfully; OpenCode exit code `0`.
- **Build/test result:** Not run by design because the task prohibited
  generated files.
- **Issues found:** Historical four-operation documentation is stale relative
  to the current additive status API; the current RPi AAOS tree now contains a
  Navigation import even though an older limitation document says it does not;
  the NOVA mock collapses some unknown-token behavior into expired-token
  behavior.
- **Codex review:** Accepted as a mechanical inventory after direct source
  verification and the corrections below.
- **Disposition:** **ACCEPTED WITH CORRECTIONS**.

## Codex corrections

1. `INavigationCommandService` has nine Binder methods in the current source:
   `getApiVersion`, four legacy command methods, two one-shot status methods,
   and two observer-registration methods. A count of eight excludes
   `getApiVersion`.
2. The service methods are synchronous Binder transactions that enqueue or
   perform bounded work; only the three callback interfaces are declared
   `oneway`.
3. The normal valid legacy commands emit `ACCEPTED` and then a final result.
   `getCurrentNavigationState` and
   `getCurrentNavigationRoutePreview` return one final result and do not use
   the request registry.
4. Progress snapshots can be emitted with a null position (initial state,
   route/state transition, or throttled interval); position availability is
   not a prerequisite for every progress emission.
5. `HyperNova_Navigation_Status_Integration_Docs/13_LIVE_POSITION_AND_HEADING.md`
   does document the observer flow. The older pull-oriented runtime sequence is
   incomplete, not evidence that the callback API is absent.

## Accepted inventory

- Frozen identity and operation constants come from
  `HyperNova_Contracts/contracts`, never from legacy app literals.
- NOVA binds explicitly by frozen package, action, and service FQCN, checks API
  version 1, and opens Navigation as soon as `setDestination` is accepted.
- Launcher binds to the same service, registers the additive status observer,
  performs a one-shot state query after binding, and uses a route-preview query
  as a compatibility fallback.
- Navigation destination IDs are opaque. Search IDs remain valid for at least
  ten minutes; saved IDs remain valid while their destination exists.
- The legacy provider uses an application-owned repository/session shared by
  the Activity and service. That ownership property must be preserved, while
  its MapLibre/OSRM/Nominatim/Overpass implementation must not be copied.
- The control permission is
  `com.hypernova.permission.CONTROL_COCKPIT_APPS`, defined by NOVA with
  signature protection and requested by Navigation and Launcher.
- The current RPi image imports `HyperNovaNavigation.apk` as a
  platform-signed `system_ext` application. No AOSP staging change is authorized
  during standalone development.

The authoritative, line-referenced audit is
[`00-existing-integration-audit.md`](00-existing-integration-audit.md).
