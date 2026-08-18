# HyperNova Linux/QNX Vehicle Gateway

This C++17 POSIX process is both the laptop development relay and the QNX-side service for the final
architecture. It terminates HNVG v1 for Android and is the sole TC397 TCP command client.

Contract: `../../HyperNova_Contracts/docs/VEHICLE_GATEWAY_PROTOCOL_V1.md`

## Responsibilities

- listen for one Android Gateway APK at TCP `:6100`;
- maintain/reconnect the TC397 TCP session at `192.168.0.30:6001`;
- receive TC397 UDP telemetry on `:6000`;
- validate and translate only the implemented HVAC command;
- serialize one command in flight and correlate ACK/rejection by TC sequence;
- publish temperature, humidity, fuel, confirmed HVAC state, DTC events, and link freshness;
- preserve fail-closed timeout/disconnect behavior.

The contract catalogue is complete. RPi5 ambient lighting is outside this service.

The final sensor frame is three bytes: temperature, humidity, fuel. During the bench transition the
relay also accepts the currently flashed `b7a88ea` four-byte form, ignores byte four, and never
exposes it to Android. Remove that compatibility only after every TC397 is reflashed.

## Laptop build and tests

From this directory:

```bash
cmake -S . -B build
cmake --build build
ctest --test-dir build --output-on-failure
```

With QNX stopped, TC397 reachable, and the laptop temporarily holding both its frozen `.40` address
and QNX's `.51` address so it can receive the unicast telemetry:

```bash
./build/hypernova-qnx-gateway
```

Remove the temporary `.51` address before starting QNX. Never let the laptop and QNX claim `.51` at
the same time.

In a second terminal:

```bash
python3 tools/gateway_smoke.py
```

The smoke test sends `22 C`, fan level 3, both zones, caller AI. It must print an HNVG handshake,
fresh state, `accepted`, and then `confirmed by TC397`. Stop the relay with Ctrl+C afterward so other
test tools can acquire TC397's single command connection.

Optional endpoint overrides:

```text
--tc-address ADDRESS
--tc-port PORT
--android-port PORT
--telemetry-port PORT
```

## Android emulator

The Gateway APK debug build connects to host alias `10.0.2.2:6100`. Start this process before binding
the Gateway service from Climate. No port forwarding is required for the standard Android emulator.

## QNX cross-build handoff

The service uses portable POSIX sockets, `poll`, C++17, and CMake. The QNX owner should:

Frozen deployment addresses are QNX `192.168.0.51/24`, Android `192.168.0.100/24`, hypervisor host
`192.168.0.50/24`, TC397 `192.168.0.30/24`, RPi5 `192.168.0.20/24`, and laptop
`192.168.0.40/24`. The NXP virtual network must be bridged to the physical switch before starting
the service. TC397 telemetry is compiled to QNX `192.168.0.51:6000`.

1. source the matching QNX SDP environment;
2. configure CMake with the QNX toolchain file supplied by the image project;
3. build and run `ctest` for the protocol codec;
4. install `hypernova-qnx-gateway` in the image-owned executable location;
5. supervise it with the image's process manager and restart-on-failure policy;
6. give it only the network/resource privileges needed for the TC397 and virtio-net interfaces;
7. bridge/expose the inter-guest network to the external Ethernet switch and set QNX `.51`;
8. provision authenticated Android/QNX transport before leaving the isolated demo bench.

Do not hardcode a developer filesystem path in service scripts. The image owner selects the target
binary/configuration locations.

Example configure shape (replace the toolchain path with the image team's real file):

```bash
cmake -S . -B build-qnx -DCMAKE_TOOLCHAIN_FILE=/path/provided/by/qnx-image-team.cmake
cmake --build build-qnx
ctest --test-dir build-qnx --output-on-failure
```

## Operational notes

- A new TC397 connection is quarantined for 750 ms so retained asynchronous events are drained before
  accepting actuation.
- A command is not automatically retried after timeout/disconnect.
- Android state publication is throttled to 5 Hz because current TC397 firmware emits telemetry at
  about 12.3 Hz instead of its intended 1 Hz.
- TC397 uses one shared sequence counter for TCP faults and UDP telemetry. UDP gaps/reordering are not
  treated as proof that a fault event was lost.
- Unknown/malformed HNVG messages close the Android session. Malformed TC397 CRC frames are dropped.
