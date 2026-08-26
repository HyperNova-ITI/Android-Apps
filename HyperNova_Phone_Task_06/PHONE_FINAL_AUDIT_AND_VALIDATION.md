# HyperNova Phone — Final Audit and Validation

Audit date: 2026-08-27  
Target: NXP Standard Android automotive target  
Validation mode: software-only

## 1. Executive Summary

**CONFIRMED — baseline:** The Phone project at commit `50b51bfd` already had the correct fundamental production architecture: real Android Telecom calls, a standalone translucent incoming-call activity, provider-backed contacts and recents, robust phone-number matching, real call controls, and the signature-protected NOVA Phone Binder contract. There were no Phone changes between `50b51bfd` and the repository HEAD at the start of this audit, even though the repository had newer commits for other HyperNova components.

**FIXED — gaps found:** The baseline still had several real edge-case defects:

- an outgoing call initiated outside an already-visible Phone activity did not open the full call UI until `ACTIVE`;
- `InCallService.onBringToForeground()` could open the full Phone UI while an incoming call was still ringing;
- repeated Telecom emissions could repeatedly request incoming/full-screen activities;
- caller provider queries ran synchronously from Telecom/UI main-thread paths and a duplicate positive cache could stay stale during PBAP updates;
- the compact incoming bar did not independently re-resolve its visible identity during provider batches;
- an ended call retained identity/timer fields, allowing a later call to inherit the prior active timer;
- provider observer registration was all-or-nothing and did not recover reliably after permissions were granted;
- `READ_PHONE_STATE` and `POST_NOTIFICATIONS` were declared but not included in the user recovery request;
- Telecom number/name privacy needed separate handle and caller-display-name presentation gates, while restricted CallLog rows could retain identity;
- `POST_NOTIFICATIONS` incorrectly participated in core Bluetooth/HFP readiness instead of only the HUN fallback;
- the Contacts screen's search surface was a non-interactive placeholder;
- runtime tooling could miscount provider error text as contact rows and did not count CallLog rows.

**FIXED — implementation:** The minimum coherent changes now gate incoming versus full call UI from real Telecom state, de-duplicate activity launches, resolve caller identity asynchronously through the shared provider repository, refresh the compact bar during Contacts/CallLog changes, reset terminal call state, recover observer/permission state, apply separate Telecom number/name presentation gates, keep notification permission out of core readiness, protect restricted CallLog data, add real ContactsProvider-backed UI search, and strengthen tests and runtime diagnostics.

**CONFIRMED — software status:** The required unit-test/build command succeeds, 47 unit tests pass with zero failures, the NOVA contract client assembles, Android lint completes successfully, shell tooling parses, `git diff --check` passes, and the merged debug manifest contains the required activity/service declarations.

**NOT TESTED — runtime:** **NOT RUNTIME TESTED — NXP unavailable.** No target was available and, per validation scope, ADB was not used for runtime acceptance. Real Bluetooth HFP calls, PBAP population, provider row counts, system-dialer selection, HFP `PhoneAccount`, SCO audio, window placement, and Answer/Decline/End behavior still require an NXP session.

**PENDING — overall status:** The Phone source is **SOFTWARE VERIFIED** and is ready to install for NXP acceptance testing. End-to-end demo readiness is pending runtime verification only.

## 2. Repository Baseline

| Item | Value |
|---|---|
| Repository | `/home/ayman/ITI/Android-Apps` |
| Phone project | `/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06` |
| Branch | `main` |
| HEAD before work | `bde9687b edit panic` |
| HEAD after work | `bde9687b edit panic` |
| Commit created | None; changes intentionally left uncommitted for review |
| Phone changes after `50b51bfd` at baseline | None (`git diff --stat 50b51bfd..HEAD -- HyperNova_Phone_Task_06` was empty) |

Important Phone commits reviewed:

| Commit | Subject |
|---|---|
| `67461f0c` | `fix(phone-contacts): robust number matching and deterministic caller identity fallbacks` |
| `4ba2b4e9` | `fix(telecom): enforce state/capability gating and HFP SCO lifecycle` |
| `bd1f57ad` | `fix(phone-ui): derive call screen strictly from live Telecom state` |
| `031665e3` | `Use resolveCallerIdentity for call identity and fix phone setup helper` |
| `6e39189a` | `feat(phone-ui): show real ContactsProvider photo only when it decodes` |
| `82be240c` | `Move tools/nxp_phone_runtime_setup.sh to HyperNova_Phone_Task_06/tools/nxp_phone_runtime_setup.sh` |
| `50b51bfd` | `fix(phone): show incoming call bar over current app` |

Git status before work:

```text
(clean)
```

Git status after work is recorded in sections 17 and 18. HEAD is unchanged because no commit was created.

## 3. Required Behavior Checklist

The evidence below is software evidence unless explicitly identified as runtime evidence.

