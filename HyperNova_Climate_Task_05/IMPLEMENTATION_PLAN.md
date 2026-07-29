# HyperNova Climate — Implementation Plan

> **Task:** 05 — Climate Android App (`com.hypernova.climate`)
> **Language / UI:** Kotlin · XML Views + ViewBinding · MVVM
> **Backend decision:** `CarProperty`/VHAL is the app-facing path; the real values come from the bare-metal **TC397 over Ethernet**. This plan explains exactly how those two are linked.
> **Deadline context:** Hyper-Nova Cockpit graduation project — Aug 30, 2026.
> **Scope of this document:** the plan only. No code is written yet.

---

## 0. What already exists (don't rebuild these)

- **Frozen contract module** — `HyperNova_Contracts/contracts` already contains the Climate AIDL and Java types (`IClimateCommandService`, `IClimateCommandCallback`, `ClimateContract`, `ClimateCapabilities`, `ClimateState`, `ClimateResult`). Consume this module; never copy or fork the AIDL.
- **Service guide** — `HyperNova_Contracts/docs/MAHGOUB_CLIMATE_SERVICE_GUIDE.md` is the authoritative recipe for the NOVA-facing command service. Follow it exactly.
- **Design system** — defined as tokens/templates in `HyperNova_Team_Requirements (2)/00-Design-System` and mirrored in the README §9–§12. It is a spec, not a compiled module yet — bring the tokens in as `res/values` resources.
- **Reference app structure** — `HyperNova_Launcher_Task_01` is the template: Gradle layout, `app/src/main/{java,res,aidl,keepRules}`, `aosp/product/etc`, `gradle.properties`. Mirror it.
- **The approved reference image** — `assets/hypernova_climate_reference.png`. Dual-zone Climate Home. This is the visual target.

The frozen contract already fixes the numbers you must honor:
`ZONE_ALL=0, ZONE_DRIVER=1, ZONE_PASSENGER=2`; query timeout **2 s**, command timeout **5 s**; availability `UNAVAILABLE/AVAILABLE/STALE`; errors `HARDWARE_REJECTED / UNSUPPORTED_ZONE / OUT_OF_RANGE`.

---

## 1. The core architecture question — linking CarProperty to the bare-metal TC397 over Ethernet

Your question: *"How does CarProperty get its values from the vehicle when the vehicle is the bare-metal TC397, and the IVI connects to it over Ethernet?"*

Short answer: **`CarPropertyManager` never talks to hardware directly. It talks to a HAL (the VHAL). To connect real hardware you put your Ethernet client inside — or behind — that HAL.** There are two clean ways to do it, and the README's `ClimateBackend` abstraction is designed so you can pick one without changing the UI.

### 1.1 The standard AAOS property stack

```
Climate UI / NOVA
      │  CarPropertyManager.get/setProperty(HVAC_*)
      ▼
Car Service (system_server)            ← standard AOSP, you don't modify this
      ▼
Vehicle HAL (VHAL)                      ← THIS is your integration point
      ▼
??? real vehicle transport
```

In stock AOSP the VHAL is a reference/emulator implementation that just stores HVAC values in RAM. Reading `HVAC_TEMPERATURE_SET` returns whatever was last written locally — no real vehicle involved. Your job is to replace that "RAM" with "the TC397 over Ethernet."

### 1.2 Option A — VHAL bridge (the true "CarProperty" path)

You implement/modify the VHAL so that HVAC property reads and writes are translated into TC397 Ethernet frames:

```
CarPropertyManager.setProperty(HVAC_TEMPERATURE_SET, zone, 22.0)
      ▼  Car Service
      ▼
Custom VHAL  ──encodes──►  CMD_SET_HVAC frame  ──TCP 192.168.10.30:6001──►  TC397
      ◄──EVT_CMD_ACK / EVT_CMD_REJECTED────────────────────────────────────
      ▼
VHAL updates the property + fires onPropertyEvent
      ▼
CarPropertyManager.onChangeEvent  ►  app UI updates from confirmed value

Telemetry (cabin temp, outside temp, air quality):
TC397 EVT_SENSOR_DATA  ──UDP 192.168.10.x:6000──►  VHAL  ►  property change events
```

The VHAL owns one TCP client (commands, one connection at a time — the TC397 serves exactly one) and one UDP listener (telemetry, fire-and-forget). It encodes/decodes the frame format from `TC397-Networking.md`: `CMD_TYPE | SEQ | LEN | PAYLOAD | CRC_LO | CRC_HI`, CRC-16/CCITT-FALSE over `CMD_TYPE+PAYLOAD`, little-endian. `SEQ` correlates each reply to its request.

