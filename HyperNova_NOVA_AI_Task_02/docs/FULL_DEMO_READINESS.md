# HyperNova full-demo readiness and vehicle-network bridge

Status: **Android app demo ready; Pi voice integration ready to resume; physical TC397 actuation
requires the gateway adapter and the controller protocol inputs below.**

Target: Android 16/API 36 on the laptop emulator now, then the Android 16 ARM64 NXP guest without
changing the feature-app AIDL.

## 1. What is working now

| Layer | Current result | Proof |
|---|---|---|
| Android 16 emulator | Ready | API 36 AVD boots at the cockpit resolution with Vulkan enabled |
| NOVA Android broker | Ready | Builds/tests pass; TCP requests are correlated with final AIDL callbacks |
| Navigation | Ready for app demo | Real APK returns saved places and activates a route to the returned opaque ID |
| Climate | Ready for app demo | Real APK exposes the shared AIDL service; debug commands update the UI and Launcher state |
| Launcher | Ready for app demo | Real Climate state is shown as 22°C, fan 3, AUTO; Navigation opens correctly |
| Pi command mapping | Ready to resume | Seven local agent tests pass, including accepted→final handling and saved Home/Work resolution |
| Physical TC397 command | Not wired yet | Final gateway boundary is decided, but the Android gateway, laptop relay, and protocol mapping remain |
| NXP Android/QNX integration | Waiting on platform | Requires the Android 16 guest and AAOS↔QNX virtual network supplied by the NXP image team |

The current Climate debug confirmation proves Android UI/AIDL integration. It is not a controller
ACK and must not be described as physical HVAC actuation.

## 2. Best demo available immediately

Use the Android 16 laptop emulator and the local NOVA command probe:

```text
laptop probe TCP sockets
  → NOVA Android broker
  → real Climate AIDL service → Climate UI + Launcher card
  → real Navigation AIDL service → active route UI
```

Run `NOVA_END_TO_END_TEST_RUNBOOK.md` in laptop-only mode. The probe performs these checks and can
hold the connection open for presentation:

1. Set the Climate state to 22°C and wait for `accepted` then `confirmed`.
2. Request saved destinations.
3. Select the opaque ID whose source is `saved_home`.
4. Wait for Navigation to report an active route.
5. Return to Launcher and show the updated Climate card.

This is a useful app-integration demo even while the Pi and TC397 are disconnected.

## 3. Voice demo after the Pi is powered on

The Pi remains the microphone/wake-word/ASR/LLM/TTS-synthesis node. Android remains the speaker and
cockpit UI. The user-facing UI never exposes that hardware placement.

Recommended presentation order:

1. “Hey NOVA, set the climate to 22 degrees.”
2. Show the Climate app and Launcher card updating from the confirmed AIDL state.
3. “Hey NOVA, take me home.”
4. Show Navigation activating the saved Home route and NOVA speaking the final result.
5. Demonstrate one honest rejection, such as an unsupported temperature, if time permits.

Use the short deterministic commands first. Leave open-ended Qwen requests until after the typed
command flow has been demonstrated because the current 30B model is the slowest part of the stack.

Before the live run, copy the updated `agent_loop_v3.py` to the Pi, run its tests, start the llama and
agent services, and build NOVA with the Pi's current IPv4 address. The exact commands are in the main
runbook.

## 4. Can the old router be used for a TC397 demo?

**Yes.** Use it as an isolated Ethernet switch/access point, not as part of the production
architecture. The laptop temporarily runs a QNX-relay simulator. Android still talks to a gateway
endpoint; it does not open a raw TC397 socket. Replacing the laptop process with the real QNX service
then leaves Climate and NOVA unchanged.

Recommended lab topology:

```text
Home/project Wi-Fi
  ├── Raspberry Pi (microphone + NOVA runtime)
  └── laptop Wi-Fi
        └── Android 16 emulator → host at 10.0.2.2

laptop Ethernet, static TC397-side address
  └── old router LAN port (WAN port unused; DHCP disabled)
        └── TC397 Ethernet

laptop QNX-relay simulator
  ├── Android-facing gateway protocol on the emulator host interface
  └── raw TC397 TCP/UDP protocol on the isolated Ethernet interface
```