| Requirement | Before | After | Evidence |
|---|---|---|---|
| Incoming compact call bar | **CONFIRMED**, with duplicate-request edge case | **FIXED / SOFTWARE VERIFIED** | `HyperNovaInCallService` requests `IncomingCallActivity` only for `INCOMING`; `TelecomCallPolicyTest` proves incoming is compact and not full-screen |
| Background app preserved | **CONFIRMED**, except unconditional foreground callback risk | **FIXED / SOFTWARE VERIFIED** | Standalone empty affinity, floating translucent/no-dim theme; incoming `onBringToForeground()` is redirected to compact UI; no incoming branch starts `MainActivity` |
| Incoming caller name | **PARTIAL** — robust lookup existed but synchronous duplicate resolver/cache was stale-prone | **FIXED / SOFTWARE VERIFIED** | Telecom meaningful name, async `ContactsRepository` PhoneLookup/bounded scan, CallLog fallback, raw number; compact activity observes providers |
| Answer | **CONFIRMED** | **CONFIRMED / SOFTWARE VERIFIED** | Compact button calls `TelecomCallController.answer()`; state/direction gate precedes real `Call.answer()` |
| Decline | **CONFIRMED** | **CONFIRMED / SOFTWARE VERIFIED** | Compact button calls `TelecomCallController.decline()`; gate precedes real `Call.reject()` |
| Outgoing full-screen call UI | **PARTIAL** — UI-originated call worked, external/NOVA call waited until `ACTIVE` | **FIXED / SOFTWARE VERIFIED** | Real outgoing `DIALING`/`RINGING` callback requests `MainActivity`; no screen change occurs in `placeCall()` |
| Outgoing caller/contact identity | **CONFIRMED**, with main-thread resolver defect | **FIXED / SOFTWARE VERIFIED** | Telecom name is retained; `PhoneRepository` asynchronously enriches by the matching real number and rejects stale lookup results |
| ACTIVE call screen | **CONFIRMED** | **CONFIRMED / SOFTWARE VERIFIED** | `PhoneViewModel` maps only live Telecom statuses to `PhoneScreen.CALL`; answered incoming opens full UI only after `ACTIVE` |
| End call | **CONFIRMED** | **CONFIRMED / SOFTWARE VERIFIED** | UI/Binder dispatch real `Call.disconnect()` after live-call gate |
| Mute | **CONFIRMED** | **CONFIRMED / SOFTWARE VERIFIED** | `InCallService.setMuted()` is used and UI state changes only on real `CallAudioState` callback |
| Speaker/audio | **CONFIRMED** | **CONFIRMED / SOFTWARE VERIFIED** | Supported real route mask/endpoints gate selection; confirmed audio state is published back |
| Hold | **CONFIRMED** | **CONFIRMED / SOFTWARE VERIFIED** | `CAPABILITY_HOLD` or `CAPABILITY_SUPPORT_HOLD` controls visibility; real `Call.hold()`/`unhold()` |
| DTMF | **CONFIRMED** | **CONFIRMED / SOFTWARE VERIFIED** | Valid digits and ACTIVE/HELD state gate real `playDtmfTone()`/`stopDtmfTone()` |
| Contacts list | **CONFIRMED** | **IMPROVED / SOFTWARE VERIFIED** | Real `ContactsContract.CommonDataKinds.Phone` rows only; preferred number is deterministic |
| Contact names | **CONFIRMED** | **CONFIRMED / SOFTWARE VERIFIED** | Provider `DISPLAY_NAME`; number only when provider name is blank; no fabricated name |
| Contacts search | **MISSING in screen**; NOVA Binder search was real | **FIXED / SOFTWARE VERIFIED** | Interactive screen search filters loaded provider rows by real name/formatted number; Binder search remains provider-backed |
| Recents | **CONFIRMED** | **CONFIRMED / SOFTWARE VERIFIED** | One bounded real `CallLog.Calls` query; exact incoming/outgoing/missed/rejected contract type retained |
| Recent caller names | **CONFIRMED** | **IMPROVED / SOFTWARE VERIFIED** | ContactsProvider positive match → CallLog cached name → raw number; restricted presentations are not exposed |
| PBAP live refresh | **PARTIAL** | **FIXED / SOFTWARE VERIFIED** | Debounced Contacts observer reloads contacts/recents, clears identity cache, and re-resolves current caller; forced loads replace older work |
| Provider observers | **PARTIAL** — all-or-nothing registration | **FIXED / SOFTWARE VERIFIED** | Contacts and CallLog observers register/recover/clean up independently according to permission |
| NOVA Phone contract | **CONFIRMED** | **PRESERVED / SOFTWARE VERIFIED** | Existing signature permission/AIDL contract retained; client assembles; shared robust number matcher now used |

## 4. Architecture

Incoming call path:

```text
Connected real handset
    -> Bluetooth HFP Client
    -> HfpClientConnectionService PhoneAccount
    -> Android Telecom
    -> HyperNovaInCallService.onCallAdded(Call)
    -> TelecomCallController publishes real INCOMING
    -> HyperNovaInCallService de-duplicates presentation
    -> IncomingCallActivity (empty task affinity, translucent/floating, top)
       -> current cockpit app remains the underlying window
       -> Answer -> real Call.answer()
       -> Decline -> real Call.reject()

Answer path:
INCOMING -> user Answer -> Telecom callback ACTIVE
    -> compact activity finishes
    -> service opens MainActivity once
    -> PhoneViewModel derives PhoneScreen.CALL
```

Outgoing call path:

```text
Keypad / Contact / Recent / NOVA confirmed command
    -> TelecomCallController.placeCall(real number)
    -> TelecomManager.placeCall(tel:...)
    -> no optimistic call-state mutation
    -> Android Telecom callback DIALING or outgoing RINGING
    -> controller publishes real status
    -> service opens MainActivity once
    -> PhoneViewModel derives PhoneScreen.CALL
    -> Telecom ACTIVE starts timer
    -> End/Mute/Audio/Hold/DTMF dispatch to real Telecom/InCallService
```

