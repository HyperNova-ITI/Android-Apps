# Mahgoub handoff: implement the NOVA Climate service

Status: implementation guide for **Frozen Demo API v1**

Your task is to implement the server side of the shared Climate AIDL over the real Climate repository
and TC397 backend. Do not edit generated Binder code and do not create a second AIDL copy.

## 1. What you are building

```text
NOVA Android
  → generated IClimateCommandService Proxy
  → ClimateCommandService
  → Climate repository
  → vehicle backend
  → TC397
  → ACK / rejection / authoritative readback
  → generated IClimateCommandCallback
  → NOVA Android
```

Frozen Demo API v1 operations:

```text
getCapabilities
getCurrentState
setPowerEnabled
setTargetTemperature
setFanLevel
setAcEnabled
setAutoModeEnabled
setRecirculationEnabled
```

Source of truth:

- [`IClimateCommandService.aidl`](../contracts/src/main/aidl/com/hypernova/contracts/climate/IClimateCommandService.aidl)
- [`IClimateCommandCallback.aidl`](../contracts/src/main/aidl/com/hypernova/contracts/climate/IClimateCommandCallback.aidl)
- [`ClimateContract.java`](../contracts/src/main/java/com/hypernova/contracts/climate/ClimateContract.java)
- [`ClimateCapabilities.java`](../contracts/src/main/java/com/hypernova/contracts/climate/ClimateCapabilities.java)
- [`ClimateState.java`](../contracts/src/main/java/com/hypernova/contracts/climate/ClimateState.java)
- [`ClimateResult.java`](../contracts/src/main/java/com/hypernova/contracts/climate/ClimateResult.java)

## 2. Pull and verify the shared module

After the contract commit is available on the shared branch:

```bash
cd Android-Apps
git pull
test -f HyperNova_Contracts/contracts/src/main/aidl/com/hypernova/contracts/climate/IClimateCommandService.aidl
```

Do not copy the AIDL files into the Climate project.

## 3. Create or configure the Climate Android project

Required identity:

```text
namespace: com.hypernova.climate
applicationId: com.hypernova.climate
compileSdk: 36
targetSdk: 36
minSdk: 35
```

If the Gradle project is located at:

```text
HyperNova_Climate_Task_05/HyperNovaClimate/settings.gradle.kts
```

add:

```kotlin
include(":hypernova-contracts")
project(":hypernova-contracts").projectDir =
    file("../../HyperNova_Contracts/contracts")
```

If `settings.gradle.kts` is directly inside `HyperNova_Climate_Task_05`, the path is instead:

```kotlin
include(":hypernova-contracts")
project(":hypernova-contracts").projectDir =
    file("../HyperNova_Contracts/contracts")
```

The path must resolve to the repository's single `HyperNova_Contracts/contracts` directory.

In the Climate app module:

```kotlin
dependencies {
    implementation(project(":hypernova-contracts"))
}
```

Build from the Climate Gradle project:

```bash
./gradlew :app:assembleDebug
```

This automatically generates and compiles the Binder `Stub`, `Proxy`, and callback code. Generated
files remain under `build/generated` and must not be committed.

In Android Studio, run **Sync Project with Gradle Files** after editing the Gradle settings.

## 4. Verify generated types are available

Climate must be able to import:

```kotlin
import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.climate.ClimateCapabilities
import com.hypernova.contracts.climate.ClimateContract
import com.hypernova.contracts.climate.ClimateResult
import com.hypernova.contracts.climate.ClimateState
import com.hypernova.contracts.climate.IClimateCommandCallback
import com.hypernova.contracts.climate.IClimateCommandService
```

If imports fail, correct the Gradle module path/dependency. Do not copy generated or AIDL files.

## 5. Required internal architecture

```text
Climate UI
  ┐
  ├→ one application-scoped ClimateRepository
  ┘
ClimateCommandService
  → ClimateCommandManager
  → ClimateBackend
  → Tc397ClimateBackend
```

The UI and AIDL service must read the same authoritative repository state.

Minimum internal boundary:

