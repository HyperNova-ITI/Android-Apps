# Contract Change

The earlier status integration appended this read-only method after all legacy
commands:

```aidl
void getCurrentNavigationState(
    String requestId,
    INavigationCommandCallback callback
);
```

The route-preview extension appends a separate read-only transaction:

```aidl
void getCurrentNavigationRoutePreview(
    String requestId,
    INavigationRoutePreviewCallback callback
);
```

The one-way callback returns a dedicated `NavigationRoutePreviewResult`.
Contract additions are:

```text
NavigationRoutePoint(latitude: double, longitude: double)
NavigationRoutePreview(routePoints: List<NavigationRoutePoint>,
                       currentPosition: NavigationRoutePoint?)
NavigationRoutePreviewResult(requestId, status, message, errorCode,
                             navigationState, routePreview)
```

`NavigationContract` adds `OP_GET_ROUTE_PREVIEW = "get_route_preview"` and
`MAX_ROUTE_PREVIEW_POINTS = 128`.

## Compatibility decision

The original `NavigationResult` constructor, field order, and Parcel read/write
sequence are unchanged. Existing search, saved-destination, set-destination,
cancel-navigation, current-state, and NOVA consumers therefore retain the same
wire contract. Geometry deliberately does not append an unknown tail to this
shared classic Parcelable.

`HyperNovaContract.API_VERSION` remains `1` because both read operations are
additive AIDL transactions placed after existing ones. An updated Launcher
connected to an older service receives a normal transaction failure, catches
it, and retains the complete text-only state.

Device-side tests verify both method/constants, the unchanged
`NavigationResult` round trip, route-point round trip, dedicated ACTIVE preview
result with current position, and empty preview round trip.
