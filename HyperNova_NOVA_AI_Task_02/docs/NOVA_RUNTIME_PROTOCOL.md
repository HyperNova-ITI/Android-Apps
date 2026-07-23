# NOVA Android ↔ Raspberry Pi runtime protocol

Status: MVP contract, version 1
Android package: `com.hypernova.ai`
Target hardware: NXP i.MX 8QM MEK Android 16 guest; laptop Android runtime during development

## Ownership

The Raspberry Pi owns the CM108 USB microphone, openWakeWord, VAD, ASR, the agent/tool loop, and TTS synthesis. Android owns audio focus and speaker playback.

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
```

Pi → Android:

```json
{"type":"hello_ack","v":1,"seq":1,"server":"nova-pi","audio_port":8766}
{"type":"state","v":1,"seq":2,"turn_id":"uuid","value":"listening"}
{"type":"transcript","v":1,"seq":3,"turn_id":"uuid","text":"turn on the AC","final":true}
{"type":"state","v":1,"seq":4,"turn_id":"uuid","value":"processing"}
{"type":"state","v":1,"seq":5,"turn_id":"uuid","value":"executing"}
{"type":"result","v":1,"seq":6,"turn_id":"uuid","status":"success","text":"Air conditioning is on"}
{"type":"state","v":1,"seq":7,"turn_id":"uuid","value":"speaking"}
{"type":"error","v":1,"seq":8,"turn_id":"uuid","code":"ASR_FAILED","message":"I couldn't understand that"}
```

Allowed state values are `idle`, `listening`, `processing`, `executing`, `success`, `error`, `speaking`, and `unavailable`. Unknown message fields are ignored. Unknown protocol versions are rejected with `UNSUPPORTED_VERSION`.

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

The Pi is currently discoverable as `hnc-ai30.local` (last known IPv4 `192.168.1.32`). The app must keep the host configurable because emulator, laptop, and final AAOS guest networking differ. No image-specific or Trout-specific API is allowed in the core audio or protocol layers.
