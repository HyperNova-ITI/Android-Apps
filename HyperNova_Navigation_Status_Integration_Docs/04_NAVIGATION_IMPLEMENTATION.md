# Navigation Implementation

`NavigationCommandService` forwards `getCurrentNavigationState()` to
`NavigationCommandController`. The controller validates the correlation ID,
runs the read on its existing executor, and returns one final callback. It does
not register a mutable command, send an ACCEPTED intermediate result, or alter
the repository.

`NavigationResultFactory.currentStateResult()` reads metadata, while
`currentRoutePreviewResult()` separately reads geometry from the same single
`NavigationRepository.currentNavigationState()` source and maps:

| Domain state | Contract state | Destination | Route metrics | Geometry |
|---|---|---|---|---|
| IDLE | STATE_IDLE | absent unless authoritative context remains | unavailable | empty |
| CALCULATING | STATE_CALCULATING | current destination | unavailable | selected plan only if already authoritative |
| ROUTE_PREVIEW | STATE_IDLE | preview destination | unavailable | empty |
| ACTIVE | STATE_ACTIVE | active destination | selected route totals | selected OSRM route |
| ARRIVED | STATE_ARRIVED | reached destination | selected route totals | selected OSRM route |
| ERROR | STATE_ERROR | destination when retained | unavailable | empty |

The factory emits `-1` for unavailable numeric fields and rejects non-finite or
negative route metrics. It never substitutes demo values.

No new Navigation model was required: the existing `NavigationSessionState`
already retains `destination` and `routePlan`. The existing UI route flow calls
`beginCalculation()`, `showRoutePreview()`, then `activate()`, so an internally
created route is visible through the shared repository immediately.

`NavigationRoutePreviewMapper` reads `routePlan.selected.points`, the same real
GeoJSON-decoded list used by `NavigationMapController`. It rejects invalid
coordinates and uses deterministic evenly spaced vertex-index sampling when
more than 128 valid points exist. The formula preserves the first and last
point. `NavigationSession.cancel()` replaces the session with an empty state,
so IDLE cannot expose stale geometry.

`NavigationCommandService.getCurrentNavigationRoutePreview()` and its
controller path run off the Binder thread and deliver one dedicated callback.
They do not mutate the session or change the Parcel used by existing commands.
