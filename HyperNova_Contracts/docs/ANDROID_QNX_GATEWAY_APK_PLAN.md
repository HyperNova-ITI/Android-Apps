# Android ↔ QNX Vehicle Gateway APK Plan

Status: **implementation plan; QNX/TC397 wire fields pending final interface from Mostafa**

## Decision

Implement the Android side as a standalone headless APK, `com.hypernova.vehiclegateway`. It is not
an AOSP framework service, does not modify CarService or VHAL, and does not require a custom Android
API. Feature apps bind to its signature-permission-protected AIDL service; the APK owns the one
authenticated network session to the QNX guest service.

```text
NOVA Android ──AIDL──► Navigation / Climate domain app
                                      │
                                      │ typed domain request
                                      ▼
                         Vehicle Gateway APK
                         com.hypernova.vehiclegateway
                                      │
                                      │ versioned authenticated TCP
                                      ▼
                             QNX gateway service
                                      │
                                      │ final TC397 binary TCP/UDP interface
                                      ▼
                                   TC397
```

Only QNX communicates with the final TC397 interface. The Android gateway never opens the TC397
command socket and never encodes TC397 frames.

## Why this needs no AOSP modification

- Development image: install the gateway with `adb install` and sign all HyperNova integration APKs
  with the same project release key.
- Final image: preinstall the unchanged APK as a product/system package if persistent early startup is
  required. Packaging and permission allow-list configuration are image integration, not a change to
  Android framework, CarService, VHAL, or application source APIs.
- The gateway defines its own signature permission. It does not need the platform certificate unless
  the final integration later grants a platform-defined privileged permission.
- Climate and NOVA keep their frozen public AIDL. Only Climate's internal backend changes from the
  laptop stub to a gateway AIDL client.

Android bound services are the primary lifecycle mechanism. On the laptop, the socket exists while at
least one domain service is bound. Do not rely on an unrestricted boot-started `dataSync` foreground
service: Android 15/16 limits background foreground-service starts and long-running data-sync work.
If continuous boot telemetry becomes a target requirement, preinstall the gateway and agree the
system-image startup/power allow-list with the Android image owner; keep reconnect behavior correct
even when Android kills and recreates the process.

Official platform references:

- <https://developer.android.com/develop/background-work/services/bound-services>
- <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>
- <https://source.android.com/docs/automotive/security/vehicle_system_isolation>

## APK responsibilities

The gateway owns:

- QNX address/port configuration for development and target builds;
- one socket lifecycle with connect timeout, exponential backoff, heartbeat, and clean reconnect;
- request ID/sequence correlation and one-command-in-flight serialization if QNX requires it;
- strict frame length and message schema validation before dispatch;
- response timeout and deterministic `confirmed/rejected/unavailable/timeout` mapping;
- an in-memory latest telemetry snapshot and AIDL listener fan-out;
- Binder death cleanup, bounded queues, deduplication, rate limiting, and diagnostics;
- peer authentication, integrity, replay protection, and encryption for the production channel.

It does not own:

- natural-language interpretation or LLM routing;
- Climate domain capability/range policy;
- TC397 binary framing/CRC;
- physical actuation or authoritative fault clearing;
- UI.

## Contract shape

Do not freeze the previous `domain + operation + opaque argsJson` draft. Keep extensibility with a
versioned envelope, but validate each operation against a compiled schema registry. Unknown domains,
operations, keys, enum values, sizes, or protocol versions are rejected locally and again by QNX.

The final AIDL is generated only after Mostafa provides the finalized TC397/QNX interface. Expected
surface:

```text
getApiVersion()
getConnectionState()
getLatestVehicleState()
submitClimateCommand(typed/versioned request, callback)
registerVehicleStateListener(listener)
unregisterVehicleStateListener(listener)
```

The first supported state is expected to contain:

