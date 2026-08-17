# HyperNova Climate backend

Status: **implemented and proven on the Android 16/API 36 bench**

This document describes the current implementation. The authoritative wire and ownership contracts
are:

- `../HyperNova_Contracts/docs/VEHICLE_NETWORK_ARCHITECTURE.md`
- `../HyperNova_Contracts/docs/VEHICLE_GATEWAY_PROTOCOL_V1.md`
- `../HyperNova_VehicleGateway_Task_10/qnx-service/README.md`

## Final product surface

The TC397 product surface is closed. Climate exposes only functionality backed by the current
controller and hardware:

| Function | TC397 mapping |
|---|---|
| Driver target temperature | `CMD_SET_HVAC`, zone 1 |
| Passenger target temperature | `CMD_SET_HVAC`, zone 2 |
| Both targets | `CMD_SET_HVAC`, zone 0 |
| Fan level | `CMD_SET_HVAC`, level `0..5`; zero means off |
| Power | Derived from fan level; off sends level zero |
| Cabin temperature/humidity/fuel | `EVT_SENSOR_DATA` |
| Fault create/clear | Physical TC397 buttons and `EVT_FAULT_EVENT` |

The final `EVT_SENSOR_DATA` payload is exactly
`[temperature][humidity][fuel]`. Ambient lighting is an independent RPi5 NOVA output and is not a
vehicle-gateway or TC397 operation.

The generic Climate AIDL still contains A/C, AUTO, and recirculation methods for ABI compatibility.
This vehicle advertises those capabilities as false, hides their controls, and rejects calls with
`UNSUPPORTED_OPERATION`. The UI does not maintain pretend vehicle state.

## Runtime path

```text
NOVA or Climate UI
  -> IClimateCommandService / process-wide Climate state owner
  -> IVehicleGatewayService (signature-protected Binder)
  -> HNVG v1 over the Android/QNX virtual network
  -> laptop relay for development / same POSIX service on QNX final
  -> TC397 TCP :6001
  -> ACK or explicit rejection
  -> confirmed state back through the same typed layers
```

Android applications never open a TC397 socket. QNX is the only controller client in the final
architecture, and the laptop relay occupies that role during development. No AOSP, VHAL, or
CarService modification is required.

## Implemented Android behavior

`VehicleGatewayRuntime` is the process-wide Climate Binder client. It:

- binds explicitly to `com.hypernova.vehiclegateway`;
- subscribes to typed vehicle state and DTC events;
- maps exact capabilities: dual zone, `16..28 C`, step `1 C`, fan `0..5`;
- marks UI changes pending before submission;
- publishes accepted and exactly one final result;
- updates confirmed state only from Gateway/TC397 confirmation;
- retains last confirmed values as stale on disconnect;
- handles service disconnect, dead binding, and process restart without duplicate bindings;
- starts at application-process creation so NOVA works while Launcher is foreground.

`ClimateCommandService` and the Climate UI use this same runtime/state owner. UI and voice therefore
cannot diverge into separate optimistic states.

## Wire behavior

`CMD_SET_HVAC` has exactly four payload bytes:

```text
[target int8][fan 0..5][zone 0..2][caller driver=0/AI=1]
```

Target is `16..28 C` when fan is nonzero and ignored when fan is zero. TC397 remains the final range,
fault, and hardware authority. Binder acceptance means only that the request entered the pipeline;
physical success is reported only after the TC397 ACK.

The reviewed firmware source corrects exact-length validation, elapsed-time 1 Hz telemetry, prompt
10 ms fault/HVAC servicing, rejected-command rollback, and the three-byte sensor frame. The board
currently flashed with `b7a88ea` predates those fixes and still sends a fourth zero byte. The relay
temporarily accepts both sensor lengths but exposes only the three real signals. Rebuild and reflash
before final regression.

## Proven test

On the API 36 emulator with the laptop relay and physical TC397, the command probe completed:

```text
climate.set_temperature: accepted
climate.set_temperature: confirmed
navigation.get_saved_destinations: accepted -> confirmed
navigation.set_destination: accepted -> confirmed (route ready; trip remains idle)
```

Gateway and Climate Gradle builds/tests pass, the POSIX relay CMake/CTest suite passes, and the
Climate screen renders controller-backed cabin temperature, targets, power, fan, and connection
state while hiding unsupported fields.

## Remaining gates

1. Build/flash the reviewed TC397 source and rerun `Test/hnc_bench.py test`.
2. Exercise fault create/clear, rejection, timeout, stale telemetry, and reconnect through Android.
3. Reconnect RPi5 voice and verify final spoken confirmation over the same typed path.
4. Cross-compile/package the unchanged POSIX relay as a supervised QNX service.
