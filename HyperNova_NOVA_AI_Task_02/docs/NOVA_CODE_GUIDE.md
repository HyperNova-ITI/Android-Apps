# NOVA code guide

This guide is a practical reading path through the current NOVA implementation. Start with the
end-to-end flow, then open the linked files in order.

## 1. The complete runtime flow

```text
Pi microphone
  → wake word / VAD / ASR
  → Pi agent and tool selection
  → control events on TCP 8765
  → NovaRuntimeService
  → NovaRuntimeState
  → NovaViewModel
  → NovaUiStateFactory
  → NovaActivity
  → NovaStatusService
  → HyperNova Launcher widget

Pi TTS PCM
  → framed audio on TCP 8766
  → NovaAudioClient
  → NovaPcmPlayer
  → Android audio focus and speaker
```

The activity does not run the AI model, capture the Pi microphone, or invent command results. It
renders the authoritative session data delivered by the runtime.

## 2. Start with the screen

Open [NovaActivity.kt](../app/src/main/java/com/hypernova/ai/NovaActivity.kt).

Important parts:

- `onCreate()` inflates the ViewBinding layout, observes the `NovaViewModel`, and starts the runtime
  foreground service.
- `render()` is deliberately mechanical: it copies `NovaUiState` values into views, chooses the
  state color, and exposes the retry button only when unavailable.
- `animateOrb()` maps a visible state to one animation. Listening/speaking pulse;
  processing/executing rotate.
- `startRuntime(reconnect)` sends `ACTION_RECONNECT` only when the driver retries.

This separation is useful: state and message decisions are testable outside Android view code.

The visual structure is in
[activity_nova.xml](../app/src/main/res/layout/activity_nova.xml), while colors, strings, icons, and
backgrounds live under `app/src/main/res`.

## 3. Follow the UI state backwards

Open these files next:

1. [NovaViewModel.kt](../app/src/main/java/com/hypernova/ai/ui/NovaViewModel.kt)
2. [NovaUiStateFactory.kt](../app/src/main/java/com/hypernova/ai/ui/NovaUiStateFactory.kt)
3. [NovaUiState.kt](../app/src/main/java/com/hypernova/ai/ui/NovaUiState.kt)
4. [NovaVisibleState.kt](../app/src/main/java/com/hypernova/ai/ui/NovaVisibleState.kt)
5. [NovaStateMachine.kt](../app/src/main/java/com/hypernova/ai/session/NovaStateMachine.kt)

`NovaViewModel` observes runtime snapshots and validates each visible-state transition with
`NovaStateMachine`. `NovaUiStateFactory` then converts real runtime fields into concise,
driver-facing text.

The factory is where wording belongs. Network parsing and views should not contain competing
versions of the same user message.

The eight internal states are:

```text
IDLE, LISTENING, PROCESSING, EXECUTING,
SUCCESS, ERROR, SPEAKING, UNAVAILABLE
```

`NovaActivity` displays `IDLE` as `READY` and `SUCCESS` as `COMPLETED`.

## 4. Read the central orchestrator

Open [NovaRuntimeService.kt](../app/src/main/java/com/hypernova/ai/runtime/NovaRuntimeService.kt).

This foreground service is the center of the Android runtime:

- loads the Pi endpoint;
- owns the control client, audio client, and PCM player;
- starts both sockets and keeps them alive outside the activity lifecycle;
- parses incoming JSON messages;
- publishes transcript, action, result, response, and error data;
- maps wire states to Android visible states;
- reports playback start/end to the Pi;
- handles explicit reconnect requests.

The main parser is `onControlMessage()`. The current Pi event types are:

| Event | Android effect |
|---|---|
| `state` | Updates the underlying control state |
| `transcript` | Stores what the driver said and moves to processing |
| `action` | Stores tool information and moves to executing or error |
| `result` | Stores the completed result and moves to success or error |
| `say` | Stores the response text |
| `error` | Stores the error and moves to error |

Actual PCM playback owns the `SPEAKING` state. A text event saying “speaking” is not trusted over the
real player lifecycle.

## 5. Understand the two sources of state

Open:

- [NovaStateCoordinator.kt](../app/src/main/java/com/hypernova/ai/runtime/NovaStateCoordinator.kt)
- [NovaRuntimeState.kt](../app/src/main/java/com/hypernova/ai/runtime/NovaRuntimeState.kt)
- [NovaRuntimeSnapshot.kt](../app/src/main/java/com/hypernova/ai/runtime/NovaRuntimeSnapshot.kt)

`NovaStateCoordinator` combines three independent facts:

```text
control socket connected?
audio socket connected?
audio currently playing?
```

Its priority is:

```text
either socket disconnected → UNAVAILABLE
audio playing              → SPEAKING
otherwise                  → latest control state
```

This fixes a common race: a wake acknowledgement can be playing while the Pi has already progressed
to processing. The UI shows speaking during playback and reveals the remembered processing state
after playback ends.

`NovaRuntimeState` is the in-process source of truth. It publishes both the simple visible state and
the richer `NovaRuntimeSnapshot`. Updates are queued on the main thread so fast transcript, action,
and result events are not silently coalesced or reordered.

