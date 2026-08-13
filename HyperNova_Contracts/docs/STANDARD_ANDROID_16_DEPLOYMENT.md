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
| Phone | normal Telecom/dialer APIs plus optional platform HFP-client bridge |

Do not install the `HyperNovaSettings` CarSettings fork or other APK/source snapshots under
`HyperNova_RPi5_AAOS` on this guest. Those artifacts are retained only for the earlier AAOS target.

## Base-image capabilities still required

These are Android platform capabilities, not Android Automotive dependencies:

1. Make HyperNova Launcher the persistent/default HOME application.
2. Sign Launcher, NOVA, Navigation, Climate, Phone, and Vehicle Gateway with the same agreed
   certificate so their signature-protected AIDL permissions work.
3. Provide Internet, a working WebView implementation, audio output, and the required runtime
   permission policy.
4. If the demo includes phone-call control, enable the Bluetooth HFP Client profile and preinstall
   the platform-signed `HyperNovaConnectivityService`. It uses platform Bluetooth APIs but no
   `android.car` API. Without this image capability Phone must show HFP unavailable honestly.
5. If the demo includes phone audio in Media, enable A2DP Sink/AVRCP Controller in the Bluetooth
   stack. Without it Media still supports its other sources and reports phone audio unavailable.

The Launcher Settings card first opens `com.hypernova.settings` when that optional app exists, then
falls back to the stock `android.settings.SETTINGS` activity on the NXP guest.

## Frozen network

All endpoints share bridged `192.168.0.0/24`: RPi5 `.20`, TC397 `.30`, laptop `.40`, NXP host `.50`,
QNX `.51`, and Android `.100`. QNX remains the only process allowed to own the TC397 TCP command
connection even though the devices are mutually reachable.
