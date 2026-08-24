# Non-GMS Google Maps and Gemini boundary

## Decision

The NXP Android 16 guest is bare AOSP and does not contain
`com.google.android.gms` or Google Services Framework. The native Maps SDK for
Android, Places SDK for Android, and Navigation SDK cannot be the deployed
runtime on this image.

HyperNova therefore uses Google Maps Platform's supported browser surface in
the system Chromium WebView. Google map rendering, Places search, and route
calculation still use live Google services and the existing Google Cloud
project; only the device-side client technology changes.

## Authority boundaries

| Component | Owns | Must not claim |
|---|---|---|
| Gemini | Natural-language interpretation, conversational context, choosing the Navigation tool and phrasing confirmed results | That a route exists before the AIDL final callback |
| NOVA router | Local-vs-cloud routing, confirmation policy, request IDs, timeout handling | A successful mutation from `STATUS_ACCEPTED` alone |
| Android NOVA client | Binding to the frozen AIDL service and forwarding every accepted/final callback | A fabricated fallback when Navigation rejects a request |
| Navigation app | Google Places search, opaque result tokens, route computation, current route state and callbacks | Native turn-by-turn guidance on the non-GMS image |
| Google Maps Platform | Place IDs/data, Google map, route geometry, ETA and distance | Vehicle control or safety decisions |

## Actionable navigation conversation

```text
Driver: "Find a quiet coffee shop nearby."
Gemini: emits navigation.search(query="quiet coffee shop nearby")
NOVA -> AIDL: searchDestinations(requestId, query)
Navigation -> Google Places: Text Search (New), maximum four
Navigation -> NOVA: STATUS_CONFIRMED + four opaque destination IDs
Gemini: summarizes the returned names briefly and asks which one

Driver: "The second one."
Gemini: selects the second Navigation-issued opaque ID
NOVA -> AIDL: setDestination(newRequestId, opaqueId)
Navigation -> Google Routes: compute route
Navigation -> NOVA: STATUS_CONFIRMED only after geometry/ETA/distance exist
Gemini: "Route ready to <name>, about <ETA>."
```

Gemini Maps grounding can still answer advisory questions. It is not an action
receipt. If the user wants to navigate to a grounded suggestion, NOVA must
re-resolve it through `searchDestinations` so Android owns a valid destination
token and can confirm the route.

## Truthfulness rules

- `STATUS_ACCEPTED` means work started, not success.
- Speak success only on the final `STATUS_CONFIRMED` callback.
- Speak a short, specific failure for rejected, unavailable, timeout, or
  cancelled results.
- Never substitute a different destination silently.
- Never treat a Gemini text answer as proof that Android changed state.
- `setDestination` means route preview only; it never starts guidance.

## Headless behavior

`NavigationCommandService` may be started while `MainActivity` is closed. One
process-owned `GoogleMapsWebGateway` therefore creates the WebView during
application startup. The activity attaches that WebView only while visible and
detaches it on destruction. Search/route calls use the same JavaScript bridge in
both cases, avoiding a second map state or a launcher-only mock.

## Security and cost controls

- Dedicated Maps browser key; never reuse the Gemini/Vertex key.
- HTTPS logical document origin fixed to `https://nova.hypernova.local/`.
- Website restriction for that exact origin.
- API restriction to Maps JavaScript, Places API (New), and Routes API.
- JavaScript bridge exposes callbacks only; it exposes no Android command or
  API-key getter.
- File/content access, mixed content, popups, and arbitrary WebView navigation
  are disabled.
- The API key file is ignored and placeholder values are rejected.
- Google attribution remains visible and map content is not copied onto the
  MapLibre launcher widget. The frozen status callback still carries the
  selected destination, ETA, and distance, while its route-preview geometry is
  intentionally empty. Launcher labels that summary as Google Maps content.
- Configure low daily quotas for the three enabled APIs during the student
  demonstration period.

## Current limitation and future upgrade

The bench image supplies network location but no GNSS. Route previews use a
recent real Android location when present, otherwise the labeled ITI Smart
Village demo origin. Later, QNX/Carla vehicle position can be supplied through
an internal position adapter without changing the frozen public Navigation
AIDL.

If the final NXP image becomes officially GMS-enabled, the internal
`NavigationGateway` can be replaced by Google's native Navigation SDK gateway.
Gemini, NOVA, and the frozen AIDL contract do not need to change. Launcher
remains contract-compatible and can replace its summary renderer independently.