- **Pros:** The Climate app stays 100 % standard AAOS — `CarPropertyClimateBackend` just uses `CarPropertyManager`. Launcher, NOVA, and any future app all see the same real values for free. This is the "production-correct" answer.
- **Cons:** Requires building/flashing a custom VHAL into the AOSP image and mapping standard HVAC property IDs to `CMD_SET_HVAC`. More AOSP plumbing, longer feedback loop. In your topology the VHAL likely runs on the NXP gateway/QNX side and reaches the TC397 — coordinate the socket ownership with the NXP team.

### 1.3 Option B — Direct gateway backend (fastest path to a working demo)

Skip the VHAL. The app's `VehicleGatewayClimateBackend` opens its own TCP socket to the TC397 (directly, or via the NXP gateway relay) and speaks the frame protocol itself:

```
Climate repository ► VehicleGatewayClimateBackend
      │  TCP 6001 : CMD_SET_HVAC   /   UDP 6000 : EVT_SENSOR_DATA
      ▼
TC397 bare metal
```

- **Pros:** No AOSP/VHAL work. You can develop and test the whole app against the TC397 (or a laptop socket simulator) immediately. Matches the raw-Ethernet reality of `TC397-Networking.md` directly.
- **Cons:** Not "CarProperty." Other apps don't get the values unless they also bind your service. This is a graduation-demo shortcut, not the OEM-production shape.

### 1.4 Recommendation

Because the whole app is written against the `ClimateBackend` interface, **the UI does not care which one you use.** Recommended sequencing:

1. **Start on Option B** (`VehicleGatewayClimateBackend`) so you have real TC397 values flowing end-to-end early and can demo the full screen. This de-risks the schedule.
2. **Keep Option A (VHAL bridge) as the documented production path** and implement `CarPropertyClimateBackend` against it if/when the custom VHAL is available on the target image. Swapping backends is a one-line change in the repository/DI, with zero UI changes.

Either way, the frame encoder/decoder, CRC, SEQ correlation, ACK/timeout logic, and the TC397 capability mapping are the **same reusable code** — write it once in a transport layer and let both the VHAL bridge and the gateway backend call it.

### 1.5 The TC397 command mapping (this is what "real values" means)

From `TC397-ClimateControl.md`, the only fully-operational command today is `CMD_SET_HVAC (0x01)`, 4-byte payload:

| Byte | Field | Range | Maps to app concept |
|---|---|---|---|
| 0 | `target_temp_c` | 16–28 °C | `setTargetTemperature` (ignored when `level=0`) |
| 1 | `level` | 0–5 | fan level; `0` = fans off = climate power off |
| 2 | `zone` | 0=both, 1, 2 | `ZONE_ALL / ZONE_DRIVER / ZONE_PASSENGER` |
| 3 | `caller` | 0=Driver, 1=AI | who issued it (Driver vs NOVA) |

Replies: `EVT_CMD_ACK (0x83)`, `EVT_CMD_REJECTED (0x82)` + reason, `EVT_FAULT_EVENT (0x81)`. A command is **confirmed only on ACK** — this is exactly the README's "never show success before confirmation" rule, enforced at the wire level.

**Capability truth (critical):** the TC397 today does **not** expose A/C toggle, AUTO, recirculation, airflow direction, defrost, or seat heating as commands — `CMD_SET_HVAC` only carries temperature, fan level, and zone; the other frame commands are "Interface reserved." Therefore your real `getCapabilities()` must advertise roughly: **dual-zone, temp 16–28 °C, max fan level 5, supportsAc=false, supportsAuto=false, supportsRecirculation=false** until the vehicle team implements those frames. The capability-driven UI then hides/disables everything unsupported — which is precisely why the README forbids hard-coding these. Build the UI for all controls, but let capabilities gate what actually renders as active.

---

## 2. Phased implementation plan

Fourteen phases, ordered so each one is demoable and de-risks the next. Phases 1–2 unblock everything; phases 3–5 give you a screen; 6–9 make it real; 10–12 integrate; 13–14 harden and ship.

### Phase 1 — Project skeleton & contracts wiring
- Create the Gradle Android project under `HyperNova_Climate_Task_05/HyperNovaClimate/` mirroring the Launcher layout. Identity from the service guide: `namespace/applicationId com.hypernova.climate`, `compileSdk 36`, `targetSdk 36`, `minSdk 35`, portrait-only.
- In `settings.gradle.kts` include the shared contracts module by relative path:
  `project(":hypernova-contracts").projectDir = file("../../HyperNova_Contracts/contracts")` and add `implementation(project(":hypernova-contracts"))`.
- Confirm the generated Binder types import (`com.hypernova.contracts.climate.*`). Do **not** commit `build/generated`.
- **Exit check:** `./gradlew :app:assembleDebug` succeeds; contract types resolve.

