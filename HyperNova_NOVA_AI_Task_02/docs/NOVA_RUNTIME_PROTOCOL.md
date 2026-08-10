# NOVA Android ↔ Raspberry Pi runtime protocol

Status: MVP contract, version 1
Android package: `com.hypernova.ai`
Target hardware: NXP i.MX 8QM MEK Android 16 guest; laptop Android runtime during development

## Ownership

The Raspberry Pi owns the CM108 USB microphone, openWakeWord, VAD, ASR, the agent/tool loop, and TTS
synthesis. Android owns audio focus, speaker playback, and the typed AIDL bridge to Android feature
applications.

The Pi captures its microphone directly through ALSA. No microphone samples are captured or transmitted by the Android app. This avoids continuous network streaming, Android recording conflicts, and Android 15/16 microphone-service differences.

Android sends `playback started` and `playback ended` control messages. The Pi suppresses and resets wake-word processing while Android is playing NOVA audio so the cockpit speaker cannot trigger the Pi microphone.

## Endpoints

| Port | Transport | Purpose |
|---|---|---|
| 8765 | TCP, UTF-8 JSON Lines | Connection, state, transcript, result, error, and text-command control |
| 8766 | TCP, framed binary | Pi TTS PCM to Android |

The Pi listens on all interfaces. Android initiates both connections. Only one active Android audio client is allowed in MVP; a newly authenticated client replaces a stale connection.

## Control channel (8765)

Each line is one JSON object terminated by `\n`. Every message contains `type`, protocol version `v`, and a monotonically increasing per-connection `seq`. State and result messages also carry a `turn_id` when applicable.

Android → Pi:

```json
{"type":"hello","v":1,"seq":1,"client":"nova-android","app_version":"0.1.0"}
{"type":"command","v":1,"seq":2,"turn_id":"optional-uuid","text":"turn on the AC"}
{"type":"cancel","v":1,"seq":3,"turn_id":"uuid"}
{"type":"playback","v":1,"seq":4,"turn_id":"uuid","value":"started"}
{"type":"playback","v":1,"seq":5,"turn_id":"uuid","value":"ended"}
{"type":"command_result","v":1,"seq":6,"turn_id":"uuid","request_id":"uuid","domain":"navigation","operation":"set_destination","status":"confirmed","message":"Destination set. Route is ready.","data":{"selected_destination":{"id":"opaque-id","title":"Coffee Lab"},"eta_seconds":900,"distance_meters":8200}}
```

Pi → Android:

```json
{"type":"hello_ack","v":1,"seq":1,"server":"nova-pi","audio_port":8766}
{"type":"state","v":1,"seq":2,"turn_id":"uuid","value":"listening"}
{"type":"state","v":1,"seq":3,"value":"listening","followup":true,"window_ms":5000}
{"type":"transcript","v":1,"seq":4,"turn_id":"uuid","text":"turn on the AC","final":true}
{"type":"state","v":1,"seq":5,"turn_id":"uuid","value":"processing"}
{"type":"state","v":1,"seq":6,"turn_id":"uuid","value":"executing"}
{"type":"result","v":1,"seq":7,"turn_id":"uuid","status":"success","text":"Air conditioning is on"}
{"type":"state","v":1,"seq":8,"turn_id":"uuid","value":"speaking"}
{"type":"error","v":1,"seq":9,"turn_id":"uuid","code":"ASR_FAILED","message":"I couldn't understand that"}
{"type":"command_request","v":1,"seq":10,"turn_id":"uuid","request_id":"uuid","domain":"navigation","operation":"search_destinations","args":{"query":"coffee shops near me"}}
{"type":"latency","v":1,"seq":11,"turn_id":"uuid","ttfw_s":1.24,"total_s":1.52}
{"type":"route","v":1,"seq":12,"turn_id":"uuid","tier":"cloud"}
{"type":"progress","v":1,"seq":13,"turn_id":"uuid","tier":"local","text":"Thinking that through."}
```

Allowed state values are `idle`, `listening`, `processing`, `executing`, `success`, `error`, `speaking`, and `unavailable`. Unknown message fields are ignored. Unknown protocol versions are rejected with `UNSUPPORTED_VERSION`.

For a follow-up capture window, the Pi emits exactly one `listening` state with `followup=true` and a
positive `window_ms`. Android renders this as a distinct conversational window and derives its local
countdown deadline when the event arrives. A plain `listening` event remains the wake-word capture UI.

`latency` is developer telemetry. `ttfw_s` may be JSON `null` for typed commands or when audible-start
timing is unavailable; `total_s` is always present. Release UI must not expose these numbers to the
driver. `route` is also developer telemetry for the hybrid tier and is safe for older clients to
ignore.

`progress` belongs to the same turn and is never terminal. It exists only for a slow local reasoning
turn and must be followed by that turn's final `say` or `error`; Android may render it while the state
remains `processing`. It must never claim that a tool succeeded.

