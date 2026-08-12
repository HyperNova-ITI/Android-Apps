# HyperNova Climate implementation record

Status: **Android implementation complete for the final TC397 surface**

The earlier VHAL-versus-direct-socket design alternatives are superseded. Climate never connects to
the TC397 and no AOSP/VHAL modification is required. See [BACKEND_PLAN.md](BACKEND_PLAN.md) for the
implemented path and current evidence.

## Frozen decisions

- Android target: Android 16/API 36 now; Android 16 ARM64 NXP guest later.
- App package: `com.hypernova.climate`.
- NOVA-facing API: frozen `IClimateCommandService` contract.
- Vehicle-facing API: signature-protected `IVehicleGatewayService`.
- Socket owner: laptop POSIX relay during development; the same service runs under QNX in the final
  system.
- TC397 is the final command/range/fault/hardware authority.
- Final controller functions: dual-zone target `16..28 C`, fan `0..5`/power, temperature/humidity/fuel
  telemetry, and physical-button fault create/clear.
- Unsupported Climate controls are hidden and their generic AIDL calls reject; no optimistic local
  vehicle state is presented.
- RPi5 ambient lighting is independent of Climate and the TC397 protocol.

## Delivered

1. The Climate project consumes the shared contract module and signature permission.
2. `HyperNovaClimateApplication` starts the process-wide gateway runtime even when Launcher is the
   foreground activity.
3. `VehicleGatewayRuntime` manages Binder connection/death/reconnect, capabilities, state subscription,
   and controller-confirmed mutations.
4. `ClimateStateOwner` represents requested/pending versus confirmed state explicitly.
5. `ClimateViewModel` routes UI power, temperature, and fan actions through the Gateway.
6. `ClimateCommandService` routes NOVA power, temperature, and fan calls through that same state path,
   preserving `accepted -> one final result` semantics.
7. The UI renders connection/pending/stale state, disables commands while pending/offline, shows only
   real controller-backed signals, and hides unsupported controls/empty cards.
8. Android 16 builds pass and the physical bench confirms NOVA -> Climate -> Gateway -> relay -> TC397
   ACK -> final Android callback.

## Required verification before the presentation

1. Rebuild and flash the reviewed TC397 source.
2. Run `TriCore_Node/Test/hnc_bench.py test`; require exact-length rejection, three-byte sensor frames,
   approximately 1 Hz telemetry, valid CRC, single-client enforcement, and physical DTC events.
3. Run the Android command probe and interact with the Climate UI; verify the displayed state matches
   the physical fan/controller state.
4. Test failure paths: TC rejection, command timeout, TCP loss/reconnect, stale telemetry, Gateway
   process restart, and Climate process restart.
5. Reconnect RPi5 voice and confirm speech occurs only after the final controller result.

## NXP/QNX delivery

- Cross-compile `HyperNova_VehicleGateway_Task_10/qnx-service` using the team QNX SDP.
- Run it as a supervised, least-privilege service owned by QNX.
- Point the Android Gateway APK at the Android/QNX virtio-net address.
- Preinstall/sign Gateway, Climate, NOVA, Launcher, Navigation, and any retained Phone APK with the
  agreed certificate.
- Disable emulator aliases and plaintext debug mode after the platform team provides the authenticated
  inter-guest channel.

The Android feature contracts and application source do not change when moving from the laptop relay
to QNX; only packaging, addresses, supervision, signing, and secure transport configuration change.