Contacts/PBAP path:

```text
Handset contact-sharing permission
    -> Android Bluetooth PBAP/platform synchronization
    -> Android ContactsProvider Phone rows
    -> Contacts ContentObserver (450 ms debounce)
    -> ContactsRepository bounded provider query
    -> PhoneRepository contacts StateFlow
    -> Contacts screen + real in-memory name/number search
    -> identity cache invalidation + active caller re-resolution
```

Recents/CallLog path:

```text
Android Telecom/Bluetooth platform call lifecycle
    -> Android CallLog provider rows
    -> CallLog ContentObserver (450 ms debounce)
    -> CallHistoryRepository bounded newest-80 query
    -> exact provider call type/presentation
    -> ContactsProvider identity resolution
    -> PhoneRepository recents StateFlow / NOVA Binder
```

Caller identity resolution path:

```text
Telecom meaningful display name (presentation allowed)
    -> otherwise ContactsProvider PhoneLookup
    -> otherwise bounded ContactsProvider phone-row scan
    -> otherwise matching CallLog cached name
    -> otherwise real raw number
    -> otherwise unknown/private presentation label
```

## 5. Full Code Audit

| File/subsystem | Purpose | Reviewed | Problem found | Change or no-change justification |
|---|---|---|---|---|
| `app/src/main/AndroidManifest.xml` | Permissions/components | All permissions, activities, services, receiver, metadata | No manifest defect | No change. Merged manifest proves `IN_CALL_SERVICE_UI=true`, empty affinity, `noHistory=true`, signature/service permissions |
| `app/src/main/res/values/hypernova_incoming_bar.xml` | Incoming window theme | Translucency, floating window, title, background/dim | No defect | No change; it is transparent, floating, titleless, and has dim disabled |
| `MainActivity.kt` | Full Phone shell, permission recovery | lifecycle, intents, navigation, role/permissions | Bluetooth setup requested only one of three associated runtime permissions | Requests `BLUETOOTH_CONNECT`, `READ_PHONE_STATE`, and `POST_NOTIFICATIONS` together; AAOS policy behavior preserved |
| `PhoneViewModel.kt` | Immutable UI state and call-screen derivation | call mapping, timer, keypad reset, actions | No optimistic state defect | No change; only real DIALING/RINGING/INCOMING/ACTIVE/HELD states select call screen |
| `PhoneScreenRenderer.kt` | Full visual UI and controls | incoming/full call states, identity, timer, controls, contacts, recents | Contacts search was a placeholder; permission copy was incomplete | Added real provider-row search and truthful phone-access copy; existing real call controls retained |
| `HyperNovaInCallService.kt` | Telecom call delivery/UI/audio bridge | add/remove/rebind, foreground request, state collector, audio | Duplicate UI requests; external outgoing UI late; incoming foreground request could open full app | Added de-duplication and explicit compact/full policy; DIALING/RINGING opens full UI, INCOMING never does |
| `IncomingCallActivity.kt` | Compact top incoming bar | window sizing, lifecycle, identity, buttons, transitions | Synchronous provider access, duplicate active-screen launch, no provider refresh | Async repository lookup, Contacts/CallLog observers, stale-result guard; service solely owns full-screen launch |
| `IncomingCallNotifier.kt` | Safe fallback notification | identity, actions, content intent, permission | Narrower lookup and fallback tap opened full Phone | Uses shared robust resolver; fallback tap returns to compact activity |
| `CallActionReceiver.kt` | Notification actions | action routing and state authority | No defect | No change; routes only to real controller commands and never mutates UI state |
| `TelecomCallController.kt` | Real call commands and callbacks | place/answer/reject/end/hold/DTMF, multi-call tracking, SCO, identity, timer | Main-thread provider resolver; terminal state retained stale identity/timer; presentation privacy gap | Provider work removed; timer/state fixed; number uses `handlePresentation`, name independently uses `callerDisplayNamePresentation` |
| `TelecomCallPolicy.kt` | Pure state/capability gates | mapping, answer/decline, hold, DTMF | Missing testable UI/timer/presentation policies | Added pure policies and tests |
| `CallAudioController.kt` | Binder audio facade | mute and route dispatch | No defect | No change; real current `InCallService` is authoritative |
| `CallerIdentityResolver.kt` | Duplicate synchronous identity path | complete implementation and cache behavior | Duplicated provider logic, queried synchronously, positive cache lacked PBAP invalidation | Deleted; all provider resolution now uses async `ContactsRepository` |
| `ContactsRepository.kt` | Contacts/details/identity provider access | list, real IDs/number rows, PhoneLookup, scan, CallLog fallback | Default list number for multi-number contact was provider-order dependent | Primary/super-primary/row ordering made deterministic; no fake rows introduced |
| `PhoneNumberMatching.kt` | Shared normalization/matching | exact, framework comparison, suffix fallback, cache key | Robust behavior was correct | Preserved; added explicit Egyptian local/international test |
| `CallerIdentity.kt` / `CallerIdentityFallbacks` | Deterministic real-data fallback | meaningful-name rejection and provider gates | No defect | Preserved unchanged |
| `CallHistoryRepository.kt` | Real CallLog and recent identity | row limit/types/presentation/cache/fallback | Non-allowed rows could retain raw/cached identity | Presentation gate now precedes lookup/display; positive-only contact cache behavior retained |
| `PhoneRepository.kt` | Aggregate providers/Bluetooth/Telecom | observers, debounce, capabilities, current identity, jobs | Observer registration all-or-nothing; stale rows on permission loss; forced PBAP refresh could lose to running load; notification permission gated core readiness | Independent observers, recovery registration, stale-row clear, forced-load cancellation; core readiness uses Bluetooth/phone-state permissions only |
| `BluetoothPhoneClient.kt` | HFP capability state | platform bridge, NXP PhoneAccount fallback, generic Bluetooth, permissions | Missing `READ_PHONE_STATE` fell through to vague paired-device state | Truthfully reports that phone-state access is needed to verify HFP; generic Bluetooth still never equals HFP |
| `PhoneCommandService.kt` | NOVA Binder contract | signature binding, request gates, search/details/history/calls/audio/state callbacks | Contact/history-to-call matching used a weaker local comparator | Uses shared robust matcher; contract and confirmation semantics unchanged |
| `hypernova-contracts` / AIDL | Public NOVA Phone API | version, DTOs, callbacks, permission contract | No defect | No API/contract modification required |
| Unit tests | Logic verification | all existing tests and gaps | Timer, compact/full policy, privacy, Egyptian format, UI search were uncovered | Added focused pure tests; 47 tests now pass |
| `tools/nxp_phone_runtime_setup.sh` | Later NXP diagnostics | permissions, dialer, HFP, PBAP/provider checks | Contact count could count error text; no CallLog count | Counts only `Row:` output and adds privacy-safe CallLog count |
| `tools/run_phone_contract_test.sh` | Later Binder runtime test | grants and launch flow | Missing `READ_PHONE_STATE` grant attempt | Added it; script was parsed only, not run |
| Gradle/docs | Build and handoff context | project graph, SDK, dependencies, prior docs | Architecture/permissions docs were stale | Updated factual docs; no dependency/API churn |