- measured temperature;
- simulated fuel level percentage;
- active fault ID/state and fault-created/fault-cleared events;
- requested and confirmed fan level/PWM state for the two-fan blower group;
- freshness timestamp and QNX/TC397 connection health.

There is deliberately no AI-facing `clearFault` operation. The TC397 hardware clear button remains
authoritative.

## Process and package security

- Export exactly one AIDL service guarded by
  `com.hypernova.permission.CONTROL_COCKPIT_APPS` with protection level `signature`.
- Use explicit component binding and reject an unexpected bind action.
- Verify caller UID/package/signing identity in addition to the manifest permission for sensitive
  methods.
- Run networking on one dedicated coroutine/executor; never block Binder or Android main threads.
- Keep QNX credentials/certificates outside the APK source tree. Development may use a loopback
  unauthenticated transport only behind an explicit debug build flag.
- Accept no shell strings, model text, arbitrary CAN/TC397 frames, or unbounded JSON.
- Redact request content from release logs and expose health counters rather than secrets/payloads.

## Implementation phases

### Phase 0 — receive and map the final interface

Required input from Mostafa:

- QNX-facing host/port and whether it is TCP only or TCP plus UDP;
- complete TC397 command, ACK, rejection, telemetry, and fault definitions;
- sequence, CRC, byte order, timing, retry, and one-in-flight rules;
- temperature units/range, fuel ADC mapping, fan levels/PWM mapping, and fault catalogue;
- which checks live in TC397 and which QNX must repeat.

Output: a reviewed Android↔QNX schema and mapping table. No transport implementation is frozen before
this phase completes.

### Phase 1 — module and AIDL

- Create `HyperNova_VehicleGateway_Task_10` as a headless Android 16 application.
- Add the reviewed gateway AIDL to the shared Contracts module.
- Add the signature permission, explicit bind action, connection/state parcelables, and callbacks.
- Unit-test every validation boundary.

Exit: Climate and a test client can bind and receive a deterministic disconnected state.

### Phase 2 — loopback transport

- Implement the socket state machine and request correlation against a laptop fake-QNX server.
- Test fragmented frames, malformed sizes, unknown versions, duplicate sequence IDs, timeouts,
  reconnect, stale telemetry, and command rejection.
- Publish temperature, simulated fuel, fault, and fan state through AIDL.

Exit: emulator tests the complete APK without QNX, TC397, or AOSP changes.

### Phase 3 — Climate backend

- Implement `VehicleGatewayClimateBackend` as an AIDL client.
- Map the two physical fans to one blower-group `setFanLevel` unless the final interface exposes
  independent zones.
- Report confirmation only from the QNX/TC397 result, never from Binder delivery.
- Leave unsupported auto/recirculation/defrost capabilities false.

Exit: Climate UI and NOVA both drive the fake-QNX server and render confirmed/rejected results.

### Phase 4 — QNX integration

- Point the debug gateway build at the QNX virtio-net address.
- Run the contract corpus against the QNX service before attaching TC397.
- Then verify one command at a time through QNX to TC397 and compare returned state with telemetry.

Exit: temperature, simulated fuel, physical fault events, fault clear, and fan speed all round-trip.

### Phase 5 — final image integration

- Sign/preinstall the gateway APK and its clients with the agreed release signing arrangement.
- Configure only the required package/permission and process-lifecycle allow-lists.
- Disable debug transport, debug/ADB assumptions, and plaintext credentials.
- Run reconnect, reboot, malformed-peer, flood, stale-state, and kill-switch tests.

Exit: identical APK/application contracts run on Android 16 without framework or VHAL source changes.

## Work that can proceed before the final interface arrives

- Create the Gradle/headless APK skeleton.
- Define connection-state and health models that contain no TC397-specific fields.
- Build the socket lifecycle behind a replaceable codec interface.
- Build a fake-QNX server and failure tests.
- Keep command/state AIDL fields pending until the final interface mapping is reviewed.