## 6. Read the control connection

Open [NovaControlClient.kt](../app/src/main/java/com/hypernova/ai/network/NovaControlClient.kt).

It owns the UTF-8 JSON Lines connection on TCP 8765:

- opens the socket and sends `hello`;
- reads one JSON object per line;
- serializes writes under `writerLock`;
- assigns a monotonically increasing `seq`;
- reconnects with exponential backoff from 250 ms to 5 seconds.

`sendCommand()` already supports Android text requests to the Pi, but the current UI has no text
input wired to it. It does not route commands to Navigation or Climate.

`sendPlayback()` is active. It tells the Pi when Android starts and finishes NOVA response playback
so speaker audio does not re-trigger the Pi wake word.

The full transport contract is in
[NOVA_RUNTIME_PROTOCOL.md](NOVA_RUNTIME_PROTOCOL.md).

## 7. Read the audio path

Open:

1. [AudioFrame.kt](../app/src/main/java/com/hypernova/ai/protocol/AudioFrame.kt)
2. [NovaAudioClient.kt](../app/src/main/java/com/hypernova/ai/network/NovaAudioClient.kt)
3. [NovaPcmPlayer.kt](../app/src/main/java/com/hypernova/ai/audio/NovaPcmPlayer.kt)

`AudioFrameCodec` encodes and validates the `NVA1` binary frame header. `NovaAudioClient` owns TCP
8766, answers ping frames, and forwards `TTS_START`, `TTS_PCM`, and `TTS_END` to the service.

`NovaPcmPlayer`:

- reads the sample rate and `turn_id` from `TTS_START`;
- requests transient Android audio focus with `USAGE_ASSISTANT`;
- buffers the mono signed 16-bit PCM stream;
- writes it to an `AudioTrack`;
- reports the real start and end of playback;
- releases the track and audio focus.

`sendMicStart()`, `sendMicPcm()`, and `sendMicEnd()` remain protocol helpers but are not used in the
approved topology. The microphone is captured directly by the Pi.

## 8. See how Launcher receives NOVA state

NOVA exports a read-only, signature-protected AIDL service:

- [INovaStatusService.aidl](../app/src/main/aidl/com/hypernova/ai/status/INovaStatusService.aidl)
- [INovaStatusCallback.aidl](../app/src/main/aidl/com/hypernova/ai/status/INovaStatusCallback.aidl)
- [NovaStatusService.kt](../app/src/main/java/com/hypernova/ai/status/NovaStatusService.kt)

The launcher keeps a matching copy of the versioned AIDL contract and binds through:

- [NovaStatusClient.kt](../../HyperNova_Launcher_Task_01/app/src/main/java/com/hypernova/launcher/core/assistant/NovaStatusClient.kt)
- [LauncherStateController.kt](../../HyperNova_Launcher_Task_01/app/src/main/java/com/hypernova/launcher/core/state/LauncherStateController.kt)

`getApiVersion()` protects against silently incompatible APKs. `registerCallback()` immediately
returns the latest value and then publishes changes. The signature permission means the final NOVA
and Launcher APKs must use the same approved platform/product signing key.

Only customer-visible assistant state crosses this boundary. Hardware topology and Pi connection
details do not.

## 9. Understand the Pi agent boundary

The Pi runtime currently lives outside this Android Git repository in `agent_loop_v3.py`.

Its relevant stages are:

```text
wake detection
  → record utterance
  → ASR
  → safety/intent decision
  → tool execution
  → optional second LLM pass
  → final response
  → TTS frames to Android
```

The vehicle abstraction is still `MockIVI`. `SomeIpIVI` is a placeholder, so today's command result
does not prove that an Android destination app or the TC397 completed an action.

The next architecture adds a destination-command request/result round trip:

```text
Pi agent
  → NOVA Android command broker
  → Navigation app or Climate app
  → confirmed/rejected result
  → NOVA Android
  → Pi final response and TTS
```

The concrete team contract is documented in
[NAVIGATION_CLIMATE_COMMAND_HANDOFF.md](NAVIGATION_CLIMATE_COMMAND_HANDOFF.md).

## 10. Where to make common changes

| Change | Primary location |
|---|---|
| Driver-facing wording | `NovaUiStateFactory` and string resources |
| State transition rule | `NovaStateMachine` |
| Socket availability/speaking priority | `NovaStateCoordinator` |
| New Pi JSON event | `NovaRuntimeService.onControlMessage()` |
| New Android-to-Pi JSON message | `NovaControlClient` |
| Binary audio frame | `AudioFrame` and `NovaAudioClient` |
| Playback/focus behavior | `NovaPcmPlayer` |
| Launcher-visible NOVA status | AIDL contract and `NovaStatusService` |
| Navigation/Climate execution | the new command broker and destination AIDL clients |

## 11. Tests worth reading

Run:

```bash
./gradlew testDebugUnitTest
```

Then inspect `app/src/test`. The most valuable unit boundaries are state transitions, wire-state
coordination, protocol framing, and UI snapshot-to-message mapping. New command routing should add
contract, correlation, timeout, duplicate-request, and confirmed-versus-accepted tests before it is
connected to real vehicle behavior.