```kotlin
interface ClimateRepository {
    fun getCapabilities(): InternalClimateCapabilities
    fun getConfirmedState(): InternalClimateState

    fun setPowerEnabled(enabled: Boolean): InternalCommandResult
    fun setTargetTemperature(zone: Int, temperatureC: Float): InternalCommandResult
    fun setFanLevel(level: Int): InternalCommandResult
    fun setAcEnabled(enabled: Boolean): InternalCommandResult
    fun setAutoModeEnabled(enabled: Boolean): InternalCommandResult
    fun setRecirculationEnabled(enabled: Boolean): InternalCommandResult
}
```

The real API may be asynchronous or use `StateFlow`; the important boundary is that every command
returns only after ACK/readback, rejection, disconnection, or timeout.

Do not let the service or UI talk directly to CAN, SOME/IP, serial, Ethernet, or TC397 frames.

## 6. Freeze the TC397 capability mapping

Before advertising a feature, fill this table with the vehicle team:

| AIDL operation | Supported | TC397 command/property | Request payload | Positive ACK/readback | Rejection | Range |
|---|---:|---|---|---|---|---|
| `setPowerEnabled` |  |  |  |  |  | boolean |
| `setTargetTemperature` |  |  |  |  |  | min/max/step |
| `setFanLevel` |  |  |  |  |  | 0..max |
| `setAcEnabled` |  |  |  |  |  | boolean |
| `setAutoModeEnabled` |  |  |  |  |  | boolean |
| `setRecirculationEnabled` |  |  |  |  |  | boolean |

`getCapabilities` must reflect this real mapping. An operation with no finished controller mapping is
reported unsupported; it is not simulated for the production demo.

## 7. Map internal capabilities to the contract

Constructor order:

```kotlin
private fun InternalClimateCapabilities.toContract(): ClimateCapabilities =
    ClimateCapabilities(
        zoneMode,
        minimumTemperatureC,
        maximumTemperatureC,
        temperatureStepC,
        maximumFanLevel,
        supportsPower,
        supportsTemperature,
        supportsAc,
        supportsAuto,
        supportsRecirculation,
    )
```

Rules:

- Use `ClimateContract.ZONE_MODE_SINGLE` or `ZONE_MODE_DUAL`.
- Temperature range and step come from the real backend.
- Maximum fan level comes from the real backend.
- Unsupported methods return `false`.
- Do not advertise a capability merely because its UI exists.

## 8. Map authoritative state to the contract

Constructor order:

```kotlin
private fun InternalClimateState.toContract(): ClimateState =
    ClimateState(
        availability,
        powerEnabled,
        driverTargetTemperatureC ?: Float.NaN,
        passengerTargetTemperatureC ?: Float.NaN,
        fanLevel ?: -1,
        acEnabled,
        autoModeEnabled,
        recirculationEnabled,
        updatedAtEpochMillis,
    )
```

Availability values:

```text
ClimateContract.AVAILABILITY_UNAVAILABLE
ClimateContract.AVAILABILITY_AVAILABLE
ClimateContract.AVAILABILITY_STALE
```

`Float.NaN` and `-1` mean unavailable. Never replace unknown vehicle state with demonstration
numbers.

The result state is always the latest confirmed/read-back state, not an optimistic requested state.

## 9. Create `ClimateCommandService`

Create:

```text
app/src/main/java/com/hypernova/climate/service/ClimateCommandService.kt
```

Use this structure:

