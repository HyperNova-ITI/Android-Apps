# Problem

Navigation's existing command callbacks returned destination and route metrics
to the caller that invoked `setDestination()`. A route started by Navigation's
own UI never passed through Launcher, so Launcher did not possess that callback.

Launcher worked around the missing query by calling the read-only
`getSavedDestinations()`. That result included `navigationState`, allowing the
card to say `Route active`, but deliberately did not include
`selectedDestination`, `etaSeconds`, or `distanceMeters`. The visible fallback
was therefore correct but incomplete:

```text
Route active
Route details unavailable from Navigation
ETA: —
Arrival —
```

The first fix was a dedicated read-only Binder request. That solved textual
status, but the Launcher card's upper area remained blank because
`NavigationResult` still transported only route metadata. No route coordinates
reached Launcher, and the old layout deliberately hid its placeholder preview.

The route-preview extension adds bounded geometry from the exact selected OSRM
route retained by `NavigationSessionState.routePlan`. Navigation remains the
only source of truth; Launcher neither reconstructs nor persists route state,
and it never draws a route line unless at least two valid contract points are
present.
