# Ayman handoff: implement the NOVA Navigation service

Status: implementation guide for **Frozen Demo API v1**

Your task is to implement the server side of the shared Navigation AIDL. Do not edit generated
Binder code and do not create a second copy of the AIDL.

## 1. What you are building

```text
NOVA Android
  → generated INavigationCommandService Proxy
  → NavigationCommandService
  → Navigation repository
  → search / saved places / routing provider
  → generated INavigationCommandCallback
  → NOVA Android
```

The service implements four demo operations:

```text
searchDestinations
getSavedDestinations
setDestination
cancelNavigation
```

Source of truth:

- [`INavigationCommandService.aidl`](../contracts/src/main/aidl/com/hypernova/contracts/navigation/INavigationCommandService.aidl)
- [`INavigationCommandCallback.aidl`](../contracts/src/main/aidl/com/hypernova/contracts/navigation/INavigationCommandCallback.aidl)
- [`NavigationContract.java`](../contracts/src/main/java/com/hypernova/contracts/navigation/NavigationContract.java)
- [`NavigationDestination.java`](../contracts/src/main/java/com/hypernova/contracts/navigation/NavigationDestination.java)
- [`NavigationResult.java`](../contracts/src/main/java/com/hypernova/contracts/navigation/NavigationResult.java)

## 2. Pull and verify the shared module

After the contract commit is available on the shared branch:

```bash
cd Android-Apps
git pull
test -f HyperNova_Contracts/contracts/src/main/aidl/com/hypernova/contracts/navigation/INavigationCommandService.aidl
```

Do not copy files from `HyperNova_Contracts` into the Navigation app.

## 3. Connect Navigation to the contract module

Edit:

```text
HyperNova_Navigation_Task_03/HyperNovaNavigation/settings.gradle.kts
```

Add:

```kotlin
include(":hypernova-contracts")
project(":hypernova-contracts").projectDir =
    file("../../HyperNova_Contracts/contracts")
```

Edit:

```text
HyperNova_Navigation_Task_03/HyperNovaNavigation/app/build.gradle.kts
```

Use the agreed Android baseline:

```kotlin
android {
    defaultConfig {
        minSdk = 35
        targetSdk = 36
    }
}
```

Add:

```kotlin
dependencies {
    implementation(project(":hypernova-contracts"))
}
```

Then generate and compile the Binder API:

```bash
cd HyperNova_Navigation_Task_03/HyperNovaNavigation
./gradlew :app:assembleDebug
```

This build generates `Stub`, `Proxy`, and callback Binder classes automatically. Generated sources
appear under the contract module's `build/generated` directory and must remain untracked.

In Android Studio, run **Sync Project with Gradle Files** after changing `settings.gradle.kts`.

## 4. Verify generated types are available

The Navigation module must be able to import:

```kotlin
import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.navigation.INavigationCommandCallback
import com.hypernova.contracts.navigation.INavigationCommandService
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.contracts.navigation.NavigationDestination
import com.hypernova.contracts.navigation.NavigationResult
```

If these imports fail, fix the Gradle module path/dependency. Do not work around it by copying AIDL.

## 5. Create one repository shared by UI and service

The current search/routing implementation must not remain owned by `MainActivity`. Move it behind one
application-scoped Navigation repository that both the activity and service use.

Minimum service-facing boundary:

```kotlin
interface NavigationRepository {
    fun searchDestinations(query: String): List<ResolvedDestination>
    fun getSavedDestinations(): List<ResolvedDestination>
    fun resolveDestination(destinationId: String): ResolvedDestination?
    fun startGuidance(destination: ResolvedDestination): ActiveRoute
    fun cancelNavigation()
    fun hasActiveNavigation(): Boolean
}
```

`ResolvedDestination` and `ActiveRoute` are internal Navigation models. They do not belong in the
shared contract.

The repository owns:

- search provider requests;
- Home/Work lookup through Driver Profile;
- user-saved Navigation favorites;
- destination-ID storage and expiry;
- route calculation;
- active guidance state;
- ETA and distance.

The activity renders repository state. The AIDL service adapts the same state. Never create a second
route/search engine inside the service.