### Phase 2 — Design system as resources
- Port the tokens (README §9–§12) into `res/values`: `colors.xml` (the `hn_*` palette), `dimens.xml` (4–32 dp scale, radii, touch sizes), `type.xml`/text-appearances (Roboto scale), and a dark `Theme.HyperNovaClimate`. Add Material Symbols Rounded icons.
- Do not redefine token values locally — these mirror the shared system so all cockpit apps match.
- **Exit check:** a blank themed Activity shows the navy background and correct typography.

### Phase 3 — Domain models (no data yet)
- Implement the internal model layer from README §34–§37: `ClimateCapabilities`, `ClimateState`, `ClimateMode`, `ClimateHealth`, `ClimateZoneMode`, `AirflowMode`, `AirQualityState`, `ClimateCommand` (+ command states `CREATED…UNAVAILABLE`), `ClimateCommandResult`, `ClimateUiState`, `ClimateRequestedState`.
- Keep **internal** models separate from the frozen contract types; map between them at the service boundary (guide §7–§8).
- **Exit check:** models compile; unit test constructs a sample `ClimateUiState`.

### Phase 4 — Static Climate Home layout (matches the reference image)
- Build the fixed hierarchy (README §13) as XML with ViewBinding, using the recommended layout files (§51) and view IDs (§52): header → environment status card → driver zone → central vehicle visualization → passenger zone → health card → primary controls → fan → airflow → fresh/recirc → defrost → seat heating → confirmation message.
- Must fit `1080×1920` portrait with **no scrolling**; touch targets ≥ 48 dp, temperature buttons ≥ 56 dp.
- Wire it to hard-coded *preview* values **in the layout preview only** — no dummy data in code paths (README §5).
- **Exit check:** side-by-side with `hypernova_climate_reference.png`; no clipping/overlap.

### Phase 5 — ViewModel + state rendering (still no vehicle)
- `ClimateViewModel` exposes an immutable `StateFlow<ClimateUiState>`. `ClimateFragment` renders **only** from `confirmedState`; requested values render separately (README §36, §4).
- Implement the component binders (`HeaderBinder`, `EnvironmentStatusBinder`, `DriverZoneBinder`, …) and all required visible states (§27): starting, off, auto/manual/cooling/heating/defrost, pending, rejected, timeout, unavailable, comm-lost, capabilities-loading, error.
- Feed it from an in-memory `StateFlow` for now (in `viewModel`/test scope, not a production repository).
- **Exit check:** flipping state in a debug hook drives every visual state correctly.

### Phase 6 — Backend abstraction + transport layer
- Define `ClimateBackend` (README §38): `state: StateFlow<ClimateState>`, `capabilities: StateFlow<ClimateCapabilities>`, `suspend execute(command)`, `suspend refreshState()`.
- Build the reusable **TC397 transport**: frame encode/decode, CRC-16/CCITT-FALSE, SEQ correlation, TCP command client (one connection), UDP telemetry listener, ACK/reject/timeout state machine (5 s mutations, 2 s queries). This is shared by both backend options.
- **Exit check:** transport unit-tested against known CRC vectors and a loopback socket stub (test-only, under `src/test`).

### Phase 7 — Primary backend: `VehicleGatewayClimateBackend` (Option B)
- Implement `ClimateBackend` over the transport: map `execute()` commands to `CMD_SET_HVAC`; parse `EVT_SENSOR_DATA` into `cabin/outside` temps + air quality; parse `EVT_FAULT_EVENT` into `ClimateHealth`.
- Derive **real** `ClimateCapabilities` from the TC397 mapping table (§1.5) — advertise only temp/fan/zone until more frames land.
- **Exit check:** app shows live TC397 values; a temperature change round-trips ACK→confirmed against the board (or the socket simulator).

