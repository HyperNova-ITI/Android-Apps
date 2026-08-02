# Rollback

## Large HOME / live-map release

The AOSP Launcher APK that immediately preceded this release is saved at:

```text
/home/ayman/ITI/Android-Apps/HyperNova_Launcher_Task_01/aosp-backup/HyperNovaLauncher-before-large-live-map-20260802.apk
SHA256: 32bf3f15c116382019a7e437ea8dda2ba276ae8edd3a8147508f77c14808cb4c
```

Restore it with:

```bash
cp /home/ayman/ITI/Android-Apps/HyperNova_Launcher_Task_01/aosp-backup/HyperNovaLauncher-before-large-live-map-20260802.apk \
  /mnt/wwn-0x5002538e7006e10b-part3/device/hypernova/cockpit/apps/HyperNovaLauncher/HyperNovaLauncher.apk
```

## Launcher AOSP APK

Restore the exact previous imported Launcher APK:

```bash
cp /home/ayman/ITI/Android-Apps/HyperNova_Launcher_Task_01/aosp-backup/HyperNovaLauncher-before-route-preview-20260802.apk \
  /mnt/wwn-0x5002538e7006e10b-part3/device/hypernova/cockpit/apps/HyperNovaLauncher/HyperNovaLauncher.apk
sha256sum /mnt/wwn-0x5002538e7006e10b-part3/device/hypernova/cockpit/apps/HyperNovaLauncher/HyperNovaLauncher.apk
```

Expected restored SHA256:

```text
267d3e559ddcb72cfe18da9b365c2278c15c0f4219c399ae3b9a6e245a9cc803
```

Rebuild `HyperNovaLauncher`, `systemextimage`, and `superimage` using the commands
in `07_BUILD_AND_DEPLOY.md`.

## Navigation AOSP APK

No Navigation AOSP APK was replaced, so there is nothing to roll back in that
tree.

## Source

No commit was created. Source rollback should remove only the files/hunks listed
for this task in the final report; do not reset the repositories wholesale,
because all three project areas contained pre-existing user work before this
integration began.
