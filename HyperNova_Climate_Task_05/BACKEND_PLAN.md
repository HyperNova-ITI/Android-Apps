# HyperNova Climate — Backend Implementation Plan

> Scope: connect the Climate Android app to the **real** vehicle functionality,
> which is whatever the **TC397 bare-metal node** actually implements. Everything
> the TC397 does *not* do stays **icons-only** (app-local visual state, no vehicle
> command) for the demo.
>
> Sources read: `TriCore_Node/docs/TC397-ClimateControl.md`,
> `TC397-Networking.md`, `.../TC397-Sensors.md`, `.../NXP-TC397-Message-Format.md`;
> `HyperNova_Contracts/.../climate/*` (AIDL + `ClimateState/Capabilities/Result`,
> `HyperNovaContract`, `ClimateContract`).

---

## 1. What the TC397 really does (the demo's real climate functionality)

One command, telemetry, and events. That's the whole climate surface.

**Command — `setHvac` (CMD_TYPE `0x01`), 4-byte payload** (finalized
`TC397-ClimateControl.md`):

| Byte | Field | Range | Meaning |
|---|---|---|---|
| 0 | `target_temp_c` | **16–28, int8 (whole °C)** | target cabin temp; ignored when `level=0` |
| 1 | `level` | **0–5** | fan speed; `0` = off, 1–5 = 20 %–100 % |
| 2 | `zone` | `0`=both, `1`=zone 1, `2`=zone 2 | which cabin zone |
| 3 | `caller` | `0`=Driver, `1`=AI Companion | who issued it (authorization) |

- **Two independently-regulated zones**, one fan each. Each holds its own
  setpoint via a hysteresis thermostat (±2 °C). Regulation is continuous — once a
  setpoint is accepted the node maintains it, no repeated commands needed.
- **Cooling only.** The hardware is a fan, not a heater — there is **no A/C
  compressor, no heat, no AUTO, no recirculation, no defrost, no seat heating**
  on the TC397.

**Events (TC → app):**
| Code | Event | Payload |
|---|---|---|
| `0x83` | `EVT_CMD_ACK` | `[orig_cmd_type]` |
| `0x82` | `EVT_CMD_REJECTED` | `[orig_cmd_type][reason]` |
| `0x81` | `EVT_FAULT_EVENT` | `[fault_code]` |
| `0x80` | `EVT_SENSOR_DATA` | cabin **temp + humidity** (DHT11), + fuel, seat *(byte layout not finalized)* |

Rejection reasons: `0x02` invalid length · `0x03` out-of-range · `0x04` safety
blocked · `0x06` hardware fault · `0x07` overheat · `0x08` sensor fault (AI only)
· `0x09` actuator not ready. Fault codes: `0x01` overheat (fans forced off) ·
`0x02` cabin-temp sensor fault (open-loop).

**Sensors that exist:** cabin temperature + humidity (DHT11). **That's it for
climate.** There is **no outside-temperature sensor and no air-quality sensor.**

**Transport** (`TC397-Networking.md`): board `192.168.10.30`, app `192.168.10.10`,
point-to-point. **TCP :6001** commands/events (one client), **UDP :6000**
telemetry. Frame: `CMD_TYPE | SEQ | LEN | PAYLOAD | CRC_LO | CRC_HI`,
CRC-16/CCITT-FALSE over `CMD_TYPE+PAYLOAD`, little-endian; `SEQ` correlates
replies.

---

## 2. What maps to real backend vs. stays icons-only

