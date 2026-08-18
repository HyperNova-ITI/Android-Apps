# HyperNova Theme Toggle

The theme button supports two installation modes.

## Development APK

Package example: `com.hypernova.launcher.dev`

The application is installed with `adb install`. It is not privileged, so it
uses `UiModeManager.setApplicationNightMode()` and changes HyperNova Launcher
only.

## Production AOSP build

Package: `com.hypernova.launcher`

Install the launcher as a privileged application under `product/priv-app` and
copy:

`aosp/product/etc/permissions/privapp-permissions-hypernova-launcher.xml`

into the product permissions partition. The same button then uses
`UiModeManager.setNightMode()` and changes the Android system mode.