```kotlin
package com.hypernova.climate.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.climate.ClimateContract
import com.hypernova.contracts.climate.ClimateResult
import com.hypernova.contracts.climate.IClimateCommandCallback
import com.hypernova.contracts.climate.IClimateCommandService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ClimateCommandService : Service() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val resultCache = ConcurrentHashMap<String, ClimateResult>()

    // Replace this with the same application-scoped repository used by the Climate UI.
    private val repository: ClimateRepository by lazy {
        ClimateRepositoryProvider.get(applicationContext)
    }

    private val binder = object : IClimateCommandService.Stub() {
        override fun getApiVersion(): Int = HyperNovaContract.API_VERSION

        override fun getCapabilities(
            requestId: String,
            callback: IClimateCommandCallback,
        ) {
            executeCapabilities(requestId, callback)
        }

        override fun getCurrentState(
            requestId: String,
            callback: IClimateCommandCallback,
        ) {
            executeCurrentState(requestId, callback)
        }

        override fun setPowerEnabled(
            requestId: String,
            enabled: Boolean,
            callback: IClimateCommandCallback,
        ) {
            executePower(requestId, enabled, callback)
        }

        override fun setTargetTemperature(
            requestId: String,
            zone: Int,
            temperatureC: Float,
            callback: IClimateCommandCallback,
        ) {
            executeTemperature(requestId, zone, temperatureC, callback)
        }

        override fun setFanLevel(
            requestId: String,
            fanLevel: Int,
            callback: IClimateCommandCallback,
        ) {
            executeFan(requestId, fanLevel, callback)
        }

        override fun setAcEnabled(
            requestId: String,
            enabled: Boolean,
            callback: IClimateCommandCallback,
        ) {
            executeAc(requestId, enabled, callback)
        }

        override fun setAutoModeEnabled(
            requestId: String,
            enabled: Boolean,
            callback: IClimateCommandCallback,
        ) {
            executeAuto(requestId, enabled, callback)
        }

        override fun setRecirculationEnabled(
            requestId: String,
            enabled: Boolean,
            callback: IClimateCommandCallback,
        ) {
            executeRecirculation(requestId, enabled, callback)
        }
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == ClimateContract.BIND_COMMAND_ACTION) binder else null

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
```

`ClimateRepositoryProvider` is a placeholder name. Replace it with Mahgoub's real application-scoped
dependency provider.

Do not perform vehicle I/O on a Binder or Android main thread.

## 10. Construct callback results

An accepted mutation:

```kotlin
private fun accepted(
    requestId: String,
    operation: String,
    message: String,
): ClimateResult = ClimateResult(
    requestId,
    operation,
    HyperNovaContract.STATUS_ACCEPTED,
    message,
    HyperNovaContract.ERROR_NONE,
    null,
    repository.getConfirmedState().toContract(),
)
```

A confirmed mutation:

```kotlin
private fun confirmed(
    requestId: String,
    operation: String,
    message: String,
): ClimateResult = ClimateResult(
    requestId,
    operation,
    HyperNovaContract.STATUS_CONFIRMED,
    message,
    HyperNovaContract.ERROR_NONE,
    null,
    repository.getConfirmedState().toContract(),
)
```

A hardware rejection:

```kotlin
private fun hardwareRejected(
    requestId: String,
    operation: String,
): ClimateResult = ClimateResult(
    requestId,
    operation,
    HyperNovaContract.STATUS_REJECTED,
    "The vehicle rejected the climate request",
    ClimateContract.ERROR_HARDWARE_REJECTED,
    null,
    repository.getConfirmedState().toContract(),
)
```

Capabilities query:

```kotlin
val result = ClimateResult(
    requestId,
    ClimateContract.OP_GET_CAPABILITIES,
    HyperNovaContract.STATUS_CONFIRMED,
    "Climate capabilities available",
    HyperNovaContract.ERROR_NONE,
    repository.getCapabilities().toContract(),
    repository.getConfirmedState().toContract(),
)
```

Every callback message is concise and driver-safe. Never expose controller frames, network addresses,
property IDs, stack traces, or hardware topology.

## 11. Required validation

Before sending a vehicle request:

- non-empty `requestId`;
- duplicate lookup before transmission;
- backend availability;
- capability supported;
- zone is `ZONE_ALL`, `ZONE_DRIVER`, or `ZONE_PASSENGER`;
- requested zone exists on this vehicle;
- temperature is inside min/max and aligned to step;
- fan level is within `0..maximumFanLevel`.

Return:

| Validation failure | Result |
|---|---|
| Bad request/value | `STATUS_REJECTED/INVALID_ARGUMENT` |
| Unsupported operation | `STATUS_REJECTED/UNSUPPORTED_OPERATION` |
| Unsupported zone | `STATUS_REJECTED/UNSUPPORTED_ZONE` |
| Temperature/fan out of range | `STATUS_REJECTED/OUT_OF_RANGE` |
| Backend disconnected | `STATUS_UNAVAILABLE/SERVICE_UNAVAILABLE` |

Validation failure must not transmit anything to TC397.

## 12. Command confirmation behavior

For every mutation:

