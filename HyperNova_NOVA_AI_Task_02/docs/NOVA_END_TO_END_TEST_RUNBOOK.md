# NOVA end-to-end Android 16 + Raspberry Pi test runbook

This runbook verifies the NOVA Android app, HyperNova Launcher, Raspberry Pi voice runtime, and
Android-owned response playback. It is the laptop test baseline before moving the APKs to the NXP
i.MX 8QM Android 16 guest.

## Current boundary

- The Raspberry Pi owns the USB microphone, wake word, VAD, ASR, LLM/tool loop, and TTS synthesis.
- Android owns NOVA response playback through the laptop or NXP speaker.
- The user interface exposes only assistant and vehicle behavior. It never exposes this hardware
  topology to the driver.
- The Pi currently uses `MockIVI`. Climate and navigation commands do not reach a production app or
  the TC397 until the command-broker contract is implemented.

## 1. Power on and locate the Pi

From the laptop:

```bash
ping -c 2 hnc-ai30.local
ssh -i ~/.ssh/id_ed25519_hypernova_nova nova@hnc-ai30.local hostname -I
```

Save the returned LAN address for the rest of the session:

```bash
PI_IP=192.168.1.32
```

Replace the example address if DHCP assigned a different one. Android should use the IPv4 address,
not `.local`.

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
"$ANDROID_SDK_ROOT/emulator/emulator" \
  -avd HyperNova_API_36 \
  -skin 1080x1920 \
  -no-snapshot \
  -no-boot-anim \
  -gpu auto \
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

## 6. Build and install NOVA

From the `Android-Apps` repository:

```bash
cd HyperNova_NOVA_AI_Task_02
./gradlew -PnovaHost="$PI_IP" testDebugUnitTest assembleDebug
"$ADB" shell pm clear com.hypernova.ai
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
```

Clearing app data matters when the default Pi address changes because NOVA persists its last
configured endpoint.

## 7. Build and install HyperNova Launcher

```bash
cd ../HyperNova_Launcher_Task_01
./gradlew testDebugUnitTest assembleDebug
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell cmd package set-home-activity --user 0 \
  com.hypernova.launcher.dev/com.hypernova.launcher.MainActivity
"$ADB" shell input keyevent KEYCODE_HOME
```

Expected: HyperNova Launcher is the HOME screen. Its NOVA widget should change from unavailable to
ready when both Pi sockets are connected.

## 8. Open NOVA and check the baseline

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

## 9. Run the voice tests

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

Expected today: the Pi recognizes the command, Android shows the state sequence, and Android plays
the spoken result. This is still a `MockIVI` result; it is not proof that the Climate app or TC397
performed the action.

### Playback and wake-word isolation

While NOVA is speaking, do not issue another command. Confirm that the cockpit speaker does not
re-trigger the Pi wake word. The Pi should suppress/reset wake detection between Android's
`playback started` and `playback ended` messages.

## 10. Test reconnect behavior

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

## 11. Collect logs

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
- Check whether the second LLM/tool pass completed.
- A future destination-app command must return one final `command_result`; an `accepted` event is not
  a completed result.

### State changes but no sound is heard

- Confirm both control and audio sockets are connected.
- Check `AudioTrack` output in Android logs.
- Confirm laptop emulator audio is not muted.
- On NXP, verify Android audio focus and output routing to the passed-through speaker.

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