## 6. Changes Made

Modified production files and exact behavior:

- `app/src/main/java/com/hypernova/phone/MainActivity.kt` — recovers all Bluetooth/HFP/notification runtime permissions as one phone-access request.
- `app/src/main/java/com/hypernova/phone/bluetooth/BluetoothPhoneClient.kt` — distinguishes missing phone-state permission from an HFP-ready device.
- `app/src/main/java/com/hypernova/phone/contacts/CallHistoryRepository.kt` — respects number presentation before identity lookup or display.
- `app/src/main/java/com/hypernova/phone/contacts/ContactsRepository.kt` — deterministically chooses the preferred provider phone row.
- `app/src/main/java/com/hypernova/phone/data/PhoneRepository.kt` — independently manages observers, clears revoked data, replaces stale PBAP loads, and excludes notification permission from core readiness.
- `app/src/main/java/com/hypernova/phone/service/PhoneCommandService.kt` — uses robust shared number matching without changing the Binder contract.
- `app/src/main/java/com/hypernova/phone/telecom/CallerIdentityResolver.kt` — removed duplicate synchronous/stale resolver.
- `app/src/main/java/com/hypernova/phone/telecom/HyperNovaInCallService.kt` — de-duplicates compact/full UI and opens outgoing/full UI only from appropriate real states.
- `app/src/main/java/com/hypernova/phone/telecom/IncomingCallActivity.kt` — asynchronous live identity and single-owner transition to active UI.
- `app/src/main/java/com/hypernova/phone/telecom/IncomingCallNotifier.kt` — shared robust identity and compact fallback intent.
- `app/src/main/java/com/hypernova/phone/telecom/TelecomCallController.kt` — clears completed-call state, fixes timer, removes main-thread provider work, protects restricted identity.
- `app/src/main/java/com/hypernova/phone/telecom/TelecomCallPolicy.kt` — adds pure timer/UI/presentation policies.
- `app/src/main/java/com/hypernova/phone/ui/PhoneScreenRenderer.kt` — real contact name/number search and accurate access copy.

Tests/docs/tools are listed in sections 14 and 17.

Tracked `git diff --stat` snapshot (new untracked tests and this report are listed separately because plain `git diff --stat` does not include untracked files):

```text
 .../main/java/com/hypernova/phone/MainActivity.kt  |  44 +-
 .../phone/bluetooth/BluetoothPhoneClient.kt        |  16 +
 .../phone/contacts/CallHistoryRepository.kt        |  55 ++-
 .../hypernova/phone/contacts/ContactsRepository.kt |  11 +-
 .../com/hypernova/phone/data/PhoneRepository.kt    | 229 +++++-----
 .../hypernova/phone/service/PhoneCommandService.kt |  26 +-
 .../phone/telecom/CallerIdentityResolver.kt        | 487 ---------------------
 .../phone/telecom/HyperNovaInCallService.kt        | 153 +++++--
 .../phone/telecom/IncomingCallActivity.kt          | 376 +++++++++++++---
 .../phone/telecom/IncomingCallNotifier.kt          |  88 ++--
 .../phone/telecom/TelecomCallController.kt         |  94 ++--
 .../hypernova/phone/telecom/TelecomCallPolicy.kt   |  60 +++
 .../com/hypernova/phone/ui/PhoneScreenRenderer.kt  | 252 +++++++----
 .../phone/contacts/PhoneNumberMatchingTest.kt      |  10 +
 .../phone/telecom/TelecomCallPolicyTest.kt         |  91 ++++
 HyperNova_Phone_Task_06/docs/ARCHITECTURE.md       |  10 +-
 HyperNova_Phone_Task_06/docs/PERMISSIONS.md        |   7 +-
 .../tools/nxp_phone_runtime_setup.sh               |  20 +-
 .../tools/run_phone_contract_test.sh               |   1 +
 19 files changed, 1089 insertions(+), 941 deletions(-)
```

