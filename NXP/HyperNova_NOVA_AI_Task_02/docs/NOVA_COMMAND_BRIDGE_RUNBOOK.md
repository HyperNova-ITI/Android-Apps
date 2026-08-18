# NOVA command bridge vertical-slice runbook

This is the shortest proof that a driver command travels through the complete integration:

```text
driver speech/text
  → Pi intent/tool
  → TCP command_request
  → NOVA Android broker
  → typed AIDL
  → Navigation or Climate provider
  → final command_result
  → NOVA UI and Android speaker
```

The included provider APKs are integration harnesses. They use the exact frozen package names,
services, permission, AIDL, callback states, timeouts, and opaque destination IDs. Replacing them
with Ayman's and Mahgoub's APKs requires no change in NOVA or the Pi protocol.

## 1. Start the Android 16 device

Use the `HyperNova_API_36` emulator described in
[ANDROID_16_DEVELOPMENT.md](ANDROID_16_DEVELOPMENT.md), then verify:

```bash
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
"$ADB" wait-for-device
"$ADB" shell getprop ro.build.version.release
"$ADB" shell getprop ro.build.version.sdk
```

Expected: Android `16`, API `36`.

Set the cockpit test surface if the AVD uses its default phone size:

```bash
"$ADB" shell wm size 1080x1920
```

## 2. Locate the Pi and deploy agent v3.6

```bash
PI_IP="$(ssh -i ~/.ssh/id_ed25519_hypernova_nova \
  nova@hnc-ai30.local 'hostname -I' | awk '{print $1}')"
printf 'Pi: %s\n' "$PI_IP"
```

From the workspace root, copy the tested agent and its offline test:

```bash
scp -i ~/.ssh/id_ed25519_hypernova_nova \
  Claude-Obsidian-Vault-Docs/01-Nodes/AI-Node-RPi5/agent_loop_v3.py \
  Claude-Obsidian-Vault-Docs/01-Nodes/AI-Node-RPi5/test_agent_loop_v3_command_bridge.py \
  nova@hnc-ai30.local:~/

ssh -i ~/.ssh/id_ed25519_hypernova_nova nova@hnc-ai30.local \
  'cd ~ && python3 -m unittest -v test_agent_loop_v3_command_bridge.py'
```

Expected: seven tests pass.

## 3. Build and install the three APKs

From `Android-Apps/HyperNova_NOVA_AI_Task_02`:

```bash
./gradlew -PnovaHost="$PI_IP" -PnovaAssistantVolume=12 \
  testDebugUnitTest \
  :app:assembleDebug \
  :mock-navigation:assembleDebug \
  :mock-climate:assembleDebug

"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" install -r mock-navigation/build/outputs/apk/debug/mock-navigation-debug.apk
"$ADB" install -r mock-climate/build/outputs/apk/debug/mock-climate-debug.apk
"$ADB" shell pm clear com.hypernova.ai
```

Install NOVA first because it declares the shared signature permission. All three debug APKs are
signed by the same debug key. Do not ship the two mock APKs in the NXP image.

## 4. Start the Pi runtime

Terminal 1 on the Pi:

```bash
cd ~/llama.cpp
./build/bin/llama-server \
  -m ~/models/Qwen3-30B-A3B-Instruct-2507-Q3_K_S-2.70bpw.gguf \
  -t 4 -c 4096 -np 1 \
  --cache-type-k q8_0 --cache-type-v q8_0 \
  --host 127.0.0.1 --port 8080
```

Terminal 2 on the Pi:

```bash
cd ~
NOVA_MIC="plughw:CARD=Device,DEV=0" \
NOVA_ANDROID_SPEAKER=1 \
NOVA_ANDROID_COMMANDS=1 \
NOVA_WAKE_THRESHOLD=0.5 \
NOVA_WAKE=1 \
NOVA_EVENTS=1 \
python3 agent_loop_v3.py
```

The microphone remains on the Pi. Android owns response playback. The app never exposes that
hardware topology to the driver.

`novaAssistantVolume=12` is for the laptop emulator only. Omit it for production so Android
Automotive and the vehicle's volume controls remain authoritative.

## 5. Open the apps and establish the connection

Set both provider harnesses to `Normal`:

```bash
"$ADB" shell am start -n \
  com.hypernova.navigation/.NavigationMockActivity
"$ADB" shell am start -n \
  com.hypernova.climate/.ClimateMockActivity
```

Tap `Normal` in each, then open NOVA:

```bash
"$ADB" shell am start -a com.hypernova.ai.action.OPEN -p com.hypernova.ai
```

NOVA should reach `READY`. Verify both Pi ports if it remains unavailable:

```bash
nc -vz "$PI_IP" 8765
nc -vz "$PI_IP" 8766
```

## 6. Run the positive vertical slices

### Climate, final ACK required

Say:

