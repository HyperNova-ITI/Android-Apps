# NOVA Launcher stage

Status: implemented baseline, 2026-08-12

## Product behavior

NOVA is the main conversational surface on Home. The top stage is intentionally larger than the
other dashboard cards, but it remains part of the Launcher so the driver keeps Navigation, Media,
Climate, and Phone context in one glance.

The stage shows:

- a minimal animated NOVA face instead of the old star-like mark;
- a compact Ready / Listening / Thinking / Acting / Speaking / Done / Attention status chip;
- the recognized request, current truthful progress, and actual final response;
- progressive response text rather than an abrupt paragraph replacement;
- at most one contextual result card for the latest real action domain;
- an explicit **Open** button for the responsible app.

The Launcher does not mention Pi microphones, Android speakers, cloud model names, sockets, or other
implementation topology. Those details belong in developer diagnostics, not the vehicle UI.

## Context-card ownership

`NovaContextCardFactory` combines NOVA's presentation-only action domain with state the Launcher
already reads from the owning app:

| NOVA domain | Card source | Open target |
|---|---|---|
| Navigation | `NavigationUiState` | HyperNova Navigation |
| Media | `MediaUiState` | HyperNova Media |
| Phone | `PhoneUiState` | HyperNova Phone |
| Climate | `ClimateUiState` | HyperNova Climate |
| Vehicle/fault | Existing vehicle/NOVA presentation state | No automatic app switch |

There is no fake card data and no embedded activity or fragment. A card disappears when no action
domain is active. The entire card and its button open the same destination; the Launcher never jumps
screens merely because NOVA set a destination or changed playback.

## Contract and failure behavior

NOVA exposes private status AIDL API v2. The Launcher registers one callback and parses the bounded
JSON snapshot with `NovaStatusSnapshotCodec`. A version mismatch, malformed payload, missing service,
or binder death produces the existing unavailable UI and never leaves a stale success on screen.

Accessibility uses the complete response as the text view's content description even while the
visible typewriter animation is in progress. The animation is cancelled when the activity is
destroyed or a newer state arrives.

## Verification

Run from each module:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The Launcher unit suite covers strict snapshot parsing and real state-to-card mapping. NOVA tests
cover bounded presentation encoding. For an end-to-end visual check, install both APKs, start NOVA,
return to Home, and run one command in each integrated domain. The expected behavior is stage text
plus one real context card; app switching occurs only after the user presses **Open**.