## 7. Incoming Call Validation

**SOFTWARE VERIFIED:** A real Telecom incoming direction maps ringing states to `CallStatus.INCOMING`. The service's incoming branch cancels any fallback notification, requests only `IncomingCallActivity`, and uses an `incomingBarRequested` guard. `onBringToForeground()` independently checks the current real state and keeps `INCOMING` on the compact activity instead of launching `MainActivity`.

**SOFTWARE VERIFIED:** The manifest and merged manifest declare `IncomingCallActivity` as non-exported, excluded from recents, `singleTop`, `noHistory=true`, and `taskAffinity=""`. Its theme is floating/translucent/transparent with dim disabled. Runtime layout attributes place a wrap-content, full-width bar at the top. This is the code-level mechanism that preserves the underlying cockpit app.

**SOFTWARE VERIFIED:** Caller presentation uses an allowed meaningful Telecom name first. Otherwise, a coroutine invokes `ContactsRepository.resolveCallerIdentity()` off the main thread. Contacts and CallLog observers debounce for 450 ms and force re-resolution during a PBAP batch. A lookup result is applied only if the current real call is still incoming and its number still matches.

**SOFTWARE VERIFIED:** Answer and Decline call `TelecomCallController`, which verifies a real incoming ringing state and direction before invoking `Call.answer()` or `Call.reject()`. The bar finishes only after Telecom publishes ACTIVE/terminal/no-call state. The service is the single owner that opens full UI after ACTIVE.

**NOT RUNTIME TESTED — NXP unavailable:** Actual top-window placement, the underlying Launcher/Navigation/Climate/Media window remaining visible, real caller name, and physical Answer/Decline behavior were not observed. There are no runtime log lines to report.

## 8. Outgoing Call Validation

**SOFTWARE VERIFIED:** All UI call paths and confirmed NOVA call paths delegate to `TelecomCallController.placeCall()`, which invokes `TelecomManager.placeCall()` with the real `tel:` URI. `placeCall()` returns dispatched/rejected status but does not write a fake call state.

**SOFTWARE VERIFIED:** Only `TelecomCallController` callbacks publish DIALING, outgoing RINGING, ACTIVE, HELD, or terminal state. `HyperNovaInCallService` requests `MainActivity` on real DIALING/RINGING, including an outgoing call initiated by NOVA while another app is visible. `PhoneViewModel` derives `PhoneScreen.CALL` from those real states. ACTIVE starts a fresh timer only on the real transition; HELD/ACTIVE resume preserves that call's original timestamp.

**SOFTWARE VERIFIED:** End, Mute, audio route, Hold/Resume, and DTMF remain gated by actual call state/capabilities and dispatch to real Telecom/InCallService APIs. State is confirmed through Telecom/audio callbacks rather than optimistic UI writes.

**NOT RUNTIME TESTED — NXP unavailable:** DIALING/ringback/ACTIVE/disconnect behavior against the NXP HFP `PhoneAccount`, remote handset, and SCO path was not observed. There are no runtime log lines to report.

## 9. Contacts / PBAP Validation

| Check | Result |
|---|---|
| `READ_CONTACTS` declaration | **CONFIRMED** in source and merged manifest |
| `READ_CONTACTS` runtime grant | **NOT RUNTIME TESTED — NXP unavailable** |
| ContactsProvider row count | **NOT RUNTIME TESTED — NXP unavailable** |
| Bluetooth PBAP evidence | **NOT RUNTIME TESTED — NXP unavailable** |
| Pairing “share contacts” state | **NOT RUNTIME TESTED — NXP unavailable** |
| Names present in target provider | **NOT RUNTIME TESTED — NXP unavailable** |
| App provider query behavior | **SOFTWARE VERIFIED** |

The application queries only `ContactsContract.CommonDataKinds.Phone`, `PhoneLookup`, and real contact/phone row IDs. The list uses `CONTACT_ID`, provider `DISPLAY_NAME`, provider number/type/starred state, and deterministic super-primary/primary ordering. Contact details query the selected real `CONTACT_ID` and return each real Phone `_ID` as the number-row ID. Nothing synthesizes a contact, name, number, photo, or provider ID.

Caller matching first uses `PhoneLookup`; if a vendor/PBAP formatting difference prevents that result, a scan is bounded to 5,000 phone rows and uses the shared robust matcher. UI search filters only the loaded real provider rows by display name or formatted phone digits. NOVA search similarly works from freshly loaded real provider rows and returns real IDs.

Contacts changes are observed recursively on `ContactsContract.AUTHORITY_URI`. Events are debounced for 450 ms, then contacts and recents are reloaded, the recents contact-name cache is cleared, and current-call identity is retried. A forced contacts load cancels an older in-flight snapshot so an earlier PBAP batch cannot overwrite a later provider notification. The compact incoming activity also observes Contacts/CallLog while visible.