If the controller still uses `192.168.10.30/24` and expects its peer at `192.168.10.10`, assign the
laptop Ethernet adapter `192.168.10.10/24`. Do not set a gateway or DNS server on that adapter. Keep
the WAN port unused and disable the old router's DHCP server. This prevents it from stealing the
laptop's default route. Confirm these addresses against the actual TC397 code before configuring
them.

Do not use this arrangement if the laptop's Wi-Fi LAN also uses `192.168.10.0/24`; two interfaces on
the same subnet make routing ambiguous. In that case change the lab subnet if the controller allows
it, or temporarily disconnect the conflicting network.

## 5. Temporary bridge versus final vehicle

```text
Temporary lab
Climate → Android Vehicle Gateway → laptop QNX-relay simulator → TC397

Final NXP
Climate → Android Vehicle Gateway → QNX guest network service → TC397
```

The invariant is that NOVA and Climate never own the controller socket. The QNX side owns the sole
TC397 command client, serialization, frame/CRC encoding, ACK/reject mapping, and telemetry receive.
The Android Vehicle Gateway owns connection/retry and AIDL fan-out to Android feature apps.

The detailed boundary is in
`../../HyperNova_Contracts/docs/VEHICLE_NETWORK_ARCHITECTURE.md`. Its architecture is frozen; the
Android↔QNX JSON protocol fields and ports are still draft until the QNX side agrees.

## 6. Inputs required before implementing physical actuation

Provide the TC397 networking source files or an equivalent protocol document containing:

- controller IPv4 address, prefix, and required peer address;
- TCP and UDP ports and which side listens on each one;
- binary frame layout, byte order, sequence rules, payload length rules, and CRC algorithm;
- HVAC command IDs and payloads for power, target temperature, fan, A/C, AUTO, and recirculation;
- ACK, rejection, timeout, fault, and telemetry message layouts;
- mapping between a command sequence and its final ACK/rejection;
- controller safety prerequisites and the commands already implemented in firmware;
- at least one known-good request/response capture or hex example.

Also select the first physical showcase. The smallest impressive vertical slice is:

```text
set target temperature → accepted UI → TC397 ACK → confirmed UI/speech
```

Add fan level as the second mutation, followed by a real controller rejection. That gives the demo a
successful action, visible state, and safety/error behavior without trying to wire every Climate
button at once.

## 7. Implementation sequence after those inputs arrive

1. Freeze the Android↔QNX relay port and JSON message names with the QNX owner.
2. Add and generate the generic Vehicle Gateway AIDL in `HyperNova_Contracts`.
3. Build `com.hypernova.vehiclegateway` with a configurable relay host and request correlation.
4. Implement the laptop QNX-relay simulator using the real TC397 frame codec.
5. Replace Climate's debug adapter with a `VehicleGatewayClimateBackend` binding.
6. Prove confirmed, rejected, timeout, duplicate-request, reconnect, and telemetry cases.
7. On NXP, change only the relay host/port and signing/preinstallation configuration.

No direct Android→TC397 shortcut should be added for the demo; it would create throwaway code and
violate the final one-client architecture.

## 8. Demo gates

The app demo is ready when:

- Android reports release 16/API 36;
- Launcher, Navigation, Climate, and NOVA remain alive with Vulkan enabled;
- the probe prints its final PASS line;
- Climate and Launcher show the same state;
- Navigation uses an ID returned by saved/search results and reaches ACTIVE.

The physical demo is ready only when:

- the laptop relay is the sole TCP command client of TC397;
- Climate reports `confirmed` only after the controller ACK/readback;
- controller rejection and timeout are visible as rejection/timeout, never success;
- disconnecting/reconnecting the controller does not require reinstalling Android apps;
- packet/log evidence can correlate NOVA `request_id` → gateway request → TC397 sequence → final
  Android callback.
