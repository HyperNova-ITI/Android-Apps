# HyperNova Climate — Android project

Kotlin / XML-Views (ViewBinding) climate app for the HyperNova Cockpit.
Target: **Android 16 (API 36.1)**, portrait-only, `com.hypernova.climate`.

## Open in Android Studio
Open the folder `HyperNova_Climate_Task_05/HyperNovaClimate/` as the project root,
then **Sync Project with Gradle Files**. The shared IPC contract is included from
`../../HyperNova_Contracts/contracts` (never fork the AIDL).

```bash
./gradlew :app:assembleDebug
```

## Vehicle backend switch (deployment macro)
One build flag selects the backend for the whole app — no source edits.

| Value | Backend | Link |
|---|---|---|
| `ETHERNET` *(default, enabled now)* | `VehicleGatewayClimateBackend` | Direct TCP/UDP frames to the bare-metal TC397 (`192.168.10.30:6001` / telemetry `:6000`) |
| `VHAL` | `CarPropertyClimateBackend` | Standard AAOS `CarPropertyManager`; real values bridged inside a custom VHAL |

Set in `gradle.properties` (`climate.backend=ETHERNET`) or override per build:

```bash
./gradlew :app:assembleDebug -Pclimate.backend=VHAL
```

The value is compiled into `BuildConfig.CLIMATE_BACKEND` and read once by
`ClimateBackendFactory`. Both backends are written; only their transport internals
are filled in during later phases.

## Structure
```
app/src/main/
├── java/com/hypernova/climate/
│   ├── ClimateActivity.kt              full-screen portrait host
│   ├── ui/ClimateFragment.kt           renders Climate Home, wires controls
│   ├── config/BackendMode.kt           ETHERNET | VHAL (from BuildConfig)
│   ├── backend/
│   │   ├── ClimateBackend.kt           abstraction (UI never sees the transport)
│   │   ├── VehicleGatewayClimateBackend.kt   direct Ethernet (enabled)
│   │   ├── CarPropertyClimateBackend.kt      AAOS CarProperty/VHAL
│   │   └── ClimateBackendFactory.kt    selects backend from the macro
│   └── model/ClimateConnectionState.kt
└── res/
    ├── layout/{activity_climate,fragment_climate}.xml
    ├── values/{colors,dimens,strings,styles,themes}.xml   HyperNova design tokens
    └── drawable/                       icons, card/control backgrounds, top-down car
```

## Status — UI + state rendering
Built: project scaffold, design-system resources/theme, the full Climate Home
layout matching the approved reference, Activity/Fragment with ViewBinding, the
backend-selection skeleton (Ethernet enabled), the domain models
(`ClimateCapabilities` / `ClimateState` / enums), `ClimateUiState`, a
`ClimateViewModel`, and capability-driven rendering of every field and control.

### How the screen gets its values
`ClimateViewModel` seeds its state from `ClimatePreview`, provided **per build
variant**:
- `src/debug/…/ClimatePreview.kt` → a populated sample matching the reference,
  so **debug builds on the emulator look right**.
- `src/release/…/ClimatePreview.kt` → an honest empty/unavailable state, so **no
  dummy data ships**.

Run the **debug** variant to see the reference look. In later phases this flow is
driven by the real `ClimateBackend` state instead of the seeded value; the
rendering code does not change.

Not yet wired (later phases per `../IMPLEMENTATION_PLAN.md`): command manager +
confirmation, the TC397 transport (frame codec/CRC/ACK), the NOVA
`ClimateCommandService`, and the Launcher status service.

> Icons under `res/drawable/ic_*` are lightweight vector placeholders. Swap for
> Material Symbols Rounded assets when available (README §12).