| App control | Backed by TC397? | Mapping |
|---|---|---|
| Driver target temp | ✅ | `setHvac(temp, level, zone=1, caller)` |
| Passenger target temp | ✅ | `setHvac(temp, level, zone=2, caller)` |
| Fan level | ✅ | `level` in `setHvac` (shared → `zone=0`, or per-zone) |
| Power on/off | ✅ (derived) | off = `level=0`; on = restore last level |
| Cabin temperature (readout) | ✅ | from `EVT_SENSOR_DATA` |
| Zone sync | ➖ app-local | mirrors driver→passenger in the app, then sends both |
| **A/C (cool/heat)** | ❌ icons only | no compressor on TC397 |
| **AUTO** | ❌ icons only | no auto controller |
| **Fresh air / Recirculate** | ❌ icons only | no flap actuator |
| **Airflow direction** | ❌ icons only | no vent actuator |
| **Front/Rear/Max defrost** | ❌ icons only | not implemented |
| **Seat heating** | ❌ icons only | not implemented |
| **Air quality** | ❌ icons only (or humidity-derived) | no AQ sensor; humidity exists |
| **Outside temperature** | ❌ unavailable | no exterior sensor |

So the **backend only needs to implement**: per-zone temperature, fan level,
power(=level 0), and reading cabin temp/humidity + ACK/reject/fault. Everything
else the UI already toggles locally — leave it.

---

## 3. Contract gaps — what we should change (and open questions)

