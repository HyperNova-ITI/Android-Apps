# NOVA end-to-end Android 16 + Raspberry Pi test runbook

This runbook verifies the NOVA Android app, HyperNova Launcher, Raspberry Pi voice runtime, and
Android-owned response playback. It is the laptop test baseline before moving the APKs to the NXP
i.MX 8QM Android 16 guest.

## Current boundary

- The Raspberry Pi owns the USB microphone, wake word, VAD, ASR, LLM/tool loop, and TTS synthesis.
- Android owns NOVA response playback through the laptop or NXP speaker.
- The user interface exposes only assistant and vehicle behavior. It never exposes this hardware
  topology to the driver.
- Climate and Navigation now cross the NOVA Android command broker and the shared AIDL contracts.
  The real Navigation service and Climate demo service are used in the normal laptop test.
- The Climate demo service updates the same state rendered by the Climate app and Launcher. It is
  honest about its scope: it proves app integration, not physical TC397 actuation.
- In the final vehicle, Climate owns HVAC validation and confirmation semantics, the Android Vehicle
  Gateway owns the single Android-to-QNX session, and the QNX service alone owns the raw TC397
  TCP/UDP sockets.

## 1. Power on and locate the Pi

From the laptop:

```bash
ping -c 2 hnc-ai30.local
ssh -i ~/.ssh/id_ed25519_hypernova_nova nova@hnc-ai30.local hostname -I
```

Save the returned LAN address for the rest of the session:

```bash
PI_IP=192.168.10.20
NOVA_LINK_TOKEN="$(openssl rand -hex 32)"
```

Replace the example address if DHCP assigned a different one. Android should use the IPv4 address,
not `.local`.

Put the same temporary token in the Pi service environment without committing it:

```bash
ssh -i ~/.ssh/id_ed25519_hypernova_nova nova@hnc-ai30.local
install -d -m 700 ~/.config/nova
umask 077
printf 'NOVA_LINK_TOKEN=%s\n' '<paste NOVA_LINK_TOKEN>' > ~/.config/nova/security.env
```

## 2. Verify the Pi microphone

```bash
ssh -i ~/.ssh/id_ed25519_hypernova_nova nova@hnc-ai30.local
arecord -l
arecord -D plughw:CARD=Device,DEV=0 \
  -f S16_LE -r 16000 -c 1 -d 3 /tmp/nova-mic-test.wav
```

Expected: the CM108/USB PnP Sound Device appears as a capture device and the recording finishes
without an ALSA error.

If the card name changed, use the value shown by `arecord -l` in `NOVA_MIC`.

## 3. Start the Pi runtime

Preferred service path:

```bash
systemctl --user start nova-llama.service
systemctl --user start nova-agent.service
systemctl --user status nova-llama.service
systemctl --user status nova-agent.service
curl http://127.0.0.1:8080/health
tail -f ~/nova-agent.log
```

Both services should be active, and the LLM health request should succeed.

If the user services are not installed, start the two processes in separate Pi terminals.

Terminal 1:

```bash
cd ~/llama.cpp
./build/bin/llama-server \
  -m ~/models/Qwen3-30B-A3B-Instruct-2507-Q3_K_S-2.70bpw.gguf \
  -t 4 -c 4096 -np 1 \
  --cache-type-k q8_0 --cache-type-v q8_0 \
  --host 127.0.0.1 --port 8080
```

Terminal 2:

```bash
cd ~
NOVA_MIC="plughw:CARD=Device,DEV=0" \
NOVA_ANDROID_SPEAKER=1 \
NOVA_ANDROID_COMMANDS=1 \
NOVA_LINK_TOKEN="<same temporary token>" \
NOVA_WAKE_THRESHOLD=0.5 \
NOVA_WAKE=1 \
NOVA_EVENTS=1 \
python3 agent_loop_v3.py
```

## 4. Verify the Pi endpoints from the laptop

```bash
nc -vz "$PI_IP" 8765
nc -vz "$PI_IP" 8766
```

Expected: both TCP connections succeed. If they fail, check the agent log, the Pi address, and any
host firewall before opening Android.

## 5. Boot the Android 16 emulator

