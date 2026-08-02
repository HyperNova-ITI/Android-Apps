# Large HOME Navigation Widget

## Purpose

The previous HOME used three fixed-height rows inside wrap-content scrolling:
Navigation/Media at 188 dp, Phone/Climate at 226 dp, and a 96 dp Settings row.
On a taller portrait IVI panel, the content ended before the fixed bottom bar,
leaving a large unused lower region. Navigation was also only half width.

The production arrangement reuses the existing card implementations and visual
language but presents them in driving priority:

```text
Climate | Media
Settings | Phone
Navigation (full width, dominant, fills remaining height)
```

Weather and Driver Profile have no HOME views. Their harmless package registry
entries remain outside this dashboard and were not expanded or modified.

## Responsive sizing

The root remains a `ConstraintLayout`. The main `NestedScrollView` is constrained
between the status divider and fixed bottom navigation and uses `fillViewport`.
Its content fills the viewport. Two compact fixed rows are followed by a
zero-height, weight-1 Navigation row with a 220 dp minimum height.

```mermaid
flowchart TD
    Status[Fixed status bar] --> Viewport[Constrained HOME viewport]
    Viewport --> Hero[Compact NOVA hero/actions/capabilities]
    Hero --> Row1[Climate | Media, 170 dp]
    Row1 --> Row2[Settings | Phone, 150 dp]
    Row2 --> Nav[Navigation, weight 1, min 220 dp]
    Nav --> Bottom[Fixed bottom navigation]
```

On larger screens, Navigation receives all extra vertical space. On a smaller
portrait screen, its minimum height causes controlled scrolling instead of
overlap, clipping, or undersized touch targets. The NOVA artwork height was
reduced from 150 dp to 82 dp and Media artwork from 78 dp to 50 dp to keep the
approved functions while prioritizing driving information.

## Map implementation

Launcher creates one MapLibre `MapView` inside `navigationMapContainer` and
keeps it for the Activity lifetime. Version 13.3.1 exactly matches Navigation.
The map is read-only: scroll, zoom, rotate, tilt, quick zoom, and double-tap
gestures are disabled. MapLibre attribution and logo remain enabled.

Styles:

- dark: `https://tiles.openfreemap.org/styles/dark`
- light: `https://tiles.openfreemap.org/styles/positron`
- style-load fallback: `https://tiles.openfreemap.org/styles/liberty`

The map draws a dark cyan route casing, cyan route line, start/destination
markers, and the authoritative vehicle arrow. Launcher has `INTERNET` access
for styles/tiles, but merged MapLibre coarse/fine location permissions are
explicitly removed. It never registers a local location listener.

## Lifecycle and fallback

`MapView.onCreate`, `onStart`, `onResume`, `onPause`, `onStop`,
`onSaveInstanceState`, `onLowMemory`, and `onDestroy` are forwarded from
`MainActivity`. Controller listeners and marker animation are removed on
destroy. The map is not recreated for progress updates.

Until a real style is ready—or if MapLibre initialization/style loading fails—
the existing Canvas preview remains visible. Textual destination, route state,
ETA, distance, and arrival remain available independently of the map.