```text
Hey NOVA, set the climate to 22 degrees
```

Expected:

```text
LISTENING → PROCESSING → EXECUTING → COMPLETED → SPEAKING → READY
```

The Climate harness changes to `22°C`. NOVA must not say “Climate set to 22°C” while the provider is
only `accepted`; it waits for `confirmed`.

### Saved destination and route preview

Say:

```text
Hey NOVA, navigate home
```

NOVA first requests the saved destinations, resolves the returned opaque Home ID, calls
`setDestination`, opens Navigation while the route is calculating, and speaks success only after
the provider reports that the real route preview is ready. Guidance starts only when the driver
presses Start in Navigation.

### Search, four choices, selection

Say:

```text
Hey NOVA, find coffee near me
```

NOVA presents up to four returned results. Then say:

```text
Hey NOVA, the second one
```

The second returned opaque ID, not the words “second one,” is sent to Navigation.

### Deterministic typed injection

Wake-word and Qwen timing can be bypassed while debugging the bridge. With NOVA already connected:

```bash
printf '%s\n' '{"type":"command","v":1,"text":"Set the climate to 22 degrees"}' \
  | nc -q 1 "$PI_IP" 8765

printf '%s\n' '{"type":"command","v":1,"text":"Navigate home"}' \
  | nc -q 1 "$PI_IP" 8765
```

These two phrases use the conservative Pi fast path but still cross TCP, Binder, the provider
callback, Android UI state, and Android response playback.

## 7. Prove failure honesty

Open a harness, choose a mode, return to NOVA, and repeat its command:

| Harness mode | Expected NOVA outcome |
|---|---|
| `Reject` | `ERROR`; speaks the provider rejection |
| `Unavailable` | `ERROR`; reports the domain unavailable |
| `Timeout` | stays `EXECUTING`, then `ERROR`; never reports success |
| `Normal` | `COMPLETED`, then response playback |

Climate mutation timeout is five seconds. Navigation search timeout is ten seconds; route timeout
is twenty seconds.

## 8. Collect evidence

```bash
"$ADB" logcat -c
"$ADB" logcat | rg 'NovaRuntimeService|NavigationCommand|ClimateCommand|AndroidRuntime'
```

Pi-side:

```bash
ssh -i ~/.ssh/id_ed25519_hypernova_nova nova@hnc-ai30.local \
  'tail -n 200 ~/nova-agent.log'
```

Record the driver phrase, `turn_id`, `request_id`, final status, final UI state, and provider mode.

## 9. Swap in the real apps

The real APK must use the frozen identity:

```text
Navigation: com.hypernova.navigation
Climate:    com.hypernova.climate
```

Uninstall the matching harness and install the teammate APK:

```bash
"$ADB" uninstall com.hypernova.navigation
"$ADB" install -r /path/to/ayman-navigation.apk

"$ADB" uninstall com.hypernova.climate
"$ADB" install -r /path/to/mahgoub-climate.apk
```

Re-run sections 6 and 7. NOVA, the Pi agent, and the JSON/AIDL contract do not change.

## Appendix: Android-only command smoke test

This isolates TCP, NOVA, Binder, and both provider APKs when the Pi is powered off. The emulator
reaches the laptop through `10.0.2.2`.

Build and install the temporary endpoint:

```bash
./gradlew -PnovaHost=10.0.2.2 :app:assembleDebug
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell pm clear com.hypernova.ai
```

Start the probe from the project root:

```bash
python3 tools/nova_command_probe.py
```

Then, in another terminal:

```bash
"$ADB" shell am start -a com.hypernova.ai.action.OPEN -p com.hypernova.ai
```

Expected:

```text
climate.set_temperature: accepted
climate.set_temperature: confirmed
navigation.get_saved_destinations: confirmed
navigation.set_destination: accepted
navigation.set_destination: confirmed
PASS: TCP -> NOVA -> AIDL -> Climate/Navigation -> final callback
```

Rebuild with `-PnovaHost="$PI_IP"` before the real Pi test.

## Last verified baseline

Verified on 2026-07-28 with Android 16/API 36 and `hnc-ai30.local`:

- Android unit tests and all three debug APK builds passed.
- Seven Pi bridge/audio tests passed on the Raspberry Pi.
- The Pi USB microphone opened as `plughw:CARD=Device,DEV=0`.
- Both live Pi↔Android sockets connected.
- `set_temperature(22°C)` returned accepted then confirmed; NOVA spoke only the confirmation.
- saved Home resolved to its returned opaque ID; its route preview was confirmed in 1.46 seconds.
- forced Climate rejection produced an error response and no success claim.
- Climate and Navigation harnesses were restored to `Normal` after testing.

This proves the integration path through the provider boundary. It does not claim real Navigation
data or TC397 actuation; those proofs require Ayman's and Mahgoub's production APKs.
