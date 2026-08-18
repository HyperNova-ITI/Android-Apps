# Android/QNX Vehicle Gateway Delivery Plan

Status: **contract, relay, Gateway APK, and Climate/NOVA vertical slice implemented**
Target: Android 16/API 36 laptop emulator, then Android 16 ARM64 NXP guest

The architecture is frozen in
[VEHICLE_NETWORK_ARCHITECTURE.md](VEHICLE_NETWORK_ARCHITECTURE.md), and the exact wire mapping is in
[VEHICLE_GATEWAY_PROTOCOL_V1.md](VEHICLE_GATEWAY_PROTOCOL_V1.md).

## Delivered

- typed, signature-protected `IVehicleGatewayService` AIDL;
- typed climate command/result, vehicle state, and DTC event parcelables;
- headless Android 16 Gateway APK (`com.hypernova.vehiclegateway`);
- strict bounded HNVG v1 stream codec, heartbeat, reconnect/backoff, correlation, command timeout,
  request deduplication, and state listeners;
- portable C++17 Linux/QNX relay with the exact TC397 CRC/frame codec;
- one-command-in-flight enforcement and ACK/rejection/timeout mapping;
- TCP fault plus UDP sensor ingestion and Android state throttling;
- codec/unit builds and a successful live laptop-relay-to-TC397 command round trip;
- process-wide Climate Gateway binding with accepted/final callbacks and confirmed vehicle state;
- successful API 36 NOVA -> Climate -> Gateway -> relay -> TC397 regression plus Navigation
  route-ready callbacks.

No AOSP, CarService, or VHAL source modification is required.

## Remaining delivery sequence

### 1. Android integration completion

- Keep the proven Gateway/Climate/NOVA/Navigation happy-path regression.
- Add fault create/clear, rejection, timeout, stale telemetry, process restart, and reconnect cases.
- Wire Phone into NOVA only if it remains in the presentation scope.
- Confirm NOVA continues working over Launcher because its broker remains a service rather than an
  activity-only connection.

Exit: one repeatable script/runbook proves speech/request ID through TC sequence and final UI/speech.

### 2. QNX packaging

- Cross-compile `qnx-service` with the NXP QNX SDP toolchain.
- Run it as a supervised QNX process with least privilege and fixed resource limits.
- Bridge the inter-guest network to the physical switch and configure QNX `192.168.0.51/24`.
- Persist no credentials in source; provision the authenticated inter-guest channel in the image.

Exit: the same protocol corpus and smoke sequence pass on QNX before TC397 is attached, then with the
controller attached.

### 3. Android 16 image packaging

- Set Android `192.168.0.100/24` and build Gateway for QNX `192.168.0.51:6100`.
- Preinstall and sign the Gateway and consuming apps with the agreed HyperNova certificate.
- If boot-time telemetry is required, add only the necessary package lifecycle/power allow-list.
- Disable plaintext/debug flags, verbose payload logging, ADB assumptions, and emulator host aliases.

Exit: identical feature-app AIDL runs on the ARM64 guest with no source API change.

## Safety and security floor

- The AI controls only infotainment/HVAC demo functions—not braking, steering, propulsion, or
  safety-critical body control.
- No model text, JSON, shell input, or raw controller frame can cross the typed Gateway AIDL.
- TC397 performs final range/fault authorization; upstream acceptance is not physical confirmation.
- Fault clearing is hardware-button-only.
- Demo plaintext is permitted only on the isolated wired bench. Production/interconnected operation
  requires authenticated encryption and key provisioning owned by the platform image.
- Disconnects are fail-closed and actuator commands are never automatically retried after an
  ambiguous result.

## Platform-team inputs still required

- bridge/tap name that exposes the frozen inter-guest subnet to physical Ethernet;
- QNX service startup/supervision mechanism and filesystem location;
- QNX SDP cross-file/toolchain details;
- authenticated inter-guest transport/key provisioning mechanism;
- Android APK preinstallation and common signing procedure.

The current target is standard Android 16/API 36, not Android Automotive OS and not Android 15.
