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

## Vehicle backend

The NXP guest is standard Android 16, so Climate has one frozen backend:
`VehicleGatewayClimateBackend`. It uses the typed Gateway AIDL; the Gateway APK speaks HNVG to QNX
`192.168.0.51:6100`, and only QNX connects to TC397 `192.168.0.30:6001`. Climate contains no
CarProperty, VHAL, or `android.car` dependency.

## Structure
```
app/src/main/
├── java/com/hypernova/climate/
│   ├── ClimateActivity.kt              full-screen portrait host
│   ├── ui/ClimateFragment.kt           renders Climate Home, wires controls
│   ├── backend/
│   │   ├── ClimateBackend.kt           abstraction (UI never sees the transport)
│   │   ├── VehicleGatewayClimateBackend.kt   typed Gateway AIDL
│   │   └── ClimateBackendFactory.kt    creates the frozen gateway backend
│   └── model/ClimateConnectionState.kt
└── res/
    ├── layout/{activity_climate,fragment_climate}.xml
    ├── values/{colors,dimens,strings,styles,themes}.xml   HyperNova design tokens
    └── drawable/                       icons, card/control backgrounds, top-down car
```

## Status — UI + state rendering
Built: project scaffold, design-system resources/theme, the full Climate Home
layout matching the approved reference, Activity/Fragment with ViewBinding, the
typed Gateway backend, the domain models
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