```bash
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
QEMU_AUDIO_DRV=pa \
QEMU_PA_SAMPLES=8192 \
QEMU_AUDIO_DAC_FIXED_SETTINGS=1 \
QEMU_AUDIO_DAC_FIXED_FREQ=48000 \
"$ANDROID_SDK_ROOT/emulator/emulator" \
  -avd HyperNova_API_36 \
  -skin 1080x1920 \
  -no-snapshot-load \
  -no-snapshot-save \
  -no-boot-anim \
  -gpu swiftshader_indirect \
  -feature Vulkan \
  -netdelay none \
  -netspeed full
```

In another laptop terminal:

```bash
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
"$ADB" wait-for-device
"$ADB" shell getprop ro.build.version.release
"$ADB" shell getprop ro.build.version.sdk
"$ADB" shell wm size
```

Expected:

```text
16
36
Physical size: 1080x1920
```

The API 35 emulator is only a fallback. The project test target is Android 16/API 36.
MapLibre in Navigation and Launcher requires Vulkan. Do not launch this AVD with
`-feature -Vulkan`; that configuration makes both apps terminate with
`No Vulkan compatible GPU found`.

Keep the audio environment variables in the launch command. They use the PulseAudio backend,
match Android's 48 kHz output, and give QEMU enough host-side buffering for clean speech. The
default small/zero-latency emulator buffer produced audible breakup even when the exact same WAV
played cleanly on the laptop outside Android. Do not force `QEMU_AUDIO_TIMER_PERIOD`: a timer
override made the Ranchu virtual PCM device enter a persistent I/O-error state on later playbacks.

## 6. Build and install NOVA and the real feature apps

From the `Android-Apps` repository:

```bash
NOVA_HOST=10.0.2.2

cd HyperNova_NOVA_AI_Task_02
./gradlew -PnovaHost="$NOVA_HOST" -PnovaAssistantVolume=12 \
  -PnovaLinkToken="$NOVA_LINK_TOKEN" \
  testDebugUnitTest :app:assembleDebug
"$ADB" shell pm clear com.hypernova.ai
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk

cd ../HyperNova_Navigation_Task_03/HyperNovaNavigation
./gradlew testDebugUnitTest :app:assembleDebug
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk

cd ../../HyperNova_Climate_Task_05/HyperNovaClimate
./gradlew testDebugUnitTest :app:assembleDebug
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
```

Use `NOVA_HOST=10.0.2.2` for the laptop probe in step 8A. Use `NOVA_HOST="$PI_IP"` for the live Pi in
step 8B. Clearing app data matters when this address changes because NOVA persists its last endpoint.

NOVA is installed first because it declares the shared signature permission. All debug APKs must be
signed with the same debug certificate. The Climate debug service confirms state inside the app so
the UI, Launcher widget, and AIDL callbacks share one source of truth. The release build deliberately
does not pretend that a hardware command succeeded before the Vehicle Gateway/QNX path exists.

## 7. Build and install HyperNova Launcher

```bash
cd ../../HyperNova_Launcher_Task_01
./gradlew testDebugUnitTest assembleDebug
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell cmd package set-home-activity --user 0 \
  com.hypernova.launcher.dev/com.hypernova.launcher.MainActivity
"$ADB" shell input keyevent KEYCODE_HOME
```

Expected: HyperNova Launcher is the HOME screen. Its NOVA widget should change from unavailable to
ready when both Pi sockets are connected.

## 8A. Laptop-only integration mode

Build NOVA with `NOVA_HOST=10.0.2.2`. From `HyperNova_NOVA_AI_Task_02`, start the local probe before
opening NOVA:

```bash
python3 tools/nova_command_probe.py --hold
```

The probe substitutes only for the Pi sockets. It exercises the real path through NOVA and both real
feature APKs, then keeps the connection open for UI inspection. Expected terminal result:

```text
climate.set_temperature: accepted
climate.set_temperature: confirmed
navigation.get_saved_destinations: accepted
navigation.get_saved_destinations: confirmed
navigation.set_destination: accepted
navigation.set_destination: confirmed
PASS: TCP -> NOVA -> Climate/TC397 + Navigation route-ready callbacks
```

Open Launcher after the pass. Its Climate card should show the controller-confirmed 22°C target
while retaining the current confirmed fan setting and MANUAL/OFF state. Navigation should show the
prepared route preview for the returned saved-home destination but must not start the trip. Press
Ctrl+C when finished.

## 8B. Live Pi integration mode

Build NOVA with `NOVA_HOST="$PI_IP"`, keep the services from step 3 running, and do not start the
laptop probe.

## 9. Open NOVA and check the baseline

Tap the NOVA widget, or run:

```bash
"$ADB" shell am start -a com.hypernova.ai.action.OPEN -p com.hypernova.ai
```

