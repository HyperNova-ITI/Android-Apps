# Standard Android 16 NXP Deployment

Status: frozen target profile

The NXP Android guest is standard Android 16, not Android Automotive OS. The deployable HyperNova
APKs therefore do not require `android.car`, CarService, VHAL, Car UI, or the automotive hardware
feature.

## Application paths

| Component | Standard Android path |
|---|---|
| Launcher | normal `CATEGORY_HOME` activity; stock Android Settings fallback |
| NOVA | typed Android AIDL plus TCP/WebSocket to RPi5 `192.168.0.20` |
| Navigation | normal Android activity/service and MapLibre |
| Climate | Vehicle Gateway AIDL only; no CarProperty/VHAL backend |
| Vehicle Gateway | normal headless bound service; HNVG to QNX `192.168.0.51:6100` |
| Media | Media3, WebView/Internet radio, local media, and optional Bluetooth phone audio |
| Phone | normal Telecom/dialer APIs; HFP client only if already exposed by the guest |

Do not install the `HyperNovaSettings` CarSettings fork or other APK/source snapshots under
`HyperNova_RPi5_AAOS` on this guest. Those artifacts are retained only for the earlier AAOS target.

## ADB-only deployment boundary

The team will not rebuild the Android image. Every HyperNova application is installed with ADB.

1. Sign Launcher, NOVA, Navigation, Climate, Phone, and Vehicle Gateway with one local demo
   certificate so their signature-protected AIDL permissions work. This does not need the Android
   platform certificate.
2. Install the packages with `adb install`, grant only their declared runtime permissions, and use
   `cmd package set-home-activity` to select Launcher.
3. Use the guest's existing Internet, WebView, audio output, and Bluetooth capabilities.
4. An ordinary ADB-installed APK cannot add system Bluetooth profiles or acquire platform-only HFP
   client privileges. Real paired-phone HFP is available only if the current guest already exposes
   a compatible service/profile; otherwise Phone must report unavailable honestly.
5. Phone audio in Media similarly requires A2DP Sink/AVRCP Controller already enabled in the guest.
   Without it Media still supports YouTube, Internet radio, and local playback.

The Launcher Settings card first opens `com.hypernova.settings` when that optional app exists, then
falls back to the stock `android.settings.SETTINGS` activity on the NXP guest.

## Frozen network

All endpoints share bridged `192.168.0.0/24`: RPi5 `.20`, TC397 `.30`, laptop `.40`, NXP host `.50`,
QNX `.51`, and Android `.100`. QNX remains the only process allowed to own the TC397 TCP command
connection even though the devices are mutually reachable. For the separate CARLA/SOME-IP path,
the same laptop Ethernet connection also owns `192.168.1.10/24` and QNX owns
`192.168.1.51/24`. Neither laptop Ethernet subnet has a default gateway; Wi-Fi remains the laptop's
Internet/default route.
