# Build and Deploy

## Java environment

This host's default Java 25 installation lacks `javac`. All successful builds
used the installed JDK 21 explicitly:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
```

## Contracts

```bash
cd /home/ayman/ITI/Android-Apps/HyperNova_Launcher_Task_01
./gradlew -p /home/ayman/ITI/Android-Apps/HyperNova_Contracts \
  clean :contracts:assembleDebug
./gradlew -p /home/ayman/ITI/Android-Apps/HyperNova_Contracts \
  :contracts:assembleDebugAndroidTest
./gradlew -p /home/ayman/ITI/Android-Apps/HyperNova_Contracts \
  :contracts:connectedDebugAndroidTest
```

Result for this release: debug AAR and the 9-test instrumentation APK compiled
successfully. The instrumentation suite was not executed because no target was
started as part of this task.

## Navigation

```bash
cd /home/ayman/ITI/Android-Apps/HyperNova_Navigation_Task_03/HyperNovaNavigation
./gradlew clean :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

Result: succeeded. Production APK:

```text
/home/ayman/ITI/Android-Apps/HyperNova_Navigation_Task_03/HyperNovaNavigation/app/build/outputs/apk/release/app-release-unsigned.apk
SHA256: 25188c0e4bde48b55110ef6143fa7a821239ca6cd32e4ca02951613ddb494b31
Package: com.hypernova.navigation
```

## Launcher

```bash
cd /home/ayman/ITI/Android-Apps/HyperNova_Launcher_Task_01
./gradlew clean assembleDebug testDebugUnitTest assembleRelease
```

Result: succeeded. Production APK:

```text
/home/ayman/ITI/Android-Apps/HyperNova_Launcher_Task_01/app/build/outputs/apk/release/app-release-unsigned.apk
SHA256: 4062cb0a286fe13955926a6ce32c5df8cd24566847927efad50c3ad34439242b
Package: com.hypernova.launcher
```

The merged APK manifest contains HOME and LAUNCHER intent filters and does not
request `android.permission.READ_PRIVILEGED_PHONE_STATE`,
`android.car.permission.CONTROL_CAR_CLIMATE`, `ACCESS_COARSE_LOCATION`, or
`ACCESS_FINE_LOCATION`. `INTERNET`/`ACCESS_NETWORK_STATE` support read-only map
style and tile loading.

## AOSP import status

The existing Launcher import is:

```text
Module: HyperNovaLauncher
Destination: /mnt/wwn-0x5002538e7006e10b-part3/device/hypernova/cockpit/apps/HyperNovaLauncher/HyperNovaLauncher.apk
Old SHA256: 32bf3f15c116382019a7e437ea8dda2ba276ae8edd3a8147508f77c14808cb4c
New SHA256: 4062cb0a286fe13955926a6ce32c5df8cd24566847927efad50c3ad34439242b
```

The Launcher APK was replaced and its original was saved as:

```text
/home/ayman/ITI/Android-Apps/HyperNova_Launcher_Task_01/aosp-backup/HyperNovaLauncher-before-large-live-map-20260802.apk
```

There is no `HyperNovaNavigation` directory, `Android.bp`, imported APK, or
`PRODUCT_PACKAGES` entry anywhere in the current AOSP source tree. Therefore no
Navigation AOSP APK was replaced and no destination hash exists. Creating a new
module would violate the instruction to use the existing module and exceed the
authorized AOSP write scope. The running device currently has Navigation as a
debug/test-only `/data/app` package, not as an AOSP system module.

## AOSP commands

The discovered lunch target is
`hypernova_cockpit_x86_64-trunk_staging-userdebug`. With the tree as it exists,
only the real Launcher module can be built:

```bash
cd /mnt/wwn-0x5002538e7006e10b-part3
source build/envsetup.sh
lunch hypernova_cockpit_x86_64-trunk_staging-userdebug
m HyperNovaLauncher -j10
m systemextimage -j10
m superimage -j10
```

Do not run `m HyperNovaNavigation` until an authorized, existing Navigation
module is present and included by the product; that module name is not currently
defined.

An unapplied, separately documented import proposal is in
`12_PROPOSED_AOSP_NAVIGATION_MODULE.md`. No AOSP product or build file was
changed.

## Fresh Cuttlefish start

After manually completing the AOSP build:

```bash
cd /mnt/wwn-0x5002538e7006e10b-part3
source build/envsetup.sh
lunch hypernova_cockpit_x86_64-trunk_staging-userdebug
${ANDROID_HOST_OUT}/bin/stop_cvd || true
${ANDROID_HOST_OUT}/bin/launch_cvd \
  --daemon=true \
  --resume=false \
  --data_policy=always_create \
  --report_anonymous_usage_stats=n
```

`--data_policy=always_create` intentionally creates fresh userdata.
