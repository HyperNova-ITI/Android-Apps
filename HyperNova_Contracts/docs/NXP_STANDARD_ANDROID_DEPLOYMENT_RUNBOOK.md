# NXP Standard Android 16 Deployment Runbook

Target: NXP i.MX 8QM with standard Android 16 guest and QNX 8 guest

## 1. Frozen network

| Node | IPv4 |
|---|---:|
| RPi5 | `192.168.0.20/24` |
| TC397 | `192.168.0.30/24` |
| laptop, HyperNova vehicle network | `192.168.0.40/24` |
| NXP hypervisor host | `192.168.0.50/24` |
| QNX guest, HyperNova vehicle network | `192.168.0.51/24` |
| Android guest | `192.168.0.100/24` |
| laptop, CARLA/SOME-IP network | `192.168.1.10/24` |
| QNX guest, CARLA/SOME-IP network | `192.168.1.51/24` |

The NXP inter-guest network must be bridged to the physical switch. Do not proceed while any two
nodes claim the same address. QNX owns `.51`; a laptop may claim `.51` only when QNX is stopped for
an isolated relay test. The laptop and QNX guest deliberately have one address on each subnet. Put
both laptop addresses on the same Ethernet connection, with no gateway or DNS on that connection;
the laptop's Wi-Fi remains its Internet/default route.

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

## 4. Build Android APKs for ADB installation

The Android image is not rebuilt. Every application is installed through ADB. All applications must
be signed by the same local certificate because the AIDL permissions use
`protectionLevel="signature"`. The certificate does not have to be the Android platform certificate
for this application-to-application IPC. Never mix independently signed APKs.

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
  :app:assembleDebug
cd ..
```

This embeds the demo link token in the APK, where it can be extracted by anyone who obtains the
file. Use only the isolated-demo token, keep the APK private, and rotate the token after the demo.
For a production design, provision a per-device credential instead of compiling it into the APK.

Prepare Google Navigation with a restricted Maps Platform API key in the local ignored file:

```bash
cd HyperNova_Google_Navigation_Task_11/HyperNovaGoogleNavigation
cp -n secrets.properties.example secrets.properties
# Edit secrets.properties locally: MAPS_API_KEY=<restricted API key>
./scripts/prepare_mustafa_apk.sh
cd ../..
```

The preparation script rejects placeholders, Map IDs, and arbitrary strings; it runs the
Navigation build, unit tests, lint, package check, signature verification, and checksum generation.

Build the isolated-demo Gateway and the production-package Launcher explicitly:

```bash
cd HyperNova_VehicleGateway_Task_10
./gradlew -PgatewayHost=192.168.0.51 \
  -PgatewayAllowPlaintext=true \
  -PclusterHost=192.168.0.51 \
  -PmediaClusterHost=192.168.0.51 \
  :app:assembleDebug
cd ../HyperNova_Launcher_Task_01
./build_launcher_for_nxp.sh
cd ..
```

Do not enable `gatewayAllowPlaintext` outside the isolated demo network.

Do not treat the older `release-apks/20260813/` bundle as the current rollout. Build from the synced
`main` tree so the Google Navigation, phone overlay, media status transport, launcher/NOVA UI, and
QNX gateway fixes are included. The Gateway defaults to `192.168.0.51:6100`; plaintext remains an
explicit isolated-demo exception.

For the first isolated demo, the debug variants use the same laptop Android debug key. Verify every
candidate with `apksigner verify --verbose --print-certs APK` and confirm their certificate digests
match before installation. This is a non-production identity.

## 5. Install order

Remove older APKs signed with a different certificate first; `adb install -r` cannot replace an app
whose certificate changed. Then install in this order:

1. Vehicle Gateway
2. Climate
3. Google Navigation (`com.hypernova.navigation`)
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

Grant only the runtime permissions used in the chosen demo through ADB. Phone may need Bluetooth,
contacts, call-log, call, and notification permissions; Media may need Bluetooth, notifications,
and media access.

## 6. Standard-Android capability check

The APKs have no `android.car`, CarService, VHAL, or Automotive hardware-feature dependency.

- Launcher falls back to stock Android Settings; do not install the old HyperNova CarSettings fork.
- Phone UI, contacts, call history, and Telecom use standard Android APIs. An ADB-installed app cannot
  create an HFP Client profile or install the existing system-UID connectivity bridge as an ordinary
  APK. Paired-phone HFP is therefore conditional on a suitable capability already in the guest.
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
- the restricted Google Maps Platform API key in local `secrets.properties`;
- explicit approval to build NOVA with the isolated-demo token embedded in the private APK;
- HFP Client and A2DP Sink/AVRCP Controller support if Phone/phone-audio are in demo scope.
