# HyperNova Navigation screen-state matrix

The current demo starts at ITI Smart Village. “Real” means the state is reachable through normal user/provider behavior. “Debug-only” means the presentation is complete but is not automatically claimed without live movement.

| State | Trigger | Data source | Main actions | Error transitions | Current status |
|---|---|---|---|---|---|
| Navigation Home | Cold start, clear route, or end route | ITI Smart Village start point; persisted real recents; verified default or user-set Home/Work; connectivity; system `uiMode` | Search, Home, Work, Fuel, Recent | Offline when the network is unavailable | Real |
| Search Destination | Search button, category, shortcut configuration, or search again | User input; persisted real recents; Overpass category/radius progress | Submit Nominatim text search, run Overpass category search, Retry, clear, choose recent, Cancel | Inline validation; no results; provider error; cancellation; Offline only when device connectivity is lost | Real |
| Search Results | Successful Nominatim text response or Overpass category response | Real provider coordinates/tags and Haversine straight-line distance | Select result, select destination, save Home/Work, search again | Empty after maximum radius or provider error returns to Search; Offline | Real |
| Calculating Route | Confirm any real Nominatim/Overpass/default destination | ITI start point + selected real place; live OSRM request | Cancel | Route Preview on success; Route Error or Offline on failure | Real |
| Route Preview | Successful OSRM response | Real OSRM geometry, distance, duration, steps, and alternatives | Choose real route, Start Route, Route Overview, change/clear destination | Route Error only from a new failed calculation | Real |
| Active Navigation Preview | Start Route | Selected real OSRM route; first real maneuver only | Mute preference, Route Overview, End Route | No automatic reroute/arrival without movement | Real route preview from ITI |
| Route Overview | Overview from preview/active route | Real OSRM maneuver list | Return to Route, End Route | Returns to the owning route screen | Real |
| Rerouting | Future route-deviation event | Existing route/destination when available | Cancel/return to route | Route Error or Offline in a future live implementation | Debug-only presentation |
| Arrived | Future verified arrival event | Existing destination/route summary when available | Done, return Home | None | Debug-only presentation |
| Location Unavailable | Configured origin cannot be resolved | Origin configuration state | Retry, open location settings, Home | Offline or Home depending on retry | Defensive real state; debug-testable |
| Offline / Network Unavailable | Validated Android connectivity is lost | Android connectivity classification | Retry, change destination, Home | Returns to prior safe flow when connectivity succeeds | Real failure state; debug-testable |
| Route Error | OSRM timeout, malformed response, no route, or other route failure | OSRM failure classification and selected destination | Try Again, Change Destination, Clear Route | Calculating Route on retry; Search or Home | Real failure state; debug-testable |

## Transition outline

```text
HOME
  -> SEARCH
  -> SEARCHING
  -> RESULTS
  -> CALCULATING_ROUTE
  -> ROUTE_PREVIEW
  -> ROUTE_ACTIVE
  -> ROUTE_OVERVIEW

SEARCHING or CALCULATING_ROUTE -> OFFLINE
CALCULATING_ROUTE              -> ROUTE_ERROR

REROUTING, ARRIVED, and the special-state presentations
are selectable only in debuggable builds for this ITI start-point demo.
```

`SEARCHING` is an internal loading state rendered within the Search Destination screen rather than a thirteenth product screen.