Required diagnosis rule for later NXP testing:

- If ContactsProvider contains a matching saved name but HyperNova displays only a number, classify it as a **CODE ISSUE** and capture the sanitized provider row plus `HN-*` logs.
- If ContactsProvider contains only numbers/no names, classify it as a **PLATFORM / PBAP ISSUE**; HyperNova must not invent names.
- If ContactsProvider contains zero phone rows, classify it as a **PBAP / pairing synchronization issue** and verify handset contact-sharing permission, PBAP state/account, Android user, and provider population.

No platform conclusion is claimed now because the target provider was unavailable.

## 10. Recents / CallLog Validation

| Check | Result |
|---|---|
| `READ_CALL_LOG` declaration | **CONFIRMED** in source and merged manifest |
| `READ_CALL_LOG` runtime grant | **NOT RUNTIME TESTED — NXP unavailable** |
| CallLog row count | **NOT RUNTIME TESTED — NXP unavailable** |
| Real provider-only source | **SOFTWARE VERIFIED** |
| Incoming/outgoing/missed/rejected mapping | **SOFTWARE VERIFIED** by code review and existing mapping tests |
| Contact-name resolution | **SOFTWARE VERIFIED** |
| Presentation privacy | **SOFTWARE VERIFIED** by focused tests |

`CallHistoryRepository` performs one newest-first query limited to 80 real `CallLog.Calls` rows. It retains exact contract semantics for incoming, outgoing, missed, rejected, and other rows; the UI intentionally groups rejected visually with missed without losing the exact Binder value.

For each presentation-allowed usable number, identity is ContactsProvider name, then the row's real CallLog cached name, then the raw number. Non-allowed presentation rows do not submit retained numeric values for contact lookup and do not expose raw/cached identity. The Contacts resolver cache stores only successful names; misses are never negative-cached, so a later PBAP batch can resolve. Contacts observer events explicitly clear successful cache entries before refreshing.

CallLog observer events are independently registered and debounced, then force a recents refresh. Permission loss produces permission-required state and clears displayed rows rather than showing fake or stale content.

## 11. Caller Identity Resolution

Final priority:

```text
Android Telecom meaningful display name, when caller-display-name presentation is allowed
    -> ContactsProvider PhoneLookup
    -> bounded ContactsProvider phone-row scan with robust matching
    -> matching CallLog cached name, when permitted
    -> raw real number
```

“Meaningful” rejects blank text, the same number in another format, and number-like labels. The number is gated by `handlePresentation`; the Telecom display name is independently gated by `callerDisplayNamePresentation`. Provider photo URIs are used only with a real ContactsProvider contact name and the renderer accepts only `content://com.android.contacts/...` URIs.

Number normalization trims input, strips visual formatting for cache keys, preserves an initial `+`, and uses Android `PhoneNumberUtils.compare()` when available. The deterministic fallback compares the last nine digits only when both numbers have at least seven digits. This supports examples such as local Egyptian `010...` versus international `+2010...`, as well as spaces, parentheses, hyphens, dots, and common country/trunk prefixes. Short emergency/service numbers require an exact normalized key and are not suffix-matched.

Cross-call leakage is prevented in three places: completed-call publication creates a clean `TelecomCallState`; the repository clears resolved identity on terminal/no-call state; and asynchronous results are applied only when their normalized number still matches the current live call. New ACTIVE transitions cannot inherit a prior call timer.

## 12. Runtime Permissions

| Permission | Required | Runtime State | Effect if Missing |
|---|---|---|---|
| `android.permission.BLUETOOTH_CONNECT` | Yes, Android 12+ Bluetooth paired/device state | **NOT TESTED** | Phone access is permission-required; paired devices/HFP cannot be truthfully inspected |
| `android.permission.READ_PHONE_STATE` | Yes on NXP fallback to inspect call-capable HFP `PhoneAccount` | **NOT TESTED** | HFP readiness is not inferred; UI says phone-state access is required |
| `android.permission.CALL_PHONE` | Yes for outgoing call request | **NOT TESTED** | Call action is rejected with a truthful permission-required state |
| `android.permission.READ_CONTACTS` | Yes for contacts/caller names | **NOT TESTED** | Contact UI is permission-required and rows are cleared; no names are fabricated |
| `android.permission.READ_CALL_LOG` | Yes for recents/cached fallback | **NOT TESTED** | Recents are permission-required and rows are cleared; no history is fabricated |
| `android.permission.POST_NOTIFICATIONS` | Yes on Android 13+ for safe notification fallback | **NOT TESTED** | Compact activity remains primary; notification fallback truthfully cannot post |

All six permissions are present in the merged manifest. Standard Android can request missing permissions through Activity Result APIs. Automotive builds deliberately avoid driver-facing platform permission dialogs and expect product policy provisioning; capability refresh still exposes the missing state.

`POST_NOTIFICATIONS` does not gate core Bluetooth/HFP/Phone readiness. It affects only the notification/HUN fallback; the compact `IncomingCallActivity` remains the primary incoming UI.

## 13. Telecom / HFP Runtime State

