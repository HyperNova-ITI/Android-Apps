# HyperNova Vehicle Gateway Protocol v1

Status: **frozen for the integration demo**

Date: 2026-08-12

TC397 implementation baseline: `HyperNova-ITI/TriCore_Node` commit `b7a88ea`
(`origin/feat/real-dtcs-buttons-sensors`)

## 1. Boundary and ownership

```text
Android feature app -> typed AIDL -> Vehicle Gateway APK
                                      |
                                      | HNVG v1 TCP
                                      v
                              QNX gateway service
                                      |
                         TC397 TCP :6001 + UDP :6000
                                      v
                                   TC397
```

- The QNX service is the only TC397 TCP client and the only component that encodes TC397 frames.
- The Vehicle Gateway APK is the only Android client of the QNX service.
- Climate owns HVAC validation and its NOVA-facing `IClimateCommandService`.
- NOVA never sends arbitrary bytes, JSON arguments, shell strings, or TC397 frames.
- Navigation and Phone remain Android-domain services and do not use the vehicle gateway.

The laptop relay used during development is the same portable service built for QNX. Only its
listen address changes when it moves from Linux to the QNX guest.

## 2. Bench addresses

| Node | Address | Role |
|---|---|---|
| Laptop relay today / QNX final | `192.168.10.10/24` on the TC397 network | TC397 command authority and telemetry receiver |
| RPi5 | `192.168.10.20/24` | NOVA voice node |
| TC397 | `192.168.10.30/24` | TCP server `6001`; UDP telemetry producer to `.10:6000` |
| Android emulator -> laptop | `10.0.2.2:6100` | Emulator alias for the host relay |
| Android guest -> QNX | Build-time configured inter-guest address, TCP `6100` | Final HNVG v1 link |

The Ethernet network has no DHCP, gateway, or DNS. The laptop may keep Wi-Fi as its default route.

## 3. Android/QNX transport

HNVG v1 uses one TCP connection. Commands, results, telemetry, and faults are multiplexed on it.
All integers wider than one byte use network byte order (big-endian).

```text
0               4 5 6       8          12    14    16
+----------------+-+-+-+-+-+-+-+-+----------+-----+-----+
| magic "HNVG" |v|type| flags   |corr_id   | len | rsv |
+----------------+-+-+-+-+-+-+-+-+----------+-----+-----+
| payload (len bytes, maximum 512)                       |
+--------------------------------------------------------+
```

| Field | Size | Rule |
|---|---:|---|
| magic | 4 | ASCII `HNVG`; mismatch closes the session |
| version | 1 | `1`; unknown versions are rejected |
| type | 1 | Message type below |
| flags | 2 | Must be zero in v1 |
| correlation ID | 4 | Non-zero for commands/results; zero for unsolicited state/fault messages |
| payload length | 2 | `0..512`; larger values close the session |
| reserved | 2 | Must be zero |

TCP supplies delivery and corruption detection for the demo. The production inter-guest channel
must add mutually authenticated TLS (or an equivalent QNX platform-secured channel) before the
network leaves an isolated bench. The protocol deliberately contains no credential or key material.

## 4. Message types

| Type | Name | Direction | Payload |
|---:|---|---|---|
| `0x01` | `HELLO` | Android -> QNX | `[api_hi][api_lo][capabilities u32]` |
| `0x02` | `PING` | either | empty |
| `0x10` | `SET_HVAC` | Android -> QNX | `[target_temp int8][fan 0..5][zone 0..2][caller 0..1]`; target is `16..28` when fan > 0 and ignored for fan 0 |
| `0x20` | `GET_STATE` | Android -> QNX | empty |
| `0x81` | `HELLO_ACK` | QNX -> Android | `[api_hi][api_lo][tc_protocol_version u16]` |
| `0x82` | `PONG` | either | empty |
| `0x90` | `COMMAND_RESULT` | QNX -> Android | 12-byte result block below |
| `0xA0` | `VEHICLE_STATE` | QNX -> Android | 14-byte state block below |
| `0xA1` | `FAULT_EVENT` | QNX -> Android | `[dtc_hi][dtc_lo][state][tc_event_seq]` |

No generic domain/operation/`argsJson` method exists in v1. Adding a command requires a reviewed,
bounded payload and a new message type. This prevents an Android caller from turning the gateway
into an arbitrary actuator/frame tunnel.

### 4.1 Command result

```text
[command_type][status][tc_reject_reason][reserved]
[target_temp][fan][zone][caller][tc_seq][reserved x3]
```

`status` matches `HyperNovaContract`: `1 accepted`, `2 confirmed`, `3 rejected`, `4 unavailable`,
`5 timeout`. A TC397 `EVT_CMD_ACK` is the only source of `confirmed`. A successful Binder call,
socket write, or QNX queue insertion is never confirmation.

TC397 rejection reasons remain `0x01..0x09`. Relay-local reasons are `0xE1 BUSY`,
`0xE2 TC397_UNAVAILABLE`, `0xE3 INVALID_GATEWAY_REQUEST`, and `0xE4 TC397_TIMEOUT`. The `status`
field remains authoritative; consumers must not present a local reason as a controller rejection.

