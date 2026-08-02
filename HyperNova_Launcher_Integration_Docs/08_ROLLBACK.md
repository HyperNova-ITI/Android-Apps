# Rollback

Only the imported Launcher APK needs to be restored. Do not change any other
application or `Android.bp`.

## Original APK identity

```text
Destination:
/mnt/wwn-0x5002538e7006e10b-part3/device/hypernova/cockpit/apps/HyperNovaLauncher/HyperNovaLauncher.apk

Original SHA256:
33540d800c66a221db3ef4a953c5f5c24904b418e30b158472d20c05f0424c17

Original size:
23606456 bytes

Original mode/owner:
0664, 1000:1000
```

## Restore procedure

Use the retained original AOSP artifact from the product's artifact store or
workspace snapshot and verify it before copying:

```bash
sha256sum /path/to/original/HyperNovaLauncher.apk
# Must equal 33540d800c66a221db3ef4a953c5f5c24904b418e30b158472d20c05f0424c17

cp /path/to/original/HyperNovaLauncher.apk \
  /mnt/wwn-0x5002538e7006e10b-part3/device/hypernova/cockpit/apps/HyperNovaLauncher/HyperNovaLauncher.apk

chmod 0664 \
  /mnt/wwn-0x5002538e7006e10b-part3/device/hypernova/cockpit/apps/HyperNovaLauncher/HyperNovaLauncher.apk

sha256sum \
  /mnt/wwn-0x5002538e7006e10b-part3/device/hypernova/cockpit/apps/HyperNovaLauncher/HyperNovaLauncher.apk
```

Then rebuild only the discovered module in the correct configured environment:

```bash
m HyperNovaLauncher -j10
```

After flashing/syncing the resulting image, verify:

```bash
adb shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell dumpsys package com.hypernova.launcher | grep versionName
```

The original APK metadata/hash was recorded before replacement. No backup APK
was created elsewhere in the AOSP application tree, so rollback must use the
retained product artifact/workspace snapshot matching the recorded hash.
