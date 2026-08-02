# Runtime State Flow

## Navigation

```mermaid
sequenceDiagram
    participant UI as Navigation UI
    participant Repo as NavigationRepository/Session
    participant AIDL as NavigationCommandService
    participant Client as NavigationStatusClient
    participant Home as Launcher HOME

    UI->>Repo: User creates/activates a real route
    Repo->>Repo: Session becomes ACTIVE
    Home->>Client: onResume refresh
    Client->>AIDL: getSavedDestinations(requestId)
    Note over Client,AIDL: Non-mutating request; used only because API v1 includes navigationState
    AIDL-->>Client: NavigationResult(STATE_ACTIVE)
    Client-->>Home: Active route state
    Note over Home: Destination/ETA/distance remain unavailable because this query omits them
```

## Media

```mermaid
sequenceDiagram
    participant App as HyperNova Media
    participant Session as MediaSession
    participant Client as MediaSessionClient
    participant Home as Launcher media card

    App->>Session: Publish metadata/playback/actions
    Session-->>Client: Player events
    Client-->>Home: Title, artist, playback state, position, duration, actions
    Home->>Session: Play/pause/previous/next only when command is available
```

## Settings

```mermaid
flowchart LR
    Wifi[WifiManager] --> Client[SystemSettingsClient]
    Bt[Settings.Global Bluetooth] --> Client
    Brightness[Settings.System brightness] --> Client
    Volume[AudioManager media volume] --> Client
    Broadcasts[Framework broadcasts/content observers] --> Client
    Client --> Card[Launcher Settings card]
```

The Launcher observes these values only. It does not write Settings.

## Climate

```mermaid
flowchart TD
    Start[Climate app available] --> Service{Frozen Climate service exists?}
    Service -->|yes| Query[getCurrentState]
    Query --> ContractState[Confirmed ClimateState]
    Service -->|no / unavailable| Car[AAOS Car service]
    Car --> VHAL[CarPropertyManager HVAC properties]
    ContractState --> Card[Launcher Climate card]
    VHAL --> Card
    Query -->|permission/error/no state| Car
    Car -->|permission/property unavailable| Unavailable[Honest HVAC unavailable state]
```

## Phone

```mermaid
flowchart LR
    Bluetooth[Android Bluetooth enabled state] --> PhoneClient[PhoneStatusClient]
    Accounts[Telecom call-capable phone accounts] --> PhoneClient
    Calls[Telecom isInCall] --> PhoneClient
    Events[Telecom/phone/Bluetooth events] --> PhoneClient
    PhoneClient --> Card[Launcher Phone card]
```

No Contacts Provider, PBAP data, phone number, device name, or synthetic call
timer is read or displayed.

## Package lifecycle

```mermaid
sequenceDiagram
    participant PM as PackageManager
    participant Monitor as AppAvailabilityMonitor
    participant Activity as MainActivity
    participant Client as Affected state client
    participant UI as HOME card

    PM-->>Monitor: PACKAGE_ADDED/REMOVED/CHANGED/REPLACED
    Monitor-->>Activity: registered package changed
    Activity->>Client: reconnect/refresh affected integration
    Activity->>PM: recompute availability
    Activity-->>UI: rerender current state
```