1. Check duplicate cache.
2. Validate capability and argument.
3. Optionally send accepted.
4. Submit once to the Climate repository/backend.
5. Wait for positive ACK or authoritative matching readback.
6. Publish the new confirmed repository state.
7. Send exactly one final result.
8. Cache accepted/final results for at least ten minutes.

Five seconds is the frozen mutation timeout.

```text
Binder method returned       ≠ confirmed
TC397 frame transmitted      ≠ confirmed
TC397 accepted transport     ≠ confirmed
positive ACK/readback match  = confirmed
```

Explicit controller rejection returns `STATUS_REJECTED/HARDWARE_REJECTED`.
No confirmation within five seconds returns `STATUS_TIMEOUT/TIMEOUT`.

## 13. Temperature behavior

Zones:

```text
ClimateContract.ZONE_ALL
ClimateContract.ZONE_DRIVER
ClimateContract.ZONE_PASSENGER
```

`ZONE_ALL` means every available cabin zone.

For Demo API v1:

```text
"Set the climate to 22 degrees"
→ setTargetTemperature(ZONE_ALL, 22.0f)
```

If Climate is off, this high-level operation turns power on and applies the target as one correlated
command. Return confirmed only after both the required power state and all requested target values
are authoritative.

If a multi-property operation partially fails, do not claim success. Return rejected/timeout with the
latest confirmed state so NOVA can respond honestly.

## 14. Query behavior

`getCapabilities` and `getCurrentState`:

- use real repository values;
- return confirmed within two seconds;
- return unavailable when the backend has no authoritative data;
- mark the state stale when only an old confirmed snapshot is available;
- do not transmit a mutation to TC397.

## 15. Register the service

Inside the Climate app `<application>`:

```xml
<service
    android:name=".service.ClimateCommandService"
    android:exported="true"
    android:permission="com.hypernova.permission.CONTROL_COCKPIT_APPS">
    <intent-filter>
        <action android:name="com.hypernova.climate.action.BIND_COMMAND" />
    </intent-filter>
</service>
```

Climate remains an independent app:

```text
package: com.hypernova.climate
open action: com.hypernova.climate.action.OPEN
```

Do not declare `CATEGORY_HOME`.

## 16. Security and signing

- The command service is protected by the frozen signature permission.
- Do not add an unprotected test service in production source.
- NOVA and Climate must be signed with the same approved integration key.
- Default debug keys created on different laptops are different. Build all integration APKs on the
  integration laptop or use the approved shared debug-signing process.
- Never commit a private signing key.
- Do not log raw vehicle frames or sensitive controller details.

## 17. Demo API exclusions

These may exist inside Climate but are not exposed to NOVA Demo API v1:

```text
airflow direction
fresh air
zone sync
front/rear/max defrost
driver/passenger seat heating
```

Do not delay the frozen demo to add them to AIDL. A later compatible extension or API v2 can expose
them.

## 18. Tests Mahgoub must deliver

Unit tests:

- every capability true/false mapping;
- single-zone and dual-zone validation;
- temperature min/max/step;
- fan min/max;
- unsupported operation and zone;
- disconnected backend;
- duplicate `requestId`;
- positive ACK;
- explicit rejection;
- five-second timeout;
- partial `ZONE_ALL` failure;
- no optimistic confirmed-state mutation.

Integration/backend tests:

- service resolves through `com.hypernova.climate.action.BIND_COMMAND`;
- untrusted/differently signed APK cannot bind;
- each advertised operation produces the correct TC397 request;
- returned confirmed state matches ACK/readback;
- duplicate request produces one controller transmission;
- controller disconnect returns unavailable;
- service death/rebind creates no false success;
- Climate UI, Launcher snapshot, and NOVA callback show the same confirmed state.

Test doubles may exist under `src/test` or `src/androidTest`. Production builds must select the real
backend and must not silently fall back to a fake controller.

## 19. Delivery to the NOVA team

Provide:

- source commit/branch;
- debug APK built with the agreed integration key;
- completed TC397 capability/mapping table;
- actual supported temperature and fan ranges;
- confirmation/rejection/readback definitions;
- test report covering success, rejection, timeout, duplicate, disconnect, and rebind;
- any NXP guest permissions or vehicle-backend prerequisites.

Done means NOVA can query real Climate state, send every advertised Demo API v1 command, and receive
a final result that matches the TC397-confirmed vehicle state.
