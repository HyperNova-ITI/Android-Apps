# HyperNova Vehicle Network Architecture v1

Status: **frozen and implemented for the demo path**
Date: 2026-08-12

This is the authoritative component boundary. The byte-level contract is
[VEHICLE_GATEWAY_PROTOCOL_V1.md](VEHICLE_GATEWAY_PROTOCOL_V1.md).

## Final topology

```text
NOVA Android app
   | typed Climate AIDL
   v
Climate app                         Navigation and Phone stay Android-only
   | typed Vehicle Gateway AIDL
   v
Vehicle Gateway APK (Android 16, headless bound service)
   | HNVG v1, TCP :6100 over Android/QNX virtio-net
   v
HyperNova gateway service (QNX POSIX process)
   | TC397 binary TCP :6001       <- sole command client
   | TC397 binary UDP :6000       <- telemetry receiver
   v
TC397
   - temperature and humidity sensor
   - fuel-level potentiometer
   - fault-create and fault-clear buttons
   - two HVAC fans controlled as one logical blower level
```

The same POSIX gateway executable runs on the laptop during development. Moving it to QNX changes
the build/toolchain and interface addresses, not the HNVG protocol or Android AIDL.

## Supported product surface

The TC397 implementation is the final scope:

- set HVAC target temperature `16..28 C`, fan level `0..5`, zone `both/driver/passenger`, caller
  `driver/AI`;
- receive temperature, humidity, and fuel telemetry;
- receive the five implemented DTC create/clear events;
- expose TC397 connection, telemetry freshness, and last controller-confirmed HVAC state.

This list is the complete TC397 product surface. RPi5 ambient lighting is a separate visual-output
feature and does not use this service.

## Ownership rules

| Component | Owns | Must not do |
|---|---|---|
| TC397 | Physical fan output, hardware inputs, DTC rules, final ACK/rejection | Trust an upstream request without its own checks |
| QNX gateway | Sole TC socket, CRC/framing, sequence correlation, one in-flight command, fault/telemetry translation | Parse speech/model text or expose arbitrary TC frames |
| Android Gateway APK | QNX socket lifecycle, typed Binder API, request deduplication/timeouts, state fan-out | Connect directly to TC397 or confirm before controller ACK |
| Climate app | Climate UX/domain validation, NOVA-facing Climate AIDL, mapping gateway state into UI | Own vehicle sockets or invent controller state |
| NOVA app | Intent routing and user-facing progress/final replies | Send model strings or raw bytes to the vehicle gateway |

Only Climate consumes the vehicle command API. Navigation and Phone retain their own typed Android
AIDL services. Ambient lighting is owned by the RPi5 runtime.

## Bench mapping

```text
DES-1008D unmanaged switch
  laptop Ethernet  192.168.10.10/24
  RPi5              192.168.10.20/24
  TC397             192.168.10.30/24
```

No DHCP, DNS, or default gateway is configured on this Ethernet link. Laptop Wi-Fi remains the
Internet route and may be shared to the RPi5 separately. The Android emulator reaches the laptop
relay at `10.0.2.2:6100`.

The final NXP mapping uses an Android/QNX inter-guest address selected by the image team. The TC397
side remains QNX-owned. Do not encode a laptop filesystem path or laptop IP as a contract constant.

## Android without AOSP modifications

`com.hypernova.vehiclegateway` is a normal headless APK with a bound AIDL service. It requires no
CarService, VHAL, framework, or AOSP source modification.

- Development: install the debug APK with ADB; the emulator build targets `10.0.2.2:6100`.
- Final image: preinstall/sign the same APK and other HyperNova clients with the agreed certificate,
  then set only the QNX endpoint in product build configuration.
- The exported Binder service is protected by the HyperNova signature permission and verifies caller
  signing identity.
- Release transport remains disabled until the NXP/QNX team provisions authenticated inter-guest
  transport (mTLS or an equivalent platform-secured channel).

The gateway is a bound service, so a consuming app owns its lifecycle during the demo. If the final
image requires telemetry from boot before any UI binds, the image owner must add the package to the
appropriate system lifecycle/power policy; this remains packaging configuration, not an API change.

## Command semantics

```text
Climate request
  -> Android Gateway validates and deduplicates
  -> QNX returns ACCEPTED only after the frame is queued for TC397
  -> TC397 ACK       => CONFIRMED
  -> TC397 rejection => REJECTED with mapped reason
  -> link loss       => UNAVAILABLE
  -> no response 5 s => TIMEOUT
```

One command may be in flight at the QNX/TC397 boundary. Commands are not retried after an ambiguous
disconnect, because an actuator request might already have executed. The TC397 remains authoritative
for fault-related restrictions and may reject a request that passed Android validation.

## Implemented source locations

| Piece | Location |
|---|---|
| Frozen HNVG/TC mapping | `HyperNova_Contracts/docs/VEHICLE_GATEWAY_PROTOCOL_V1.md` |
| Typed Gateway AIDL/parcelables | `HyperNova_Contracts/contracts/src/main/{aidl,java}/com/hypernova/contracts/vehiclegateway` |
| Android 16 Gateway APK | `HyperNova_VehicleGateway_Task_10/app` |
| Portable Linux/QNX relay | `HyperNova_VehicleGateway_Task_10/qnx-service` |
| Live smoke client | `HyperNova_VehicleGateway_Task_10/qnx-service/tools/gateway_smoke.py` |
| TC397 source of truth | `TriCore_Node` commit `b7a88ea` on `feat/real-dtcs-buttons-sensors` |

## Validated evidence

On 2026-08-12:

- the TC397 feature firmware passed its 23/23 bench protocol checks;
- the portable relay protocol codec passed its CTest suite;
- the Android Gateway APK unit tests and debug APK build passed;
- a live laptop test completed HNVG handshake, received fresh `25 C` / `93%` fuel telemetry, sent
  HVAC `22 C`, fan 3, both zones as AI, and received TC397 ACK for sequence 1.

## Known actions before final hardware freeze

- Merge TC397 `feat/real-dtcs-buttons-sensors` into its `main`; current `main` is obsolete.
- Replace the TC397 telemetry loop counter with elapsed STM timing. The live board emitted about
  12.3 Hz although the design says 1 Hz; the gateway currently throttles Android state publication.
- Require TC397 HVAC payload length to equal four bytes, not merely be at least four.
- Decide whether to strengthen the TC frame CRC to cover sequence and length. This requires a
  coordinated codec version change, not a unilateral QNX edit.
- Enable and verify watchdogs before making any production/reliability claim.
- Supply the Android/QNX virtio-net address, service startup policy, and authenticated-channel
  configuration when the NXP image reaches that integration stage.