Expected:

- NOVA opens in the approved cockpit theme.
- The visible state reaches `READY`.
- Back/Home returns cleanly to HyperNova Launcher.
- Launcher reflects NOVA's current state.
- No driver-facing label mentions Pi, microphone routing, Android speaker routing, host addresses, or
  TCP ports.

## 10. Run the voice tests

### General request

Say:

```text
Hey NOVA
Check the car for me
```

Expected sequence:

```text
READY → LISTENING → PROCESSING → EXECUTING → SPEAKING → COMPLETED → READY
```

The full agent path may take roughly 20–30 seconds on the current Pi because it can require two LLM
passes. The UI must continue to show the real state rather than declaring early success.

### Fast-path command

Say:

```text
Hey NOVA, turn on the AC
```

Expected today: the Pi recognizes the command, Android shows the state sequence, the real Climate
APK updates, the Launcher Climate card follows it, and Android plays the spoken result. This proves
the TCP → NOVA broker → AIDL → Climate callback path. It is not yet proof of TC397 actuation; that
proof requires Climate → Android Vehicle Gateway → QNX service → TC397 and the controller's
authoritative ACK.

### Playback and wake-word isolation

While NOVA is speaking, do not issue another command. Confirm that the cockpit speaker does not
re-trigger the Pi wake word. The Pi should suppress/reset wake detection between Android's
`playback started` and `playback ended` messages.

## 11. Test reconnect behavior

While NOVA is open:

```bash
ssh -i ~/.ssh/id_ed25519_hypernova_nova nova@hnc-ai30.local \
  systemctl --user restart nova-agent.service
```

Expected:

```text
READY → UNAVAILABLE → READY
```

NOVA should reconnect automatically without reinstalling or restarting Android.

## 12. Collect logs

Android:

```bash
"$ADB" logcat -c
"$ADB" logcat | rg 'Nova|AndroidRuntime|AudioTrack'
```

Pi:

```bash
ssh -i ~/.ssh/id_ed25519_hypernova_nova nova@hnc-ai30.local \
  tail -n 200 ~/nova-agent.log
```

When reporting a failure, include:

- the spoken or typed command;
- the final Android state;
- the Pi `turn_id`, if logged;
- Android log lines for the same time;
- whether control port 8765 and audio port 8766 were connected.

## Troubleshooting

### NOVA stays unavailable

- Recheck `PI_IP`.
- Re-run `nc -vz` for ports 8765 and 8766.
- Confirm both Pi services are active.
- Clear NOVA app data and rebuild with `-PnovaHost="$PI_IP"`.
- Confirm the laptop and Pi are on the same reachable LAN.

### Wake word is detected but no command follows

- Check `arecord -l` and `NOVA_MIC`.
- Watch `~/nova-agent.log` for VAD/ASR errors.
- Speak the command after the wake acknowledgement, not over it.

### UI says it will check but never finishes

- Follow the same `turn_id` in the Pi log.
- Follow the same `request_id` through the Android provider callback.
- Confirm the destination app returns one final `command_result`; `accepted` is not completion.
- If needed, install the mock provider APKs and use `Normal`, `Reject`, `Unavailable`, and `Timeout`
  modes to isolate callback handling. Do not use mocks for the normal real-app demo.

### State changes but no sound is heard

- Confirm both control and audio sockets are connected.
- Check `AudioTrack` output in Android logs.
- Confirm laptop emulator audio is not muted.
- On NXP, verify Android audio focus and output routing to the passed-through speaker.

### Emulator speech sounds rough or broken up

- Fully stop the emulator; changing the variables after it has started has no effect.
- Relaunch it with the four `QEMU_AUDIO_*`/`QEMU_PA_SAMPLES` settings from step 5.
- Confirm the laptop output device itself is clean by playing ordinary audio outside the emulator.
- This workaround is for the laptop QEMU audio backend. Revalidate clean playback separately on
  the NXP Android 16 guest and its real audio HAL.

## Stop the test safely

Stop the Pi services:

```bash
ssh -i ~/.ssh/id_ed25519_hypernova_nova nova@hnc-ai30.local \
  systemctl --user stop nova-agent.service nova-llama.service
```

Then shut down the Pi:

```bash
ssh -i ~/.ssh/id_ed25519_hypernova_nova nova@hnc-ai30.local \
  sudo shutdown -h now
```

Wait for shutdown to complete before removing power.