### Driver-facing Android state mapping

The release UI does not expose Pi, cloud-provider, microphone, speaker, socket, or model names. It
maps protocol state into the following compact driver language:

| Runtime | Header/status | Card behavior |
|---|---|---|
| `idle` | Ready | Wake prompt; no activity indicator |
| `listening` | Listening | Listening prompt or a real follow-up countdown |
| transcript + `processing` | Understanding | Show the recognized request and activity indicator |
| `progress` + `processing` | Working on it | Replace the transcript headline with truthful progress; keep the request as context |
| `executing` | Acting | Show the domain action/provider message and activity indicator |
| PCM playback | Responding | Actual playback owns the speaking animation |
| `success` | Completed | Show the final confirmed result |
| `error` | Needs attention | Show the safe failure and state that no change was made |
| disconnected | Unavailable | Offer reconnect without exposing network topology |

`route.tier` remains internal telemetry. Progress wording describes useful work (“Checking traffic
and your schedule”) rather than infrastructure (“Calling cloud model”).

## Destination-command extension

`command_request` asks NOVA Android to invoke the frozen Navigation or Climate AIDL service.
`command_result` returns its intermediate or final callback to the Pi.

Rules:

- `turn_id` identifies the voice turn; `request_id` identifies one destination-app operation.
- Android echoes both IDs unchanged.
- `accepted` is intermediate only.
- Final statuses are `confirmed`, `rejected`, `unavailable`, `timeout`, and `cancelled`.
- A duplicate `request_id` must not repeat a route or vehicle actuation.
- The Pi must not emit its final `result`/`say` success until it receives a confirmed
  `command_result`.
- Navigation operations are `search_destinations`, `get_saved_destinations`, `set_destination`, and
  `cancel_navigation`.
- Climate operations are `get_capabilities`, `get_current_state`, `set_power`, `set_temperature`,
  `set_fan_level`, `set_ac`, `set_auto`, and `set_recirculation`.

Exact arguments, result payloads, AIDL methods, and showcase scenarios are frozen in
[NAVIGATION_CLIMATE_COMMAND_HANDOFF.md](NAVIGATION_CLIMATE_COMMAND_HANDOFF.md).

## Binary audio channel (8766)

All multi-byte integers use network byte order (big-endian). Every frame begins with this 16-byte header:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | ASCII magic `NVA1` |
| 4 | 1 | Protocol version, currently `1` |
| 5 | 1 | Frame type |
| 6 | 2 | Flags, reserved as `0` |
| 8 | 4 | Payload length, maximum 262144 bytes |
| 12 | 4 | Stream ID, unsigned and non-zero for audio streams |

Frame types:

| Value | Name | Direction | Payload |
|---:|---|---|---|
| 1 | `HELLO` | Android → Pi | UTF-8 JSON: client and format capabilities |
| 2 | `HELLO_ACK` | Pi → Android | UTF-8 JSON: selected formats |
| 16 | `MIC_START` | Reserved | Disabled in the approved Pi-microphone topology |
| 17 | `MIC_PCM` | Reserved | Disabled in the approved Pi-microphone topology |
| 18 | `MIC_END` | Reserved | Disabled in the approved Pi-microphone topology |
| 32 | `TTS_START` | Pi → Android | UTF-8 JSON format metadata and `turn_id` |
| 33 | `TTS_PCM` | Pi → Android | raw signed 16-bit little-endian mono PCM |
| 34 | `TTS_END` | Pi → Android | empty payload |
| 48 | `PING` | either | empty payload |
| 49 | `PONG` | either | empty payload |
| 255 | `AUDIO_ERROR` | either | UTF-8 JSON error object |

MVP TTS format is `pcm_s16le`, mono, at the native Piper voice sample rate given by `TTS_START`. PCM frame boundaries are transport boundaries only; receivers must concatenate frames belonging to the same stream ID.

## Timing and recovery

- Pi wake detection and ASR consume `plughw:CARD=Device,DEV=0` directly at their required sample rates.
- Android requests transient assistant audio focus at `TTS_START`, drains every PCM frame before ending playback, then releases focus.
- Android reports playback start/end on port 8765; the Pi ignores speaker leakage until playback ends.
- Either peer reconnects with exponential backoff from 250 ms to 5 seconds. A partial frame is discarded after disconnect.
- A control or audio disconnect makes the UI `UNAVAILABLE`; no TTS is considered delivered until both channels are healthy.

## Development addressing

The Pi is currently discoverable as `hnc-ai30.local` on the dedicated HyperNova LAN (reserved IPv4
`192.168.10.20`). The app must keep the host configurable because emulator, laptop, and final AAOS
guest networking differ. No image-specific or Trout-specific API is allowed in the core audio or
protocol layers.
