# Launcher Implementation

`NavigationStatusClient` invokes
`INavigationCommandService.getCurrentNavigationState()` for authoritative text
and metadata. For CALCULATING, ACTIVE, or ARRIVED it then invokes the separate
`getCurrentNavigationRoutePreview()` query. IDLE never makes the second request.
If the additive transaction is unavailable or times out, the client publishes
the complete textual snapshot without geometry.

`NavigationStatusMapper.fromResult()` converts the contract result to the
Launcher-owned `NavigationStatusSnapshot`:

- confirmed/accepted transport status maps to a READY connection;
- failed final status maps to ERROR;
- contract navigation state maps independently to IDLE, CALCULATING, ACTIVE,
  ARRIVED, ERROR, or UNAVAILABLE;
- blank destination text and `-1` numeric sentinels remain absent;
- an authoritative Navigation ERROR message is retained.
- dedicated preview-result points are copied by `withRoutePreview()` into immutable Launcher-owned
  `NavigationPreviewPoint` values;
- non-finite or out-of-range coordinates are discarded;
- fewer than two valid points disables the preview.

The existing lifecycle remains unchanged and is sufficient:

- bind and request on `onStart()`;
- request a fresh snapshot on every `onResume()`/HOME return;
- reconnect on Navigation package add/change/replace;
- unbind on `onStop()`;
- handle timeout, disconnect, null binding, binding death, and missing package.

Metadata and preview have separate UUID correlation IDs. Geometry is accepted
only when both responses describe the same runtime state.

`LauncherStateController` formats real snapshot values into destination, ETA,
distance, and arrival labels and now forwards preview points through
`LauncherUiState`. Arrival is derived only for presentation as the current
wall-clock time plus the returned planned duration.

`NavigationRoutePreviewView` replaces the old invisible placeholder in the
existing card. It draws a dark navy background, subdued decorative grid, cyan
route `Path`, current/start marker, and destination marker. It prepares the
projection and Path only when data or size changes, then draws them without
per-frame route allocations. IDLE/error rendering calls `clearRoute()`.

`NavigationRouteProjection` is a pure tested helper. It adjusts longitude by
the cosine of the route's middle latitude, computes a bounding box, preserves
aspect ratio, centers the result inside padded bounds, and negates latitude so
north maps upward on Android Canvas.

Only the Navigation card layout and related colors/string were changed for the
preview. NOVA, Media, Settings, Phone, Climate, theme switching, and launch
behavior remain intact. The forbidden Phone/Climate privileged permissions
remain absent from the production Launcher APK.
