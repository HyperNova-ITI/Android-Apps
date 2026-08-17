# HyperNova Navigation AIDL Test Client Guide

## Purpose

This guide explains how to build, install, launch, and use the temporary **Navigation Contract Test Client** inside the HyperNova Navigation project.

The test client validates the Navigation-side AIDL integration without requiring the NOVA AI application or the external AI Node.

```text
Navigation Contract Test Client
        |
        | AIDL / Binder
        v
NavigationCommandService
        |
        v
NavigationCommandController
        |
        v
NavigationRepository
        |
        +--> Nominatim
        +--> DestinationStore
        +--> OSRM
        |
        v
NavigationSession
        |
        v
Navigation UI
```

## Project locations

```text
Navigation project:
~/ITI/Android-Apps/HyperNova_Navigation_Task_03/HyperNovaNavigation

Test client module:
contract-test-client/

Navigation APK:
app/build/outputs/apk/debug/app-debug.apk

Test client APK:
contract-test-client/build/outputs/apk/debug/contract-test-client-debug.apk
```

Packages:

```text
Navigation:
com.hypernova.navigation

Test client:
com.hypernova.navigation.contracttest
```

## What this client validates

```text
AIDL contract loading
Binder service binding
Signature permission
getApiVersion()
searchDestinations()
getSavedDestinations()
setDestination()
cancelNavigation()
NavigationResult callbacks
Opaque destination IDs
OSRM route activation
Shared NavigationSession
UI reflecting AIDL-started routes
```

It does not test AI/NLP, NOVA conversational context, speech recognition, or the external AI Node.

## 1. Build

```bash
cd ~/ITI/Android-Apps/HyperNova_Navigation_Task_03/HyperNovaNavigation

./gradlew \
  :hypernova-contracts:assembleDebug \
  :app:testDebugUnitTest \
  :app:assembleDebug \
  :contract-test-client:assembleDebug
```

Expected:

```text
BUILD SUCCESSFUL
```

## 2. Install Navigation

```bash
adb install -r \
app/build/outputs/apk/debug/app-debug.apk
```

Expected:

```text
Success
```

## 3. Install the test client

```bash
adb install -r \
contract-test-client/build/outputs/apk/debug/contract-test-client-debug.apk
```

Expected:

```text
Success
```

## 4. Verify the AIDL service

```bash
adb shell dumpsys package com.hypernova.navigation | \
grep -n -A15 -B5 'NavigationCommandService'
```

Expected service:

```text
com.hypernova.navigation/.service.NavigationCommandService
```

Expected bind action:

```text
com.hypernova.navigation.action.BIND_COMMAND
```

Expected permission:

```text
com.hypernova.permission.CONTROL_COCKPIT_APPS
```

## 5. Verify the test client permission

```bash
adb shell dumpsys package com.hypernova.navigation.contracttest | \
grep -n -A12 -B5 'CONTROL_COCKPIT_APPS'
```

Expected:

```text
com.hypernova.permission.CONTROL_COCKPIT_APPS: granted=true
```

For local debug testing, the Navigation APK and test client APK should be signed compatibly so the signature permission is granted.

## 6. Launch the client

Normal launch:

```bash
adb shell am start \
  --user 10 \
  -n com.hypernova.navigation.contracttest/.TestClientActivity
```

If Android keeps Navigation on screen and reuses the existing task, launch the client in a separate task:

```bash
adb shell am start \
  --user 10 \
  -f 0x18000000 \
  -n com.hypernova.navigation.contracttest/.TestClientActivity
```

`0x18000000` combines:

```text
FLAG_ACTIVITY_NEW_TASK
+
FLAG_ACTIVITY_MULTIPLE_TASK
```

## 7. Expected initial state

After a successful bind:

```text
CONNECTED
API VERSION: 1 - PASS
```

This validates the Binder path:

```text
Test Client
    |
    | INavigationCommandService.Proxy
    v
Binder IPC
    |
    v
INavigationCommandService.Stub
    |
    v
NavigationCommandService
```

## 8. Test API version

Press:

```text
2. GET API VERSION
```

Expected:

```text
API VERSION: 1 - PASS
```

## 9. Test search

Enter a real query such as:

```text
Valeo
```

Press:

```text
3. SEARCH DESTINATIONS
```

Expected final callback:

```text
search_destinations / CONFIRMED / IDLE
```

`IDLE` is correct because searching alone does not start navigation.

The returned results should contain opaque Navigation-issued IDs such as:

```text
nav-...
```

The test client stores the first returned ID for routing.

## 10. Test saved destinations

Press:

```text
4. GET SAVED DESTINATIONS
```

Expected behavior:

```text
Home if configured
Work if configured
Real recent/favorite destinations
Maximum 4 total
```

Missing saved places must not be fabricated.

## 11. Test route start

After a successful search, press:

```text
5. NAVIGATE TO FIRST SEARCH RESULT
```

Expected flow:

```text
Opaque destination ID
        |
        v
DestinationStore
        |
        v
Resolved destination
        |
        v
NavigationRepository
        |
        v
OSRM
        |
        v
NavigationSession
```

Expected callback progression:

```text
set_destination / ACCEPTED / CALCULATING
```

then:

```text
set_destination / CONFIRMED / ACTIVE
```

The final result should contain real route data where available:

```text
selectedDestination
etaSeconds
distanceMeters
STATE_ACTIVE
```

## 12. Verify the same route in Navigation UI

Press:

```text
7. OPEN NAVIGATION UI
```

The Navigation UI should show the same route started through AIDL.

This validates the shared backend:

```text
MainActivity -------------------+
                               |
                               v
                    Shared NavigationRepository
                               ^
                               |
NavigationCommandService -------+
```

A successful runtime test already demonstrated a real active route rendered in the UI.

## 13. Return to the test client

If Navigation remains on screen:

```bash
adb shell am start \
  --user 10 \
  -f 0x18000000 \
  -n com.hypernova.navigation.contracttest/.TestClientActivity
```

## 14. Test cancellation

Press:

```text
6. CANCEL NAVIGATION
```

Expected:

```text
cancel_navigation / CONFIRMED / IDLE
```

Then open Navigation again. Expected UI state:

```text
No active route
```

## 15. Useful diagnostics

Current AAOS user:

```bash
adb shell am get-current-user
```

Expected development user:

```text
10
```

Check active service:

```bash
adb shell dumpsys activity services com.hypernova.navigation
```

A healthy bind can show:

```text
requested=true
received=true
hasBound=true
```

Check focused app:

```bash
adb shell dumpsys window | \
grep -E 'mCurrentFocus|mFocusedApp'
```

Check processes:

```bash
adb shell ps -A | grep com.hypernova.navigation
```

Check logs:

```bash
adb logcat | grep -E \
'NavContractTest|NavigationCommandService|AndroidRuntime|FATAL EXCEPTION'
```

## End-to-end success criteria

```text
Bind
  |
  v
API Version 1
  |
  v
Search real destination
  |
  v
Receive opaque destination ID
  |
  v
setDestination(destinationId)
  |
  v
OSRM route
  |
  v
STATE_ACTIVE
  |
  v
Navigation UI shows same route
  |
  v
cancelNavigation()
  |
  v
STATE_IDLE
```

The NOVA AI application and AI Node are not required for this test.
