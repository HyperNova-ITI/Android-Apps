# Architecture

Navigation's `Application` owns one `NavigationRepository`. Both
`MainActivity` and `NavigationCommandService` obtain that same instance, whose
`NavigationSession` stores the current immutable snapshot.

```mermaid
flowchart TD
    UI[Navigation MainActivity] --> Repo[NavigationRepository]
    Repo --> Session[NavigationSession]
    Repo --> OSRM[OSRM route backend]
    OSRM -->|GeoJSON coordinates| Plan[RoutePlan.selected.points]
    Plan --> Session
    Session --> Active[ACTIVE shared session state]
    Active --> Service[NavigationCommandService]
    Service --> Mapper[NavigationRoutePreviewMapper<br/>validate + bound to 128 points]
    Active --> Metadata[NavigationResult metadata response]
    Mapper --> AIDL[Additive route-preview response]
    AIDL --> Result[NavigationRoutePreviewResult]
    Metadata --> Client[Launcher NavigationStatusClient]
    Result --> Client
    Client --> StatusMapper[NavigationStatusMapper<br/>Launcher-owned immutable points]
    StatusMapper --> Controller[LauncherStateController]
    Controller --> State[LauncherUiState]
    State --> View[NavigationRoutePreviewView]
    View --> Card[Canvas route + markers in HOME card]
```

Ownership remains one-way:

```mermaid
flowchart LR
    Nav[Navigation domain state] -->|read-only snapshot| Contract[HyperNova Contracts]
    Contract --> Launcher[Launcher-owned snapshot]
    Launcher --> Home[HOME presentation]
```

The status operation does not call search, route calculation, activation,
cancellation, preference storage, UI views, or OSRM. The same selected
`RouteAlternative.points` list feeds both Navigation's map controller and the
read-only preview mapper.

## Live HOME extension

```mermaid
flowchart TD
    Location[Navigation-owned LocationSource] --> Repo[NavigationRepository]
    Repo --> Session[NavigationSessionState.vehiclePosition]
    Session --> FullMap[Navigation MapLibre arrow]
    Session --> Publisher[NavigationStatusPublisher]
    Publisher -->|route/version + geometry on change| Route[NavigationRouteSnapshot]
    Publisher -->|position + heading <= 1 Hz| Progress[NavigationProgressSnapshot]
    Route --> Observer[INavigationStatusCallback]
    Progress --> Observer
    Observer --> Client[Launcher NavigationStatusClient]
    Client --> Mapper[Launcher mapper/reducer]
    Mapper --> UiState[LauncherUiState]
    UiState --> MiniMap[Read-only Launcher MapLibre map]
    MiniMap --> Fallback[Canvas fallback if style/tile renderer is unavailable]
```

The route and progress channels are deliberately split. Geometry is bounded to
128 points and is never repeated on a location tick. Sequence numbers reject
stale progress, while the route ID and monotonically increasing route version
prevent a position from being applied to a different route.