## 6. Destination ID rules

Every returned `NavigationDestination.id` is opaque to NOVA.

- Search results are stored in Navigation's destination-token store for at least ten minutes.
- Saved destination IDs remain valid while the underlying saved destination exists.
- `setDestination` resolves the ID through Navigation's store.
- NOVA never sends a list index, raw coordinates, or a newly invented ID.
- A duplicate `requestId` must return the cached accepted/final result without starting another
  search or route.

Saved result ordering:

1. Configured Home.
2. Configured Work.
3. Most recently used real saved favorites until the four-result limit is reached.

Missing entries are omitted. Before the demo, save real provider results through the Navigation UI;
do not hardcode demonstration addresses.

## 7. Create `NavigationCommandService`

Create:

```text
app/src/main/java/com/hypernova/navigation/service/NavigationCommandService.kt
```

Use this structure:

```kotlin
package com.hypernova.navigation.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.navigation.INavigationCommandCallback
import com.hypernova.contracts.navigation.INavigationCommandService
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.contracts.navigation.NavigationDestination
import com.hypernova.contracts.navigation.NavigationResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class NavigationCommandService : Service() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val resultCache = ConcurrentHashMap<String, NavigationResult>()

    // Replace this with the same application-scoped repository used by MainActivity.
    private val repository: NavigationRepository by lazy {
        NavigationRepositoryProvider.get(applicationContext)
    }

    private val binder = object : INavigationCommandService.Stub() {
        override fun getApiVersion(): Int = HyperNovaContract.API_VERSION

        override fun searchDestinations(
            requestId: String,
            query: String,
            callback: INavigationCommandCallback,
        ) {
            executeSearch(requestId, query, callback)
        }

        override fun getSavedDestinations(
            requestId: String,
            callback: INavigationCommandCallback,
        ) {
            executeSaved(requestId, callback)
        }

        override fun setDestination(
            requestId: String,
            destinationId: String,
            callback: INavigationCommandCallback,
        ) {
            executeSetDestination(requestId, destinationId, callback)
        }

        override fun cancelNavigation(
            requestId: String,
            callback: INavigationCommandCallback,
        ) {
            executeCancel(requestId, callback)
        }
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == NavigationContract.BIND_COMMAND_ACTION) binder else null

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
```

`NavigationRepositoryProvider` is a placeholder name in the template. Replace it with Ayman's real
application-scoped dependency provider.

Do not execute map, HTTP, database, profile, or routing work directly on a Binder thread. Each
operation must schedule work through `executor`, a coroutine dispatcher, or the repository's
asynchronous API.

## 8. Construct results

Map each internal search result:

```kotlin
private fun ResolvedDestination.toContract(): NavigationDestination =
    NavigationDestination(
        id,
        source,
        title,
        subtitle,
        category,
        distanceMeters ?: -1L,
    )
```

An accepted result:

```kotlin
private fun accepted(
    requestId: String,
    operation: String,
    message: String,
    state: Int,
): NavigationResult = NavigationResult(
    requestId,
    operation,
    HyperNovaContract.STATUS_ACCEPTED,
    message,
    HyperNovaContract.ERROR_NONE,
    emptyList(),
    null,
    state,
    -1L,
    -1L,
)
```

A confirmed search:

```kotlin
val destinations = repository.searchDestinations(query)
    .take(NavigationContract.MAX_DESTINATION_RESULTS)
    .map { it.toContract() }

val result = if (destinations.isEmpty()) {
    NavigationResult(
        requestId,
        NavigationContract.OP_SEARCH_DESTINATIONS,
        HyperNovaContract.STATUS_REJECTED,
        "No destinations found",
        NavigationContract.ERROR_NO_RESULTS,
        emptyList(),
        null,
        NavigationContract.STATE_IDLE,
        -1L,
        -1L,
    )
} else {
    NavigationResult(
        requestId,
        NavigationContract.OP_SEARCH_DESTINATIONS,
        HyperNovaContract.STATUS_CONFIRMED,
        "Destinations found",
        HyperNovaContract.ERROR_NONE,
        destinations,
        null,
        NavigationContract.STATE_IDLE,
        -1L,
        -1L,
    )
}

callback.onResult(result)
```

