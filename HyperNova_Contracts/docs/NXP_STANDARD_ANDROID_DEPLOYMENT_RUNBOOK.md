# NXP Standard Android 16 Deployment Runbook

Target: NXP i.MX 8QM with standard Android 16 guest and QNX 8 guest

## 1. Frozen network

| Node | IPv4 |
|---|---:|
| RPi5 | `192.168.0.20/24` |
| TC397 | `192.168.0.30/24` |
| laptop | `192.168.0.40/24` |
| NXP hypervisor host | `192.168.0.50/24` |
| QNX guest | `192.168.0.51/24` |
| Android guest | `192.168.0.100/24` |

The NXP inter-guest network must be bridged to the physical switch. Do not proceed while any two
nodes claim the same address. QNX owns `.51`; a laptop may claim `.51` only when QNX is stopped for
an isolated relay test.

From Android, verify Pi and QNX reachability:

```bash
adb connect 192.168.0.100:5555
adb shell ip -br -4 address
adb shell ping -c 2 192.168.0.20
adb shell ping -c 2 192.168.0.51
```

From QNX, verify TC397:

```bash
ping 192.168.0.30
```

## 2. TC397 firmware

Build and flash the final reviewed firmware after confirming these source constants:

```c
TC397 address:          192.168.0.30/24
UDP telemetry target:  192.168.0.51:6000
TCP command server:    port 6001
```

Do not start a laptop board harness while QNX owns TC397's single TCP client slot.

## 3. QNX gateway

Cross-build `HyperNova_VehicleGateway_Task_10/qnx-service` with the image team's QNX 8 toolchain,
copy the resulting `hypernova-qnx-gateway` binary into the QNX guest, and supervise it with
restart-on-failure. Its frozen defaults are HNVG TCP `6100`, TC397 `192.168.0.30:6001`, and UDP
telemetry `6000`.

For the isolated demo, start it as:

```bash
hypernova-qnx-gateway --tc-address 192.168.0.30
```

Only this process may connect to TC397. Production/interconnected use requires an authenticated
Android/QNX transport; plaintext is a documented demo exception on the isolated switch.

## 4. Build Android APKs

All applications must be signed by the same certificate because the AIDL permissions use
`protectionLevel="signature"`. Never mix independently signed debug and release APKs.

Generate or retrieve the private Pi/Android link token without committing it:

```bash
read -rsp "NOVA link token: " NOVA_LINK_TOKEN
export NOVA_LINK_TOKEN
```

Build NOVA with the frozen Pi address:

```bash
cd HyperNova_NOVA_AI_Task_02
./gradlew -PnovaHost=192.168.0.20 \
  -PnovaLinkToken="$NOVA_LINK_TOKEN" \
  :app:assembleRelease
cd ..
```

The curated bundle in `release-apks/20260813/` contains unsigned Launcher, Navigation, Media, Phone,
Climate, and isolated-demo Vehicle Gateway APKs. The Gateway artifact is built for
`192.168.0.51:6100` with plaintext explicitly enabled. NOVA is deliberately excluded because its
private token must be injected locally.

Sign every APK—including the locally built NOVA APK—with the same demo or product key using Android
SDK `apksigner`. Verify each result with `apksigner verify --verbose APK`.

## 5. Install order

Remove older APKs signed with a different certificate first; `adb install -r` cannot replace an app
whose certificate changed. Then install in this order:

1. Vehicle Gateway
2. Climate
3. Navigation
4. Media
5. Phone
6. NOVA
7. Launcher last

Use the signed paths, not the unsigned filenames directly:

```bash
adb install -r HyperNovaVehicleGateway-demo-release-signed.apk
adb install -r HyperNovaClimate-release-signed.apk
adb install -r HyperNovaNavigation-release-signed.apk
adb install -r HyperNovaMedia-release-signed.apk
adb install -r HyperNovaPhone-release-signed.apk
adb install -r HyperNovaNOVA-release-signed.apk
adb install -r HyperNovaLauncher-release-signed.apk
```

Select HyperNova Launcher as HOME:

```bash
adb shell cmd package set-home-activity \
  com.hypernova.launcher/com.hypernova.launcher.MainActivity
adb shell am force-stop com.hypernova.launcher
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

Grant only the runtime permissions used in the chosen demo. Phone may need Bluetooth, contacts,
call-log, call, and notification permissions; Media may need Bluetooth, notifications, and media
access. The final image should own this policy instead of showing dialogs during the presentation.

## 6. Standard-Android capability check

The APKs have no `android.car`, CarService, VHAL, or Automotive hardware-feature dependency.

- Launcher falls back to stock Android Settings; do not install the old HyperNova CarSettings fork.
- Phone UI, contacts, call history, and Telecom work with standard Android APIs. Paired-phone HFP
  control additionally needs HFP Client enabled plus the platform-signed HyperNova connectivity
  bridge in the image.
- Media Internet radio, YouTube/WebView, and local playback use standard Android. Phone audio
  additionally needs A2DP Sink and AVRCP Controller enabled in the Bluetooth stack.
- If those Bluetooth profiles are absent, Phone/Media must report unavailable; they must not show a
  fake connected state.

## 7. Full-flow acceptance order

1. QNX logs an Android HNVG connection and a TC397 TCP connection.
2. Climate changes fan level; UI shows pending/accepted only until the TC397 ACK, then confirmed.
3. Physical temperature/fuel values appear in Android through TC397 -> QNX -> Gateway -> Climate.
4. Press the random-fault button; the fault appears in Climate/NOVA. Press hardware clear; it clears.
5. Start the Pi runtime with the same link token; Launcher NOVA widget reaches ready/listening states.
6. Say a deterministic climate command and trace one request ID through NOVA, Climate, Gateway,
   QNX, and the TC397 result.
7. Search Navigation, choose a returned/saved destination, and verify selection does not start a
   trip until the separate start command.
8. Verify Media Internet/audio and Phone Bluetooth only after their platform capabilities pass.

Capture Android logs during the first integration:

```bash
adb logcat -v time \
  HyperNovaGateway:* HN-Climate:* NovaRuntime:* HyperNovaPhone:* HyperNovaBluetooth:* *:S
```

## Current blockers that this runbook cannot invent

- the QNX image team's working cross-toolchain/package/startup mechanism;
- the actual bridge/tap configuration that exposes the inter-guest network on Ethernet;
- a common APK signing key or image-side platform signing procedure;
- HFP Client and A2DP Sink/AVRCP Controller support if Phone/phone-audio are in demo scope.
