# HyperNova full-demo readiness

Status: **Android-to-TC397 Climate/Navigation slice proven; physical voice/fault demo remains**
Target: Android 16/API 36 emulator now, Android 16 ARM64 NXP guest later

## Current evidence

| Layer | State | Evidence |
|---|---|---|
| RPi5 voice node | Previously proven; reconnect for combined test | Mic/wake/ASR/Qwen/TTS and NOVA TCP events worked in earlier runs |
| NOVA Android | Built and live-tested on API 36 | Correlated requests and final domain callbacks work |
| Launcher | Built/tested after merge | NOVA can remain active outside its activity |
| Navigation | Built/tested after Ayman's merge | Saved/search/set-destination typed AIDL exists |
| Phone | Existing AIDL service wired into NOVA | Real calls still require the final HFP-connected phone environment |
| Media | Existing Media3 session wired into NOVA | Play/pause/next/previous/state/volume use the Media app's one session; no Media AIDL was added |
| Climate frontend/service | Gateway-backed and live-tested | UI/service send typed requests and render controller-confirmed state |
| Android Vehicle Gateway | Implemented and live-tested | Typed AIDL plus Android 16 debug APK tests pass |
| Laptop/QNX relay | Implemented and live-tested | HNVG accepted then TC397-confirmed a real command |
| TC397 feature firmware | Live and protocol-tested | 23/23 bench checks pass; telemetry-rate defect documented |
| NXP Android/QNX integration | Waiting on platform image | Requires virtio-net/startup/security configuration, not an app API redesign |

## Showcase flow to finish

```text
"Hey NOVA, set the temperature to 22 and fan to 3"
  -> NOVA typed Climate AIDL
  -> Climate validates and shows pending
  -> Vehicle Gateway AIDL
  -> laptop relay now / QNX service final
  -> TC397 controls the two fans
  -> TC397 ACK
  -> Climate + Launcher show confirmed state
  -> NOVA speaks the final result

Press fault-create button
  -> TC397 real DTC event
  -> gateway state/event
  -> visible non-safety-critical warning and honest HVAC restriction

Press fault-clear button
  -> TC397 clear event
  -> UI clears; no AI/software clear command exists
```

Follow with Navigation search/saved destination and Phone UI/service only after the physical HVAC
slice is stable. Ambient light on RPi5 may mirror NOVA state as a visual flourish, but it is a separate
output and must never block commands or enter the TC397 contract.

## Bench topology

```text
DES-1008D switch
  laptop Ethernet 192.168.10.10/24  (relay + Android emulator host)
  RPi5             192.168.10.20/24  (mic + voice runtime)
  TC397            192.168.10.30/24  (TCP 6001 / UDP to .10:6000)

Android emulator -> laptop relay at 10.0.2.2:6100
laptop Wi-Fi      -> Internet/default route; optionally shares Internet to RPi5
laptop speaker    -> Android audio output during current bench test
```

The Ethernet profile has no gateway or DNS. TC397 supports one command client, so stop the bench tool
before starting the relay and stop the relay before running the bench tool.

## Remaining gates

1. Build and flash the reviewed TC397 source so exact four-byte HVAC validation, true 1 Hz telemetry,
   three-byte sensor telemetry, and prompt fault shutdown replace the older `b7a88ea` image.
2. Reconnect RPi5 and prove voice request -> same typed path -> laptop/Android audio result.
3. Exercise physical fault create/clear, controller rejection, timeout, and controller reconnect.
4. Prove Phone commands with a real HFP-connected phone and Media commands with a selected real
   station/track. The emulator alone can prove binding and honest unavailable states, not real HFP.
5. Add and test the RPi5 ambient-light state renderer as a non-blocking parallel output.
6. Freeze one short presentation script plus a recovery checklist.

## Known issues that must remain visible

- TC397 `feat/real-dtcs-buttons-sensors` must be merged to its `main`; current `main` is stale.
- The currently flashed `b7a88ea` image emits a fourth legacy-zero sensor byte, runs telemetry at
  about 12.3 Hz, and accepts HVAC payloads longer than four bytes. The reviewed source fixes all
  three, but the board must be rebuilt and reflashed. The relay temporarily accepts both 3- and
  4-byte sensor frames during this transition while exposing only temperature/humidity/fuel.
- TC frame CRC excludes sequence and length. Do not change one side alone.
- TC397 watchdogs are disabled in demo firmware.
- Cloud hybrid routing, latency improvements, and industry-rich LLM scenarios remain separate from
  the deterministic vehicle action path. Cloud output must never bypass typed validation/TC authority.

## Definition of demo-ready

- Android is API 36 and all six relevant APKs remain alive across Launcher transitions.
- One request ID can be traced through NOVA, Climate, HNVG correlation, TC sequence, and final ACK.
- UI says pending/accepted/confirmed/rejected accurately and never drops the final state.
- Target-temperature commands do not imply navigation trip start; destination selection and trip start
  remain distinct Navigation actions.
- Physical fan output and sensor/fuel values match TC397 telemetry.
- Fault create/clear is visible, deterministic, and cannot be cleared through AI.
- Voice begins promptly using deterministic command routing; the local 30B model is not placed in front
  of known commands.
- A laptop/QNX/TC disconnect produces unavailable/timeout, never false success.

Authoritative vehicle documents:

- `../../HyperNova_Contracts/docs/VEHICLE_NETWORK_ARCHITECTURE.md`
- `../../HyperNova_Contracts/docs/VEHICLE_GATEWAY_PROTOCOL_V1.md`
- `../../HyperNova_VehicleGateway_Task_10/qnx-service/README.md`