A confirmed active route:

```kotlin
val route = repository.startGuidance(destination)

val result = NavigationResult(
    requestId,
    NavigationContract.OP_SET_DESTINATION,
    HyperNovaContract.STATUS_CONFIRMED,
    "Route started to ${destination.title}",
    HyperNovaContract.ERROR_NONE,
    emptyList(),
    destination.toContract(),
    NavigationContract.STATE_ACTIVE,
    route.etaSeconds ?: -1L,
    route.distanceMeters ?: -1L,
)
```

The driver-facing message must be based on real returned data. Do not include provider exceptions,
tokens, raw coordinates, or network details.

## 9. Required operation behavior

### `searchDestinations`

1. Reject blank query with `STATUS_REJECTED/INVALID_ARGUMENT`.
2. Return cached result for duplicate `requestId`.
3. Optionally emit accepted.
4. Search the real provider.
5. Preserve provider ranking.
6. Return no more than four results.
7. Finish within ten seconds or return `STATUS_TIMEOUT/TIMEOUT`.
8. Cache the final result for at least ten minutes.

### `getSavedDestinations`

1. Return cached result for duplicate `requestId`.
2. Read real Home and Work from Driver Profile.
3. Append recent real favorites until the four-result limit.
4. If empty, return `STATUS_REJECTED/NO_SAVED_DESTINATIONS`.
5. Cache the final result.

### `setDestination`

1. Return cached result for duplicate `requestId`.
2. Reject blank ID with `INVALID_ARGUMENT`.
3. Resolve only an ID previously issued by Navigation.
4. Return `DESTINATION_EXPIRED` for an expired/unknown search ID.
5. Emit accepted with state `CALCULATING`.
6. Calculate and activate the route.
7. Return confirmed only when repository state is `ACTIVE`.
8. Include real destination, ETA, and distance.
9. Finish within twenty seconds or return timeout.

### `cancelNavigation`

1. Deduplicate the request.
2. Stop active guidance through the repository.
3. If already idle, return confirmed/idle because the requested final state is already true.
4. Never claim cancellation while guidance remains active.

## 10. Register the service

In `app/src/main/AndroidManifest.xml`, declare the service inside `<application>`:

```xml
<service
    android:name=".service.NavigationCommandService"
    android:exported="true"
    android:permission="com.hypernova.permission.CONTROL_COCKPIT_APPS">
    <intent-filter>
        <action android:name="com.hypernova.navigation.action.BIND_COMMAND" />
    </intent-filter>
</service>
```

Also add `android:launchMode="singleTask"` to Navigation's exported `MainActivity` if it is still
missing.

Do not declare `CATEGORY_HOME`.

## 11. Security and signing

- The command service is exported only behind the frozen signature permission.
- Do not add an unprotected development service.
- NOVA and Navigation must be signed with the same approved integration key.
- Default debug keys from different developer laptops are different. For cross-laptop testing,
  produce all APKs on the integration laptop or use the team-approved debug signing setup.
- Never commit a private signing key.

## 12. Tests Ayman must deliver

Unit tests:

- blank search;
- 0, 1, and more than 4 provider results;
- provider order preserved;
- saved ordering with missing Home or Work;
- expired destination ID;
- duplicate search request;
- duplicate route request;
- route confirmed only after active state;
- cancellation while active and while idle;
- ten-second search timeout;
- twenty-second route timeout.

Integration tests:

- service resolves through `com.hypernova.navigation.action.BIND_COMMAND`;
- untrusted/differently signed APK cannot bind;
- search callback returns real results;
- a returned ID starts its matching route;
- service death/rebind creates no false success;
- Navigation UI shows the same active route returned to NOVA.

## 13. Delivery to the NOVA team

Provide:

- source commit/branch;
- debug APK built with the agreed integration key;
- one real search query expected to produce results;
- one real search query expected to produce none;
- configured real saved destinations for the demo;
- test report for success, error, duplicate, timeout, and rebind;
- any provider/network prerequisites.

Done means NOVA can search, present up to four choices, select a returned ID, open Navigation, and
receive a confirmed active route with real ETA/distance.
