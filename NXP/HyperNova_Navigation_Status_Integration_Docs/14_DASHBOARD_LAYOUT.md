# Dashboard Layout

## Final hierarchy

```mermaid
flowchart TD
    Hero[NOVA hero/orb] --> Actions[Quick actions]
    Actions --> Capabilities[AI capability labels]
    Capabilities --> First[Row 1: Climate left | Media right]
    First --> Second[Row 2: Settings left | Phone right]
    Second --> Navigation[Row 3: full-width dominant live Navigation]
    Navigation --> Bottom[Fixed bottom navigation]
```

`DashboardLayoutOrder` makes this exact product order independently testable.
`MainActivity.configureResponsiveDashboardLayout()` reparents only the approved
existing card views before the Activity content is shown; card click behavior,
MediaSession controls, Climate/Phone safe states, Settings framework state, and
visual resources remain intact.

## Removed/absent widgets

The inspected baseline already had no visible Weather or Driver Profile HOME
card and no widget click binding. This redesign preserves their absence. It
does not display either application as unavailable, disabled, or not installed,
and it does not modify their projects.

## Portrait behavior

The implementation targets the Cuttlefish 1080 x 1920 at 320 dpi viewport and
reasonable physical portrait IVI variations. It does not add a screen-specific
blank spacer. Extra height belongs to the weighted Navigation row. The bottom
bar remains constrained to the root bottom and content remains constrained
above it.

Acceptance checks on a target should verify:

1. exact left/right card order;
2. Navigation is wider and taller than every other application card;
3. no lower dead region or bottom-bar overlap;
4. all Media/Phone/Climate/Settings controls remain reachable;
5. active route MapLibre roads, route, destination, arrow, heading, and text;
6. Canvas fallback and route text when map networking is unavailable;
7. route and arrow clear after Navigation cancellation.