| Runtime item | Result | Software evidence |
|---|---|---|
| System dialer | **NOT RUNTIME TESTED — NXP unavailable** | Expected exact component: `com.hypernova.phone/com.hypernova.phone.telecom.HyperNovaInCallService` |
| HyperNova InCallService | **SOFTWARE VERIFIED; NOT RUNTIME TESTED** | Merged manifest: exported, `BIND_INCALL_SERVICE`, `android.telecom.IN_CALL_SERVICE_UI=true` |
| HFP PhoneAccount | **NOT RUNTIME TESTED — NXP unavailable** | Code accepts only account component `com.android.bluetooth/com.android.bluetooth.hfpclient.HfpClientConnectionService` |
| Bluetooth device state | **NOT RUNTIME TESTED — NXP unavailable** | Generic bond/connect state is explicitly not treated as HFP readiness |
| PBAP/account state | **NOT RUNTIME TESTED — NXP unavailable** | App reads providers; Bluetooth/platform owns synchronization |
| SCO/audio | **NOT RUNTIME TESTED — NXP unavailable** | ACTIVE HFP call requests AOSP HFP client SCO event; real audio callbacks/routes remain authoritative |

The NXP path is public Telecom/HFP Client logic. No hidden API, reflection, RPi-specific assumption, fabricated connection, or generic Bluetooth-equals-phone shortcut was added.

## 14. Tests

Final required command:

```text
cd /home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06
./gradlew \
  :app:testDebugUnitTest \
  :app:assembleDebug \
  --offline \
  --no-daemon \
  --max-workers=10
```

Result: **BUILD SUCCESSFUL** in 22 seconds.  
Unit tests: **47 executed, 0 failures, 0 errors, 0 skipped**.

Test suites covered:

- `CallerIdentityFallbacksTest` — deterministic provider/cache/raw and permission gates;
- `PhoneNumberMatchingTest` — formatting, country prefix, short numbers, and explicit Egyptian local/international equivalence;
- `CallHistoryPresentationTest` — allowed versus restricted/unknown number exposure;
- `RecentsLoadGateTest` — forced refresh generation behavior;
- `RecentCallLabelsTest` — provider presentation labels;
- `TelecomCallPolicyTest` — real state mapping, direction/capabilities, DTMF, timer reset, compact/full UI, and restricted Telecom identity;
- `ContactSearchTest` — provider display name and formatted number matching;
- `PhoneScreenPhotoTest` — ContactsProvider-only photo URI safety;
- baseline project unit sanity test.

Additional software checks:

| Check | Result |
|---|---|
| `:phone-contract-test:assembleDebug` | **BUILD SUCCESSFUL** |
| `:app:lintDebug` | **BUILD SUCCESSFUL**; 38 non-fatal existing compatibility/dependency/style/resource warnings, no lint error |
| `bash -n tools/nxp_phone_runtime_setup.sh tools/run_phone_contract_test.sh` | **PASS** |
| Merged manifest inspection | **PASS** |
| `git diff --check` | **PASS** |

APK:

```text
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/app/build/outputs/apk/debug/app-debug.apk
SHA256 1ac22430cd7d65c6569101cd0d36ace4c5848494a494c712a4b208ad35cde2a9
```

## 15. Runtime Test Matrix

| Scenario | Result | Evidence |
|---|---|---|
| Current runtime permissions | **NOT RUNTIME TESTED — NXP unavailable** | Manifest and recovery logic only |
| System dialer component | **NOT RUNTIME TESTED — NXP unavailable** | Expected component documented/tooling checks exact string |
| HFP Client PhoneAccount | **NOT RUNTIME TESTED — NXP unavailable** | Public Telecom component match verified in code |
| ContactsProvider row/name population | **NOT RUNTIME TESTED — NXP unavailable** | Provider reader/observer verified in software |
| CallLog row population | **NOT RUNTIME TESTED — NXP unavailable** | Provider reader/observer verified in software |
| Bluetooth/PBAP pairing/share permissions | **NOT RUNTIME TESTED — NXP unavailable** | Platform responsibility documented |
| Incoming call over Launcher/other app | **NOT RUNTIME TESTED — NXP unavailable** | Compact-only source flow and manifest/theme software verified |
| Incoming saved caller name | **NOT RUNTIME TESTED — NXP unavailable** | Resolution order/matching/provider refresh software verified |
| Incoming Answer/Decline | **NOT RUNTIME TESTED — NXP unavailable** | Real Telecom command gates software verified |
| Outgoing DIALING/RINGING/full UI | **NOT RUNTIME TESTED — NXP unavailable** | Callback-driven state/UI flow software verified |
| ACTIVE/timer/End | **NOT RUNTIME TESTED — NXP unavailable** | Pure timer policy and real disconnect flow software verified |
| Incoming answer to ACTIVE/full UI | **NOT RUNTIME TESTED — NXP unavailable** | Compact finish + service full-screen transition software verified |
| Mute/Speaker/Hold/DTMF | **NOT RUNTIME TESTED — NXP unavailable** | Capability/real API paths software verified |
| SCO remote audio | **NOT RUNTIME TESTED — NXP unavailable** | HFP event and callback path inspected only |

Later NXP acceptance should run `tools/nxp_phone_runtime_setup.sh` read-only first, then perform real incoming/outgoing calls without uninstalling the app or clearing pairing/provider data. Logs and numbers must be sanitized.

## 16. Remaining Issues

1. **Type: PLATFORM / VALIDATION AVAILABILITY**  
   Impact: End-to-end NXP behavior, system-dialer selection, HFP `PhoneAccount`, real PBAP/CallLog population, actual top-window composition, call actions, and SCO audio cannot be certified from software inspection alone.  
   Recommended next action: connect to the intended NXP target/network, preserve pairing/provider state, run the read-only runtime setup checks, install with `adb install -r` only if appropriate, and execute the section 15 matrix with a real paired handset.

