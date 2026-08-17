# Runtime Flow

```mermaid
sequenceDiagram
    participant U as User
    participant UI as Navigation MainActivity
    participant R as NavigationRepository
    participant S as NavigationSession
    participant O as OSRM
    participant B as NavigationCommandService
    participant C as Launcher NavigationStatusClient
    participant H as Launcher HOME card

    U->>UI: Search and select destination
    UI->>R: calculateRoute(destination)
    R->>S: beginCalculation(destination)
    R->>O: request route
    O-->>R: real GeoJSON route
    R->>S: showRoutePreview(destination, plan)
    U->>UI: Start route
    UI->>R: activateCurrentRoute()
    R->>S: activate existing destination + plan
    U->>C: Press HOME / Launcher resumes
    C->>B: getCurrentNavigationState(requestId, callback)
    B->>R: currentNavigationState()
    R->>S: current()
    S-->>B: ACTIVE shared snapshot
    B-->>C: unchanged NavigationResult metadata
    C->>B: getCurrentNavigationRoutePreview(previewId, callback)
    B->>B: validate + downsample selected.points
    B-->>C: NavigationRoutePreviewResult(<=128 points)
    C->>C: correlate state, validate, copy to Launcher models
    C->>H: render text + Canvas route + markers
    U->>UI: Finish/cancel route
    UI->>S: reset to IDLE
    U->>C: Return HOME
    C->>B: getCurrentNavigationState(...)
    B-->>C: IDLE metadata
    C->>H: clearRoute()
```

There is no polling loop. A bind-time request plus a foreground-resume request
updates HOME after the user returns from Navigation.

## Continuous HOME progress flow

```mermaid
sequenceDiagram
    participant L as Navigation LocationSource
    participant S as Shared NavigationSession
    participant P as NavigationStatusPublisher
    participant C as Launcher NavigationStatusClient
    participant M as Launcher MapLibre map

    C->>P: registerNavigationStatusCallback(callback)
    P-->>C: NavigationRouteSnapshot(route ID/version, geometry, metadata)
    P-->>C: NavigationProgressSnapshot(position, bearing, sequence)
    C->>M: fit real route and render first authoritative marker
    loop Navigation-owned position changes
        L->>S: VehiclePosition update
        S->>P: immutable session snapshot
        P->>P: throttle to >= 1000 ms between IPC progress events
        P-->>C: progress only; no geometry
        C->>C: validate route version and increasing sequence
        C->>M: interpolate only to received endpoint; update follow camera
    end
    S->>P: cancel -> IDLE
    P-->>C: newer empty route snapshot + null progress position
    C->>M: clear route, destination marker, and vehicle arrow
```

The legacy one-shot current-state/route-preview calls remain as compatibility
fallbacks if an older Navigation service does not implement observer
registration.