### 4.2 Vehicle state

```text
[cabin_temp int8][humidity][fuel]
[zone1_target int8][zone2_target int8][zone1_fan][zone2_fan]
[active_dtc_mask][flags][telemetry_age_ms u32][last_tc_event_seq]
```

Flags: bit 0 `TC397_CONNECTED`, bit 1 `TELEMETRY_FRESH`. Telemetry is fresh for 3000 ms. Fan values
are the last TC397-acknowledged requested levels; the two-wire fans have no tachometer, so they must
not be presented as measured RPM.

`active_dtc_mask` maps bits 0..4 to `P0217`, `P0118`, `P0300`, `P0442`, and `P0562` respectively.

## 5. TC397 wire contract implemented by the QNX adapter

TC397 frames are:

```text
[CMD_TYPE][SEQ][LEN][PAYLOAD][CRC16_LO][CRC16_HI]
```

CRC-16/CCITT-FALSE uses polynomial `0x1021`, initial value `0xFFFF`, no reflection, no final XOR,
and covers `CMD_TYPE + PAYLOAD` only. CRC bytes are little-endian.

| Type | Payload |
|---:|---|
| `0x01 CMD_SET_HVAC` | `[target 16..28][level 0..5][zone 0..2][caller 0..1]` |
| `0x10 CMD_REQUEST_SENSORS` | empty |
| `0x80 EVT_SENSOR_DATA` | `[temperature][humidity][fuel]` |
| `0x81 EVT_FAULT_EVENT` | **`[DTC high][DTC low][state]`**, exactly 3 bytes |
| `0x82 EVT_CMD_REJECTED` | `[original command][reason 0x01..0x09]` |
| `0x83 EVT_CMD_ACK` | `[original command]` |

No other TC397 command is part of the HyperNova product contract. Ambient lighting is a separate
RPi5 feature and does not traverse this gateway.

The current DTCs are real SAE J2012 values:

| Mask bit | Wire value | DTC | Demo effect |
|---:|---:|---|---|
| 0 | `0x0217` | P0217 engine coolant over-temperature | HVAC blocked; fans forced off |
| 1 | `0x0118` | P0118 coolant temperature sensor circuit high | AI HVAC blocked; driver open-loop allowed |
| 2 | `0x0300` | P0300 random/multiple misfire | HVAC fan level derated to maximum 2 |
| 3 | `0x0442` | P0442 small EVAP leak | advisory |
| 4 | `0x0562` | P0562 system voltage low | all HVAC blocked; fans forced off |

## 6. Serialization and recovery

- QNX permits one Android session and TC397 permits one QNX TCP session.
- QNX allows one TC397 command in flight. A concurrent request returns `BUSY`.
- QNX correlates ACK/rejection by TC sequence and original command type.
- A response that has no current matching request is logged and ignored; this protects a new
  session from a retained asynchronous rejection carrying an old sequence.
- After reconnecting to TC397, QNX drains retained fault events before accepting a new command.
- Android deduplicates domain `requestId` values for ten minutes; QNX correlation IDs are per
  connection and never treated as globally unique.
- A command times out after 5 seconds. The gateway does not automatically retry an actuation after
  an ambiguous disconnect.
- TC397 currently shares one sequence counter across TCP fault events and UDP telemetry. The gateway
  logs sequence observations but does not infer a lost fault from UDP gaps or reordering. Fault
  clearing remains physical-button-only.

## 7. Known TC397 constraints

- Commit `b7a88ea` is one commit ahead of `main`; TC397 `main` is not the flashed source of truth yet.
- The older vault/TC397 handoff files still describe one-byte/two-fault events. That text is stale;
  the three-byte SAE DTC payload in `frame_codec.h` and `Test/hnc_bench.py` is authoritative.
- Telemetry scheduling currently uses a loop counter (`tick_count >= 100000`) rather than elapsed
  STM time. Its documented 1 Hz rate is therefore not guaranteed and should be fixed before final.
- `CMD_SET_HVAC` currently rejects payloads shorter than four bytes but accepts payloads longer than
  four; final firmware should require `len == 4`.
- The CRC does not cover `SEQ` or `LEN`. This is a known desynchronization weakness and any change
  must be coordinated with the QNX codec.
- Watchdogs are disabled in the demo firmware. This is acceptable only for bench/demo scope, not a
  functional-safety claim.

## 8. Minimum security/safety posture

- Isolated wired VLAN/switch for the demo; no port-forwarding from Wi-Fi.
- Signature permission plus signing-identity check on the Android Binder service.
- One allow-listed, length-bounded typed command; no model text reaches QNX or TC397.
- TC397 remains the physical safety authority and may reject any upstream request.
- AI actions are limited to infotainment/HVAC demo scope. No braking, steering, propulsion, or
  safety-critical body control is exposed.
- Debug plaintext and verbose payload logs are disabled in release builds.
