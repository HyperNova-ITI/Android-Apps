# Vehicle Network Architecture — Frozen Decision v1

Status: **FROZEN architecture, protocol details DRAFT** (needs QNX-side agreement before coding starts)

Owners: Mustafa/AbdelFattah (NXP gateway, QNX-side network service), Mahgoub (Climate, first consumer),
future consumers (Driver Profile — seat, Settings — ambient LED).

This document replaces "Option A / Option B" in
`HyperNova_Climate_Task_05/IMPLEMENTATION_PLAN.md` §1 with a single answer that all Android apps use,
not just Climate.

---

## 1. Problem this solves

Two facts that were previously handled per-app (Climate opening its own socket) don't scale:

1. **TC397 accepts exactly one command TCP client at a time** (`TC397-Networking.md`: "Concurrent
   command clients: 1"). `CMD_SET_HVAC` (Climate) is live today; `CMD_SET_SEAT`, `CMD_SET_TRUNK`,
   `CMD_SET_AMBIENT_LED` are "interface reserved" — meaning Driver Profile and Settings will need the
   same wire eventually. If each app opens its own socket, the second app to connect breaks the first.
2. **The Android guest has no direct route to the TC397 point-to-point link** (192.168.10.10/.30).
   That physical Ethernet segment belongs to the QNX host (`NXP-Networking.md`: "Raw L2 socket... to
   TC397" is a QNX-side task). Android only gets an inter-guest virtio-net link to QNX, which is a
   separate to-do item in the same doc. `VehicleGatewayClimateBackend`'s current stub
   (`BuildConfig.TC397_HOST`/`TC397_COMMAND_PORT` pointed straight at the board) only works in a
   dev/bring-up setup where a laptop or emulator sits on the same physical segment as TC397 — it will
   not work once Android is actually running as a QNX guest.

Both problems are solved the same way: **one Android-side broker owns the vehicle link; the TC397
wire protocol is terminated on the QNX side.**

---

## 2. Decision summary

| Question | Decision |
|---|---|
| Who speaks the raw TC397 frame protocol (CMD_TYPE/SEQ/LEN/PAYLOAD/CRC16)? | **QNX guest network service.** It owns the one real TCP :6001 / UDP :6000 socket to TC397. |
| What does Android talk to QNX over? | **Plain TCP/UDP over the inter-guest virtio-net link** (not vsock, not SOME-IP — SOME-IP stays reserved for NXP↔RPi5 per `SOME-IP-Catalog.md`). |
| Where does the Android-side broker live? | **A standalone Android service (`com.hypernova.vehiclegateway`)**, built now, shared by every feature app from day one — not folded into Climate and extracted later. |

The no-framework-modification APK lifecycle, security boundary, loopback strategy, and implementation
phases are specified in [ANDROID_QNX_GATEWAY_APK_PLAN.md](ANDROID_QNX_GATEWAY_APK_PLAN.md). That plan
does not freeze protocol fields before the final TC397/QNX interface is supplied.

---

## 3. Full architecture

```text
TC397 (bare metal)
  ▲  TCP :6001 CMD_SET_HVAC / EVT_CMD_ACK / EVT_CMD_REJECTED / EVT_FAULT_EVENT
  │  UDP :6000 EVT_SENSOR_DATA
  ▼  (single client — TC397-Networking.md)
QNX Guest Network Service                              [NXP gateway node — Mustafa/AbdelFattah]
  - Owns the only TCP/UDP client to TC397.
  - Speaks CMD_TYPE|SEQ|LEN|PAYLOAD|CRC16(CCITT-FALSE) — frame codec lives HERE, not on Android.
  - Serializes/queues commands (TC397 replies to one in-flight command at a time).
  - Exposes a higher-level Vehicle Gateway Protocol (§5) to the Android guest.
  ▲  plain TCP (command/result) + TCP or UDP (telemetry push), JSON Lines, over inter-guest virtio-net
  ▼  (NXP-Networking.md "inter-guest virtual networking between AAOS and QNX RTOS" — still a to-do;
      this is the dependency that gates everything below)
OurCustomNetworkService = HyperNova Vehicle Gateway     [new standalone Android component]
  package: com.hypernova.vehiclegateway
  - The ONLY Android-side client of the QNX guest network service.
  - Owns reconnect/backoff to QNX, not to TC397.
  - Exposes AIDL to every other Android app (§6): generic command pass-through + telemetry
    subscription. Does not know what "HVAC" or "seat" mean — domain apps own that.
  ▲  AIDL bind, signature-permission-protected (same CONTROL_COCKPIT_APPS permission already used
      for the Climate/Navigation command services)
  ▼
Feature apps (domain logic + validation stay here, unchanged from today's plan)
  - Climate (com.hypernova.climate) — CMD_SET_HVAC, live today.
  - Driver Profile (future) — CMD_SET_SEAT, once TC397 firmware implements it.
  - Settings (future) — CMD_SET_AMBIENT_LED, CMD_SET_TRUNK, once implemented.
```

### What does NOT change

- **NOVA/Pi voice path is untouched.** Pi agent → NOVA Android (TCP 8765 JSON) → AIDL to
  `IClimateCommandService`/`INavigationCommandService` → domain app validates → domain app talks to
  its backend. `NAVIGATION_CLIMATE_COMMAND_HANDOFF.md`'s rule "NOVA never contacts TC397 directly;
  Climate is the only owner of that path" still holds — Climate's path just now runs through the
  shared gateway service instead of a raw socket.
- **`IClimateCommandService` / `IClimateCommandCallback` / `ClimateContract` AIDL is frozen and
  unaffected.** This document only changes what sits *behind* Climate's internal
  `ClimateRepository → ClimateBackend → Tc397ClimateBackend` boundary (`MAHGOUB_CLIMATE_SERVICE_GUIDE.md`
  §5), which was always meant to be swappable.
- **Navigation is unaffected** — it has no TC397 dependency.

---

## 4. Component responsibilities

| Component | Owns | Does not own |
|---|---|---|
| TC397 | Physical actuation, safety authorization, fault/reject reasons | Any Ethernet peer beyond its single TCP client |
| QNX guest network service | Frame codec, CRC, SEQ correlation, ACK/reject/fault parsing, the single TC397 socket, translating to/from the Vehicle Gateway Protocol | Domain validation (temp ranges, capability gating) |
| Android Vehicle Gateway (`com.hypernova.vehiclegateway`) | The one Android-side socket to QNX, reconnect/backoff, AIDL fan-out to N feature apps, request correlation, connection-state broadcast | Frame encoding, domain validation, UI |
| Climate app | Capability mapping, validation, confirmation semantics, `IClimateCommandService` for NOVA | Any socket to QNX or TC397 |
| Driver Profile / Settings (future) | Same shape as Climate, once their TC397 commands exist | Same |

This is the same "thin transport, fat domain app" separation `MAHGOUB_CLIMATE_SERVICE_GUIDE.md`
already established for Climate — it now also applies one layer lower, between the gateway service and
QNX.

---

## 5. Vehicle Gateway Protocol (Android ↔ QNX) — DRAFT, needs QNX-team sign-off

Mirrors the JSON-Lines shape already used for Pi↔NOVA (`NOVA_RUNTIME_PROTOCOL.md`) for consistency
across the codebase, rather than inventing a third message format.

Command channel (TCP, one connection, Android is the client, QNX is the server):

```json
{"type":"vehicle_command","v":1,"seq":101,"request_id":"...","domain":"climate","operation":"set_hvac",
 "args":{"zone":0,"target_temp_c":22,"level":3,"caller":0}}
```

```json
{"type":"vehicle_result","v":1,"seq":201,"request_id":"...","domain":"climate","operation":"set_hvac",
 "status":"confirmed","error":"","message":"applied"}
```

`status` reuses `HyperNovaContract.STATUS_*` names (`accepted/confirmed/rejected/unavailable/timeout`).
`error` reuses `HyperNovaContract.ERROR_*` plus the TC397 rejection reasons
(`HARDWARE_REJECTED`, `SAFETY_BLOCKED`, `OVERHEAT_BLOCKED`, `SENSOR_FAULT_BLOCKED`, mapped from
`TC397-ClimateControl.md`'s rejection codes `0x04/0x06/0x07/0x08/0x09`).

Telemetry channel (QNX → Android, fire-and-forget, matches TC397's own UDP telemetry semantics):

```json
{"type":"vehicle_telemetry","v":1,"domain":"climate","data":{"cabin_temp_c":23.5,"humidity_pct":41}}
```

```json
{"type":"vehicle_fault","v":1,"domain":"climate","fault_code":1,"message":"engine overheat"}
```

Open items for the QNX team:

- Confirm the fixed relay port(s) for this JSON channel over the inter-guest virtio-net link.
- Confirm one command in flight at a time is enforced on the QNX side (matches TC397's own
  one-client model) or whether the gateway service queues.
- `domain`/`operation` naming above is a starting proposal — finalize together, same spirit as the
  frozen `IClimateCommandService` naming.

---

## 6. Android-side AIDL contract (add to `HyperNova_Contracts`)

New package: `com.hypernova.contracts.vehiclegateway`

```aidl
interface IVehicleGatewayService {
    int getApiVersion();
    int getConnectionState(); // DISCONNECTED / CONNECTING / CONNECTED — mirrors ClimateConnectionState

    void sendCommand(
        String requestId,
        String domain,       // "climate", "seat", "trunk", "ambient_led"
        String operation,    // "set_hvac", "set_position", ...
        String argsJson,     // opaque to the gateway; domain app encodes/decodes it
        IVehicleGatewayCallback callback
    );

    void registerTelemetryListener(String domain, IVehicleTelemetryListener listener);
    void unregisterTelemetryListener(String domain, IVehicleTelemetryListener listener);
}

oneway interface IVehicleGatewayCallback {
    void onResult(String requestId, int status, String error, String resultJson);
}

oneway interface IVehicleTelemetryListener {
    void onTelemetry(String telemetryJson);
    void onFault(int faultCode, String message);
}
```

Deliberately generic (`domain`/`operation`/JSON payload) rather than typed per-command: the gateway
service must not need a new AIDL method every time TC397 gains a command. Domain apps (Climate today)
own the typed internal model and only serialize/deserialize at this boundary — same discipline as
`ClimateCommandService` already applies at the NOVA boundary.

Bind action: `com.hypernova.vehiclegateway.action.BIND_GATEWAY`, protected by the existing
`com.hypernova.permission.CONTROL_COCKPIT_APPS` signature permission — no new permission needed.

---

## 7. What changes in the Climate app

- `VehicleGatewayClimateBackend` (currently a stub that logs `BuildConfig.TC397_HOST`) is rewritten to
  bind to `IVehicleGatewayService` instead of opening a socket. `ClimateBackendFactory`'s
  `BackendMode.ETHERNET` case is retargeted at this implementation; no other app-layer code changes.
- `IClimateCommandService`, `ClimateCommandService`, capability/state mapping — **unchanged**. They
  still sit above `ClimateBackend` exactly as `MAHGOUB_CLIMATE_SERVICE_GUIDE.md` describes.
- `IMPLEMENTATION_PLAN.md` Phase 6 ("reusable TC397 transport: frame encode/decode, CRC-16...") is now
  QNX-side work, not Climate-side. Phase 7 becomes "bind to the Vehicle Gateway AIDL and map
  `execute()` to `sendCommand(domain="climate", ...)`" instead of owning a socket.

---

## 8. Roadmap

Phased so each step is independently demoable, same style as `IMPLEMENTATION_PLAN.md`.

### Phase 0 — Agreement (blocking, do first)
- Walk this document with Mustafa/AbdelFattah (QNX/NXP side). Confirm: relay port(s), JSON schema
  in §5, one-command-in-flight behavior, and that inter-guest virtio-net (AAOS↔QNX) lands before
  integration testing needs it.
- **Exit check:** QNX team accepts the protocol shape in §5 (naming can still change), and the
  inter-guest networking task in `NXP-Networking.md` has an owner/date.

### Phase 1 — Contracts
- Add `com.hypernova.contracts.vehiclegateway` (§6) to `HyperNova_Contracts`. Reuse
  `HyperNovaContract` status/error constants; no new permission.
- **Exit check:** module builds; Stub/Proxy generate.

### Phase 2 — Android Vehicle Gateway service skeleton
- New standalone project `HyperNova_VehicleGateway_Task_10` (mirrors Launcher's project shape per
  `01-App-Integration-Contract` conventions, but this is an infrastructure service, not a launchable
  feature — no `HOME`, no Launcher card).
- Implement `IVehicleGatewayService.Stub`: connection lifecycle to a configurable QNX relay
  host/port, JSON Lines send/receive per §5, request correlation (`requestId` → pending callback map,
  same 10-minute dedup cache pattern already used elsewhere), telemetry fan-out to registered
  listeners.
- **Exit check:** service binds from a test client app; round-trips a fake `vehicle_command` against a
  loopback JSON socket stub (test-only).

### Phase 3 — QNX guest network service (QNX-side, Mustafa/AbdelFattah)
- Implement the frame codec/CRC/SEQ/ACK layer against TC397 (reuses `TC397-Networking.md` exactly as
  already scoped for the NXP node).
- Implement the Android-facing JSON relay from §5 over the inter-guest virtio-net link.
- **Exit check:** a raw JSON test client on the Android side can send `set_hvac` and see
  `EVT_CMD_ACK`/`EVT_CMD_REJECTED` round-trip through to a confirmed/rejected JSON result.

### Phase 4 — Climate backend swap
- Rewrite `VehicleGatewayClimateBackend` to bind `IVehicleGatewayService` (§7). Delete the
  direct-socket TODOs and `BuildConfig.TC397_HOST`/`TC397_COMMAND_PORT` (they described the wrong
  peer once Android is a QNX guest).
- **Exit check:** Climate UI drives a real TC397 temperature/fan change end-to-end through the full
  stack (Climate → Gateway service → QNX → TC397 → back).

### Phase 5 — Multi-app proof
- Once TC397 firmware implements a second command (seat or ambient LED), wire that domain's app
  through the same `IVehicleGatewayService` to prove the shared-service design actually avoids the
  single-TCP-client collision it was built to prevent.
- **Exit check:** two feature apps issue commands through the gateway without either losing its TC397
  connection.

---

## 9. Risks

- **Inter-guest virtio-net (AAOS↔QNX) is still an open `NXP-Networking.md` to-do.** Everything in
  Phases 2–4 is blocked on it existing. Flag this as the critical-path item, not "Decide the socket
  owner" from `IMPLEMENTATION_PLAN.md` §3 (that question is now answered by this document).
- **Protocol drift.** §5's JSON schema is a proposal, not yet agreed with the QNX team — do not start
  Phase 2 wire-format code before Phase 0 sign-off, or Android and QNX will build against different
  assumptions the same way the AIDL/VHAL options briefly diverged.
- **One command in flight.** TC397 replies to one in-flight command at a time; the QNX gateway service
  must serialize requests from Android even though Android's own gateway service may receive
  concurrent calls from multiple feature apps.
- **Do not let this block the Climate demo.** If Phase 3 (QNX-side work) slips, Climate can keep
  developing against a local loopback JSON stub of the Phase 2 gateway service — the AIDL boundary in
  §6 is what makes that possible.

---

## 10. Source-of-truth references

- TC397 wire truth: `TriCore_Node/docs/TC397-Networking.md`, `TC397-ClimateControl.md`
- QNX-side networking scope (today): `Claude-Obsidian-Vault-Docs/01-Nodes/Gateway-NXP/NXP-Networking.md`
- System-level link table: `Claude-Obsidian-Vault-Docs/00-Overview/System-Architecture.md`
- Frozen NOVA/Climate/Navigation AIDL: `HyperNova_Contracts/docs/MAHGOUB_CLIMATE_SERVICE_GUIDE.md`,
  `HyperNova_NOVA_AI_Task_02/docs/NAVIGATION_CLIMATE_COMMAND_HANDOFF.md`
- Climate's existing plan (being partially superseded): `HyperNova_Climate_Task_05/IMPLEMENTATION_PLAN.md` §1, Phase 6–7
