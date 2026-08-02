# HyperNova Launcher Runtime Integration

This directory documents the Launcher-only integration of Navigation, Media,
Settings, Climate, and Phone runtime state.

The implementation changes are confined to:

- `/home/ayman/ITI/Android-Apps/HyperNova_Launcher_Task_01`
- This documentation directory
- The existing AOSP Launcher APK at
  `/mnt/wwn-0x5002538e7006e10b-part3/device/hypernova/cockpit/apps/HyperNovaLauncher/HyperNovaLauncher.apk`

No sibling application or `HyperNova_Contracts` source was modified.

Documents:

1. [Architecture](01_ARCHITECTURE.md)
2. [Application integration matrix](02_APP_INTEGRATION_MATRIX.md)
3. [Runtime state flows](03_RUNTIME_STATE_FLOW.md)
4. [Changed files](04_CHANGED_FILES.md)
5. [Build and test](05_BUILD_AND_TEST.md)
6. [AOSP APK deployment](06_AOSP_APK_DEPLOYMENT.md)
7. [Limitations](07_LIMITATIONS.md)
8. [Rollback](08_ROLLBACK.md)

The central rule is that an installed package, a launchable application, a
connected state source, and an active feature are separate facts. The Launcher
does not create demo route, media, climate, settings, phone, contact, or call
data.