### Phase 8 — Command manager & confirmation semantics
- `ClimateCommandManager` (README §19, §29–§33): serialize conflicting commands, block duplicates while pending, hold `confirmed` until ACK, surface `Requested` vs `Confirmed`, handle REJECTED (restore confirmed), TIMEOUT (don't claim success, offer refresh), and honor authorization rejections from TC397 (overheat/critical/sensor-fault reasons `0x04/0x07/0x08`).
- **Exit check:** pending/rejected/timeout flows each render correctly and never mutate `confirmed` early.

### Phase 9 — Capability-driven UI + single/dual-zone fallback
- Drive every control's visibility/enabled-state from `ClimateCapabilities` (README §6, §34): single-zone hides passenger card + sync; unsupported A/C, AUTO, recirc, defrost, seat heat, airflow are hidden/disabled with honest text.
- **Exit check:** run the capability test matrix (§55.1) — single-zone, no passenger, no A/C, variable fan count, missing sensors — UI adapts with no fake data.

### Phase 10 — NOVA command service (frozen Demo API v1)
- Implement `ClimateCommandService` exactly per the service guide (§9–§15): `IClimateCommandService.Stub`, single-thread executor, duplicate-`requestId` cache (≥10 min), validation before any TC397 transmit, `accepted/confirmed/rejected` result construction, `ZONE_ALL` power-on-and-set correlation (guide §13).
- UI and service share **one** application-scoped repository so both read identical confirmed state.
- **Exit check:** guide §18 unit tests pass (capability mapping, range/zone validation, duplicate, ACK, rejection, 5 s timeout, partial `ZONE_ALL`).

### Phase 11 — Launcher status service
- `ClimateStatusService` (read-only) publishes availability/mode/temps/fan/A-C/AUTO/pending per README §39–§40 via `ClimateStatePublisher`. Launcher quick action "Set climate to 22 °C" must wait for confirmation.
- **Exit check:** Launcher receives live state; quick action confirms before reporting success.

### Phase 12 — IPC security, versioning, resilience
- Signature permission `com.hypernova.permission.CONTROL_COCKPIT_APPS` on the command service (README §42, guide §16); same signing key as NOVA. `getApiVersion()`/version-mismatch handling (§43). Binder-death handling, service-connection release, background reconnect, comm-loss freeze+stale marking (§33, §47).
- **Exit check:** differently-signed APK cannot bind; kill/restart the service — no false success.

### Phase 13 — Central vehicle visualization + animation polish
- Make the top-down car functional (README §16): airflow overlays reflect **confirmed** airflow only, cyan cooling / amber heating, defrost→windshield, static fallback, pause when hidden. Animation timings per §46; respect reduced-animation.
- **Exit check:** overlays change only after confirmation; no animation while backgrounded.

### Phase 14 — Test, build, AOSP integration, deliverables
- Full test matrix (README §55): capabilities, temperature, fan/modes, airflow/source, seat heating, integration, visual. Produce debug + release APKs (`HyperNovaClimate-debug/release.apk`).
- If pursuing Option A: build/flash the custom VHAL, implement `CarPropertyClimateBackend`, validate on the target portrait display.
- Assemble the §57 deliverables: state screenshots, capability/command/integration test reports, permission docs, AOSP + backend/VHAL notes, updated README.
- **Exit check:** Definition of Done (§58) fully ticked.

---

## 3. First three concrete steps to start today

1. **Scaffold the Gradle project** (Phase 1) by copying the Launcher project shape, then wire the `hypernova-contracts` module path and confirm `assembleDebug` + contract imports.
2. **Port the design tokens** (Phase 2) into `res/values` and stand up `Theme.HyperNovaClimate`.
3. **Decide the socket owner** for the TC397 link with the NXP team — does the IVI open TCP `192.168.10.30:6001` directly, or does the NXP gateway relay? This determines whether `VehicleGatewayClimateBackend` connects straight to the board or to the gateway, and it's the one external dependency that gates Phase 7.

---

## 4. Risks & watch-items

- **Capability mismatch is the #1 correctness trap.** The rich README UI implies A/C, AUTO, recirc, defrost, seat heat — the TC397 doesn't implement those yet. Advertise only temp/fan/zone; let capabilities hide the rest. Do **not** fake them.
- **Confirmation discipline.** ACK ≠ requested-applied-optimistically. Every mutation waits for `EVT_CMD_ACK` or authoritative readback; 5 s → timeout, explicit reject → restore confirmed.
- **One TCP client only.** The TC397 serves a single command connection. If both the VHAL and the app try to own it, they collide — decide the single owner (§3 step 3).
- **Signing.** NOVA and Climate must share the integration key or the signature-protected bind fails silently. Build integration APKs on one machine / shared debug key.
- **No production dummy data** anywhere outside `src/test` / `src/androidTest` (README §5).

---

## 5. Source-of-truth references

- App spec: `HyperNova_Climate_Task_05/README.md` (visual + full capability model)
- NOVA service recipe: `HyperNova_Contracts/docs/MAHGOUB_CLIMATE_SERVICE_GUIDE.md`
- Frozen contract code: `HyperNova_Contracts/contracts/src/main/...` (`ClimateContract.java` constants)
- Vehicle backend truth: `TriCore_Node/docs/TC397-ClimateControl.md`, `TC397-Networking.md`
- Design tokens: `HyperNova_Team_Requirements (2)/00-Design-System/`
- Reference app shape: `HyperNova_Launcher_Task_01/`
</content>
</invoke>
