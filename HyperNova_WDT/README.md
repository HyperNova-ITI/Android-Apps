# HyperNova System Control — UI Baseline

Package: `com.hypernova.wdt`

UI actions:
- Restart
- Watchdog Timer

Both visible actions require confirmation and are sent to the local root backend. The engineering
kernel-panic backend command is intentionally not exposed by the passenger UI.

## Prepare Gradle wrapper

```bash
cd /home/ayman/ITI/Android-Apps/HyperNova_WDT
./bootstrap.sh
```

## Build

```bash
./gradlew :app:assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Replace Settings bottom icon with Power

```bash
cd /home/ayman/ITI/Android-Apps/HyperNova_WDT
./apply_launcher_power_nav.sh
```

The Launcher patch:
- changes `navSettings` to `navPower`
- uses the supplied Power icon
- launches `com.hypernova.wdt.action.OPEN`
- adds Launcher package visibility for `com.hypernova.wdt`
- creates a source backup under `HyperNova_WDT/backups/`