The frozen `ClimateState`/`ClimateCapabilities`/AIDL don't fully fit the TC397.
Recommended changes (coordinate the parcelable changes with the NOVA team, since
it's a shared ABI — treat additive changes as **API v2**):

**Should change / add:**
1. **`ClimateState` has no cabin-temperature field.** It carries *target* temps
   but not the *measured* cabin temp — yet that's the one live sensor value the
   UI/agent care about. **Add `cabinTemperatureC` (float, NaN = unavailable).**
   Without this, NOVA/Launcher can't answer "what's the cabin temperature?"
2. **Add `humidityPercent` (int, -1 = unavailable)** — the DHT11 provides it for
   free; useful to the agent and as a stand-in for "air quality."
3. **Fault visibility.** `ClimateState.availability` is a single int; it can't
   distinguish *overheat* vs *sensor-fault* vs *comms-lost*. **Add
   `faultCode` (int)** (0 = none, mirror TC397 fault codes) so the app/agent can
   show the real condition.
4. **Capabilities values are wrong for the TC397** (they're data, not schema —
   just set them right): `supportsAc=false`, `supportsAuto=false`,
   `supportsRecirculation=false`, `zoneMode=DUAL`, `min=16`, `max=28`,
   **`temperatureStepC=1.0`** (TC397 temp is whole-degree int8 — **not 0.5**),
   `maximumFanLevel=5`.
5. **Per-zone fan?** The contract `fanLevel` is single, but the TC397 regulates a
   fan per zone. Decide: keep single (simplest; `zone=0` both) **or** add
   `passengerFanLevel`. Recommend **single for the demo**.

**Can stay as-is (don't break the frozen ABI):**
- `setAcEnabled` / `setAutoModeEnabled` / `setRecirculationEnabled` map to nothing
  real → keep the methods, advertise them unsupported, and return
  `STATUS_REJECTED / UNSUPPORTED_OPERATION`. No AIDL change needed.
- No `caller` param on the AIDL setters — the **service infers caller** (UI bind =
  Driver, NOVA bind = AI) and puts it in the `setHvac` byte. No AIDL change.

**Open questions for the TC397 / NXP team (confirm before wiring):**
- **`setHvac` payload width:** finalized ClimateControl.md = **4 bytes with
  `caller`**; the older message-format draft = 3 bytes (no caller). Which is on
  the wire?
- **CRC on/off:** Networking.md mandates CRC-16/CCITT-FALSE; the message-format
  draft calls it optional over TCP/UDP. Confirm we must append it.
- **`EVT_SENSOR_DATA` (0x80) exact byte layout** — order/scale of cabin temp,
  humidity (and whether fuel/seat share the frame).
- **Power semantics:** confirm off = `level 0`, and what "on" restores.
- **Do commands need re-sending to hold a setpoint, or is regulation sticky?**
  (Docs say sticky — confirm.)

---

## 4. Where the Android backend stands today

Built (UI phase): `ClimateBackend` interface (connection lifecycle only),
`VehicleGatewayClimateBackend` + `CarPropertyClimateBackend` **skeletons**,
`ClimateBackendFactory` (macro selects Ethernet), internal models, and a
`ClimateViewModel` driving the UI from **local optimistic state** (no vehicle).

**Missing (everything below).**

---

## 5. Backend implementation plan

### Phase A — TC397 transport (`backend/transport/`)
1. **Frame codec** — encode/decode `CMD_TYPE|SEQ|LEN|PAYLOAD|CRC16`; CRC-16/
   CCITT-FALSE; unit-test against known vectors.
2. **TCP command client** — connect `192.168.10.30:6001` (single connection),
   reassemble split/merged frames, send frames, receive events; `SEQ`
   correlation; auto-reconnect with backoff; expose link state.
3. **UDP telemetry listener** — bind `:6000`, parse `EVT_SENSOR_DATA` → cabin
   temp + humidity.
4. **Command/ACK state machine** — send `setHvac`; wait ACK (`0x83`) ≤ 5 s →
   *confirmed*; `EVT_CMD_REJECTED` (`0x82`) → *rejected(reason)*; no reply →
   *timeout*; async `HARDWARE_FAULT` correlated by `SEQ`. Parse `EVT_FAULT_EVENT`
   into fault state.

### Phase B — Backend + repository (`backend/`, single app-scoped source of truth)
5. Flesh out **`VehicleGatewayClimateBackend`** over the transport: expose
   `StateFlow<ClimateState>` + `StateFlow<ClimateCapabilities>`, `suspend
   execute(command)`, `refreshState()`.
6. **`ClimateRepository`** — merges confirmed command results + telemetry into the
   authoritative internal `ClimateState`; holds per-zone `(targetTemp, fanLevel)`,
   cabin temp, humidity, fault. Every temp/fan/power change re-sends the full
   `setHvac(temp, level, zone, caller)` triple (the command couples them).
7. **Capability mapping** — real values from §3.4 (dual-zone, 16–28 step 1, fan 5,
   AC/Auto/Recirc = false).
8. **Confirmation discipline** — UI's confirmed value only updates on ACK/readback
   (never optimistic in production paths).

### Phase C — Wire the UI to the backend
9. Replace the ViewModel's seeded local state with the repository `StateFlow`.
   Temp ±, fan ±, power route through the repository → TC397 → ACK → confirmed →
   UI. Airflow/A-C/sync/defrost/seat/fresh-recirc stay **local UI toggles**.
10. Render pending / rejected / timeout / comms-lost / overheat / sensor-fault
    states from the real fault + availability.

### Phase D — NOVA command service (the frozen contract)
11. Implement **`ClimateCommandService`** (AIDL) per
    `HyperNova_Contracts/docs/MAHGOUB_CLIMATE_SERVICE_GUIDE.md`, sharing the **same
    repository** as the UI: `getCapabilities`, `getCurrentState`,
    `setPowerEnabled`, `setTargetTemperature(zone, °C)`, `setFanLevel`. Return
    `UNSUPPORTED_OPERATION` for `setAcEnabled/setAutoModeEnabled/
    setRecirculationEnabled`. Tag `caller = AI`.
12. Signature permission `CONTROL_COCKPIT_APPS`, request dedup (10 min), 2 s query
    / 5 s mutation timeouts, concise driver-safe messages.

### Phase E — Resilience & tests
13. Binder-death handling, comms-loss freeze + stale marking, background reconnect.
14. Tests: CRC vectors, frame reassembly, ACK/reject/timeout, range clamping
    (16–28, whole °C), power=level 0, capability mapping, duplicate `requestId`,
    disconnect → unavailable, UI/NOVA/Launcher show the same confirmed state.

---

## 6. Immediate app-side fix (independent of the backend)
The UI currently uses a **0.5 °C step and 16–30 range**; the TC397 is
**whole-degree 16–28**. Change the preview capabilities + temperature stepping to
**step 1.0 °C, min 16, max 28** so the app matches what the vehicle can accept.