No known Phone code defects remain from this audit. Provider absence or provider rows without names must be classified as a platform/PBAP/pairing-data issue unless runtime evidence proves the app failed to read existing rows.

## 17. Files Modified

Exact paths:

```text
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/PHONE_FINAL_AUDIT_AND_VALIDATION.md
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/MainActivity.kt
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/bluetooth/BluetoothPhoneClient.kt
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/contacts/CallHistoryRepository.kt
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/contacts/ContactsRepository.kt
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/data/PhoneRepository.kt
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/service/PhoneCommandService.kt
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/telecom/CallerIdentityResolver.kt (deleted)
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/telecom/HyperNovaInCallService.kt
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/telecom/IncomingCallActivity.kt
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/telecom/IncomingCallNotifier.kt
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/telecom/TelecomCallController.kt
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/telecom/TelecomCallPolicy.kt
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/ui/PhoneScreenRenderer.kt
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/app/src/test/java/com/hypernova/phone/contacts/CallHistoryPresentationTest.kt
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/app/src/test/java/com/hypernova/phone/contacts/PhoneNumberMatchingTest.kt
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/app/src/test/java/com/hypernova/phone/telecom/TelecomCallPolicyTest.kt
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/app/src/test/java/com/hypernova/phone/ui/ContactSearchTest.kt
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/docs/ARCHITECTURE.md
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/docs/PERMISSIONS.md
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/tools/nxp_phone_runtime_setup.sh
/home/ayman/ITI/Android-Apps/HyperNova_Phone_Task_06/tools/run_phone_contract_test.sh
```

No file outside `HyperNova_Phone_Task_06` was modified.

## 18. Git State

Final `git status --short`:

```text
 M HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/MainActivity.kt
 M HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/bluetooth/BluetoothPhoneClient.kt
 M HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/contacts/CallHistoryRepository.kt
 M HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/contacts/ContactsRepository.kt
 M HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/data/PhoneRepository.kt
 M HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/service/PhoneCommandService.kt
 D HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/telecom/CallerIdentityResolver.kt
 M HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/telecom/HyperNovaInCallService.kt
 M HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/telecom/IncomingCallActivity.kt
 M HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/telecom/IncomingCallNotifier.kt
 M HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/telecom/TelecomCallController.kt
 M HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/telecom/TelecomCallPolicy.kt
 M HyperNova_Phone_Task_06/app/src/main/java/com/hypernova/phone/ui/PhoneScreenRenderer.kt
 M HyperNova_Phone_Task_06/app/src/test/java/com/hypernova/phone/contacts/PhoneNumberMatchingTest.kt
 M HyperNova_Phone_Task_06/app/src/test/java/com/hypernova/phone/telecom/TelecomCallPolicyTest.kt
 M HyperNova_Phone_Task_06/docs/ARCHITECTURE.md
 M HyperNova_Phone_Task_06/docs/PERMISSIONS.md
 M HyperNova_Phone_Task_06/tools/nxp_phone_runtime_setup.sh
 M HyperNova_Phone_Task_06/tools/run_phone_contract_test.sh
?? HyperNova_Phone_Task_06/PHONE_FINAL_AUDIT_AND_VALIDATION.md
?? HyperNova_Phone_Task_06/app/src/test/java/com/hypernova/phone/contacts/CallHistoryPresentationTest.kt
?? HyperNova_Phone_Task_06/app/src/test/java/com/hypernova/phone/ui/ContactSearchTest.kt
```

Tracked `git diff --stat` is reproduced in section 6. The three `??` files are intentionally untracked review changes and therefore are not included by plain `git diff --stat`.

**NOT COMMITTED. NOT PUSHED.** No remote branch was modified and `git add .` was not used.

## 19. Final Verdict

**Is the Phone source ready for the NXP demo? PARTIALLY.**

The source is **SOFTWARE VERIFIED**: architecture, manifest, permissions, state flow, provider logic, identity rules, controls, tests, lint, build, contract client, tooling syntax, APK, and final diff were validated. It is ready for installation and controlled NXP acceptance.

The demo is **RUNTIME NOT YET VERIFIED** because the NXP target was unavailable. Runtime-only behavior must not be inferred from code inspection.

| Area | Verdict | Reason |
|---|---|---|
| Incoming UX ready? | **PARTIALLY** | Software flow is verified compact-only; real top-window composition and call actions are not runtime tested |
| Outgoing UX ready? | **PARTIALLY** | Real callback-driven full UI and controls are software verified; HFP call lifecycle is not runtime tested |
| Contacts ready? | **PARTIALLY** | Provider integration/search/matching are software verified; NXP provider population is unknown |
| Recents ready? | **PARTIALLY** | CallLog reading/types/names/privacy/refresh are software verified; NXP rows are unknown |
| PBAP platform ready? | **NOT TESTED** | Pairing/contact-sharing/PBAP/provider synchronization requires the unavailable target |
| Caller names ready? | **PARTIALLY** | Deterministic real-data resolution is software verified; actual synchronized names are not runtime tested |

There are no known unresolved Phone source defects from this audit. Final demo sign-off requires the runtime matrix in section 15 on the intended NXP device.
