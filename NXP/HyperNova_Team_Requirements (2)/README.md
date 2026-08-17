# HyperNova Android Automotive — Team Requirements

This package defines the common requirements for all HyperNova Android Automotive applications.

## Documents

| Document | Purpose |
|---|---|
| `00-Design-System/README.md` | Final Light/Dark colors, themes, resource rules, typography, spacing, and visual acceptance rules. |
| `01-App-Integration-Contract/README.md` | How every independent APK is discovered and opened by HyperNova Launcher. |
| `02-Navigation/README.md` | HyperNova Navigation requirements. |
| `03-Media/README.md` | HyperNova Media requirements. |
| `04-Phone/README.md` | HyperNova Phone requirements. |
| `05-Climate/README.md` | HyperNova Climate requirements. |
| `06-Weather/README.md` | HyperNova Weather requirements. |
| `07-Driver-Profile/README.md` | HyperNova Driver Profile requirements. |
| `08-Settings/README.md` | HyperNova Settings requirements. |
| `09-NOVA-AI/README.md` | NOVA AI requirements. |

## Fixed application identities

| Application | Repository name | Package / application ID | Public open action |
|---|---|---|---|
| Launcher | `HyperNova_Launcher` | `com.hypernova.launcher` | Android `HOME` |
| Navigation | `HyperNova_Navigation` | `com.hypernova.navigation` | `com.hypernova.navigation.action.OPEN` |
| Media | `HyperNova_Media` | `com.hypernova.media` | `com.hypernova.media.action.OPEN` |
| Phone | `HyperNova_Phone` | `com.hypernova.phone` | `com.hypernova.phone.action.OPEN` |
| Climate | `HyperNova_Climate` | `com.hypernova.climate` | `com.hypernova.climate.action.OPEN` |
| Weather | `HyperNova_Weather` | `com.hypernova.weather` | `com.hypernova.weather.action.OPEN` |
| Driver Profile | `HyperNova_Driver_Profile` | `com.hypernova.driverprofile` | `com.hypernova.driverprofile.action.OPEN` |
| Settings | `HyperNova_Settings` | `com.hypernova.settings` | `com.hypernova.settings.action.OPEN` |
| NOVA AI | `NOVA_AI` | `com.hypernova.ai` | `com.hypernova.ai.action.OPEN` |

These values are contracts. Do not rename them independently.

## Global architecture

```text
HyperNova Launcher
        |
        | Explicit public OPEN intent
        v
Independent full-screen HyperNova application
        |
        | Back or Home
        v
HyperNova Launcher
```

Opening an application and sharing dashboard data are separate features:

```text
Opening:
Launcher -> Intent -> Target MainActivity

Dashboard data:
Target service or MediaSession -> Launcher client -> Launcher card
```

## Mandatory first milestone for every application

1. Create an independent Android project.
2. Use the exact package name from the table.
3. Expose the exact public open action.
4. Apply the shared HyperNova DayNight design system.
5. Build and install the APK.
6. Confirm that its Launcher card opens the correct full-screen application.
7. Confirm that Back and Home return correctly.
8. Show honest empty/unavailable states; no fake data.
9. Test 1080 × 1920 portrait.
10. Add build, install, and test instructions to the application README.

## SDK baseline

All applications must use the same SDK baseline as the Launcher. The current Launcher baseline is:

```kotlin
compileSdk = 36.1
minSdk = 35
targetSdk = 36
Java = 11
```

Do not change one application independently. If the platform baseline changes, update all applications together.
