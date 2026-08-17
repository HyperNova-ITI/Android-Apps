# HyperNova Phone ↔ NOVA AI — Developer Handoff

> **Purpose:** This document is the implementation handoff for any developer integrating NOVA AI with the HyperNova Phone application.
>
> **Phone package:** `com.hypernova.phone`  
> **Shared contract module:** `HyperNova_Contracts/contracts`  
> **Contract API version:** `1`  
> **Status:** Phone-side command backend and real-device contract path are implemented and runtime-tested. NOVA client integration is the next consumer-side step.

---

# 1. What This Integration Is

The architecture is intentionally split into two owners:

```text
NOVA AI
  |
  |  Speech / NLU / dialogue / candidate selection
  |
  v
IPhoneCommandService (AIDL)
  |
  v
HyperNova Phone
  |
  +--> Android Contacts Provider / PBAP
  +--> Android CallLog
  +--> Android Telecom / InCallService
  +--> Bluetooth HFP state
  |
  v
Real connected phone
```

The most important rule is:

```text
Phone App = source of truth + Android/AAOS executor
NOVA AI   = language reasoning + dialogue orchestration
```

NOVA must **never invent**:

- Contact names or numbers.
- Contact IDs or number IDs.
- Call-history entries.
- Missed/incoming/outgoing call timestamps.
- Current call state.
- HFP readiness.
- Telecom command success.
- Audio-route success.

The Phone app returns real platform state. NOVA reasons over that state and presents it conversationally.

---

# 2. Repository Layout

Typical monorepo layout:

```text
Android-Apps/
|
├── HyperNova_Contracts/
│   ├── HyperNova_NOVA_Phone_Integration_Scenarios.md
│   └── contracts/
│       └── src/main/
│           ├── aidl/com/hypernova/contracts/phone/
│           └── java/com/hypernova/contracts/phone/
│
├── HyperNova_Phone_Task_06/
│   ├── app/
│   ├── phone-contract-test/
│   └── docs/
│       ├── NOVA_PHONE_BACKEND_MASTER_PLAN.md
│       └── NOVA_PHONE_RUNTIME_TEST_PLAN.md
│
└── HyperNova_NOVA_AI_Task_02/
    └── ...
```

The NOVA application should depend on the **shared Contracts module**, not on Phone implementation classes.

---

# 3. Contract Identity

These values are the public Phone integration identity:

```text
AIDL interface:
com.hypernova.contracts.phone.IPhoneCommandService

Phone application:
com.hypernova.phone

Service:
com.hypernova.phone.service.PhoneCommandService

Bind action:
com.hypernova.phone.action.BIND_COMMAND

Phone open action:
com.hypernova.phone.action.OPEN

Signature permission:
com.hypernova.permission.CONTROL_COCKPIT_APPS
```

The service is protected by the signature permission.

The production NOVA application must be signed/configured so that it can receive:

```text
com.hypernova.permission.CONTROL_COCKPIT_APPS
```

Do not remove or weaken this permission for convenience.

---

# 4. Shared Contract Files

## AIDL

```text
contracts/src/main/aidl/com/hypernova/contracts/phone/
├── IPhoneCommandCallback.aidl
├── IPhoneCommandService.aidl
├── IPhoneStatusCallback.aidl
├── PhoneCallHistoryEntry.aidl
├── PhoneContact.aidl
├── PhoneContactNumber.aidl
├── PhoneResult.aidl
└── PhoneState.aidl
```

## Java parcelables / constants

```text
contracts/src/main/java/com/hypernova/contracts/phone/
├── PhoneCallHistoryEntry.java
├── PhoneContact.java
├── PhoneContactNumber.java
├── PhoneContract.java
├── PhoneResult.java
└── PhoneState.java
```

Do not duplicate these models inside NOVA.

The shared module is the integration boundary.

---

# 5. Add the Contract to NOVA

A typical monorepo Gradle setup is:

```kotlin
// settings.gradle.kts

include(":hypernova-contracts")

project(":hypernova-contracts").projectDir =
    file("../HyperNova_Contracts/contracts")
```

Then in the NOVA app:

```kotlin
dependencies {
    implementation(project(":hypernova-contracts"))
}
```

Use the real repository layout if the relative path differs.

---

# 6. Binding to HyperNova Phone

NOVA binds explicitly by package and action.

Example:

```kotlin
val intent = Intent(PhoneContract.BIND_COMMAND_ACTION).apply {
    setPackage(PhoneContract.PACKAGE_NAME)
}

val accepted = bindService(
    intent,
    connection,
    Context.BIND_AUTO_CREATE
)
```

The service connection should create the AIDL interface:

```kotlin
override fun onServiceConnected(
    name: ComponentName,
    binder: IBinder
) {
    phoneService =
        IPhoneCommandService.Stub.asInterface(binder)

    // Safe point to query API version and register state callback.
}
```

Do not invoke Phone commands until the Binder is connected.

---

# 7. Production Binder Lifecycle

The test client exposed an important production requirement:

```text
NOVA starts
   |
   v
bind Phone service
   |
   v
BOUND
```

But Phone may later restart, update, crash, or be killed.

Therefore NOVA must support:

```text
Binder disconnect / binding died / binder death
                  |
                  v
        clear old service reference
                  |
                  v
             schedule rebind
                  |
                  v
               BOUND
```

Do **not** leave NOVA permanently in `NOT BOUND`.

Recommended client states:

```text
UNBOUND
BINDING
BOUND
RETRY_WAIT
PERMISSION_DENIED
UNAVAILABLE
```

The client should handle at least:

```kotlin
onServiceDisconnected(...)
onBindingDied(...)
onNullBinding(...)
```

and preferably Binder death via `linkToDeath()` where appropriate.

---

# 8. API Version

Always check the API version after binding:

```kotlin
val version = phoneService.getApiVersion()
```

Current version:

```text
API_VERSION = 1
```

NOVA should reject or gracefully degrade if it receives an unsupported version.

Do not assume future versions are source-compatible.

---

# 9. Commands Available to NOVA

`IPhoneCommandService` provides:

```text
getApiVersion()

getCurrentState(...)
searchContacts(...)
getContact(...)

getCallHistory(...)
getCallHistoryForContact(...)

callContact(...)
callNumber(...)
callHistoryEntry(...)

answerCall(...)
declineCall(...)
endCall(...)

setMuted(...)
setHeld(...)
setAudioRoute(...)

sendDtmf(...)

registerPhoneStatusCallback(...)
unregisterPhoneStatusCallback(...)
```

All asynchronous command calls use:

```text
requestId
+
callback
```

---

# 10. requestId Rule

Every command invocation must have a caller-generated UUID:

```kotlin
val requestId = UUID.randomUUID().toString()
```

Example:

```kotlin
phoneService.getCurrentState(
    requestId,
    callback
)
```

The Phone service deduplicates requests for a bounded TTL.

A duplicated `requestId` must never cause the real action to execute twice.

This is especially important for:

```text
callContact
callNumber
answerCall
declineCall
endCall
setMuted
setHeld
setAudioRoute
sendDtmf
```

Never reuse an old request ID for a new user intention.

---

# 11. Result Status Semantics

The generic command statuses are:

```text
STATUS_ACCEPTED
STATUS_CONFIRMED
STATUS_REJECTED
STATUS_UNAVAILABLE
STATUS_TIMEOUT
STATUS_CANCELLED
```

Important rule:

```text
STATUS_ACCEPTED != real-world success
```

NOVA should only announce an action as successful after receiving the terminal result that reflects real Phone/Telecom state.

Example:

```text
User: "رد"

NOVA
  |
  +--> answerCall(requestId)
          |
          +--> ACCEPTED
          |
          +--> Telecom executes
          |
          +--> Phone confirms ACTIVE
          |
          +--> CONFIRMED
                    |
                    v
            NOVA announces success
```

Do not say "تم الرد" merely because the Binder method returned normally.

---

# 12. PhoneState

The authoritative state object contains fields such as:

```text
availability
connectedDeviceName
hfpConnected

callState
activeContactId
activeContactName
activePhoneNumber

callStartedAtEpochMillis
callDurationSeconds

muted
held
audioRoute

canAnswer
canDecline
canEnd
canHold
canMute
canSendDtmf

updatedAtEpochMillis
```

NOVA should use these fields for state-sensitive conversation.

Examples:

```text
canAnswer == false
→ do not pretend an answer command is valid

canEnd == false
→ "مفيش مكالمة شغالة أقفلها"

hfpConnected == false
→ do not report the phone as call-ready
```

---

# 13. Phone Availability

Phone availability values include:

```text
UNAVAILABLE
DISCONNECTED
CONNECTING
READY
```

Bluetooth being enabled does **not** mean the Phone system is ready.

A real calling flow requires the relevant HFP/Telecom path to be ready.

---

# 14. Call States

Semantic call states include:

```text
IDLE
INCOMING
DIALING
ACTIVE
HELD
DISCONNECTING
ENDED
FAILED
```

NOVA must reason from the current state before choosing a command.

Examples:

```text
INCOMING
→ answer / decline are meaningful

ACTIVE
→ end / mute / hold / DTMF may be meaningful

IDLE
→ answer / decline / end should not be assumed valid
```

Phone remains the final validator.

---

# 15. Contact IDs Are Opaque

Phone returns real IDs:

```text
contactId
numberId
```

NOVA should treat them as opaque references.

Correct:

```text
searchContacts("يوسف")
        |
        v
candidate contactId="123"
        |
        v
getContact("123")
        |
        v
numberId="555"
        |
        v
callContact("123", "555")
```

Incorrect:

```text
NOVA invents "contactId=1"
NOVA invents a phone number
NOVA stores a guessed provider ID
```

Provider IDs may become stale after contact/PBAP changes.

Phone can return stale-reference errors.

---

# 16. Contact Search Flow

Example:

```text
User:
"كلم يوسف"

NOVA
  |
  v
searchContacts(query="يوسف")
  |
  +--> 0 matches
  |      |
  |      v
  |   explain no real match
  |
  +--> 1 match
  |      |
  |      v
  |   getContact(contactId)
  |
  +--> multiple matches
         |
         v
      ask user to choose
```

NOVA owns the clarification dialogue.

Phone owns the real candidate list.

---

# 17. Multiple Contacts

If multiple candidates are returned:

```text
يوسف أحمد
يوسف علي
يوسف الكاشف
```

NOVA should ask a short clarification question.

The user may then say:

```text
يوسف الكاشف
التاني
الأول
هو
عليه
```

NOVA resolves the conversational reference **only against the real candidate list it already received**.

Never choose randomly.

---

# 18. Multiple Numbers

One contact can have several real numbers.

Example:

```text
Ahmed
├── Mobile
├── Work
└── Home
```

Flow:

```text
getContact(contactId)
        |
        v
PhoneContact.numbers[]
        |
        +--> numberId
        +--> label
        +--> displayNumber
        +--> primary
```

If more than one number is plausible, NOVA asks:

```text
"الموبايل ولا الشغل؟"
```

The final `callContact()` uses the returned `numberId`.

---

# 19. Call History

Available filters:

```text
ALL
INCOMING
OUTGOING
MISSED
REJECTED
```

Important semantic rule:

```text
MISSED != REJECTED
```

Do not merge them in the NOVA semantic layer.

The UI may visually group some states, but the public Phone contract preserves the real distinction.

---

# 20. Call-History IDs

Each real CallLog record is exposed using:

```text
callId
```

Example redial:

```text
getCallHistory(OUTGOING)
        |
        v
last real outgoing entry
        |
        v
callHistoryEntry(callId)
```

Do not reconstruct a call from a formatted string if a real `callId` is available.

---

# 21. Timestamp Handling

Phone returns raw real timestamps:

```text
timestampEpochMillis
```

Phone does not need to generate conversational wording such as:

```text
"من عشر دقايق"
"النهارده الساعة 8"
"أمس"
```

That is NOVA's responsibility.

This keeps the Phone API deterministic and localization-independent.

---

# 22. Context Priority

When language is ambiguous, use this priority:

```text
Current real-world Phone event
        >
Current explicit conversation selection
        >
Recent candidate list
        >
Older conversation context
```

Example:

```text
Previous dialogue selected Ahmed.

A new incoming call is now from Youssef.

User:
"رد عليه"
```

Correct interpretation:

```text
Youssef
```

because the current real incoming event outranks stale dialogue context.

---

# 23. Setter Commands Are Idempotent

Use explicit state setters:

```text
setMuted(true)
setMuted(false)

setHeld(true)
setHeld(false)
```

Do not implement conversational tools as blind toggles.

Why:

```text
NOVA: "افتح المايك"
```

must always request:

```text
setMuted(false)
```

regardless of previous dialogue assumptions.

---

# 24. Audio Route

Semantic routes include:

```text
UNKNOWN
VEHICLE
PHONE
BLUETOOTH
```

NOVA requests a route.

Android Telecom remains authoritative for the actual route.

A successful Binder transaction alone is not route confirmation.

---

# 25. DTMF

DTMF input is one valid digit at a time:

```text
0-9
*
#
```

Example:

```kotlin
sendDtmf(
    requestId,
    "5",
    callback
)
```

NOVA should only offer DTMF in a call state where Phone reports it is supported.

---

# 26. PBAP and Contacts

The real contact path is:

```text
Connected mobile phone
        |
        v
Bluetooth PBAP Client
        |
        v
Android Contacts Provider
        |
        v
HyperNova Phone
        |
        v
NOVA contract
```

Phone does not maintain a fake production contacts database.

---

# 27. PBAP Live-Sync Behavior

PBAP can connect **after** HFP/A2DP.

Therefore this is a valid runtime sequence:

```text
HFP ready
A2DP ready
        |
        v
Phone calls/music already work
        |
        v
PBAP starts later
        |
        v
Contacts arrive in batches
```

The Phone implementation now treats Contacts Provider data as live state rather than a one-time snapshot.

It observes provider changes and refreshes Phone data when PBAP inserts/updates contacts.

This prevents the old race:

```text
Phone starts
   |
   v
Contacts query returns 0
   |
   v
CONTACTS_EMPTY cached forever   <-- old bug
   |
   v
PBAP downloads contacts later
```

The desired behavior is:

```text
Phone starts
   |
   v
initial provider snapshot
   |
   v
PBAP modifies ContactsProvider
   |
   v
provider observer
   |
   v
refresh Contacts + dependent Recents
```

---

# 28. NOVA Search During PBAP Sync

A search miss while Contacts Provider is actively changing must not immediately become a definitive:

```text
CONTACT_NOT_FOUND
```

Example:

```text
User:
"كلم أحمد"

PBAP has not downloaded Ahmed yet.
```

Expected behavior:

```text
Phone performs fresh provider lookup
        |
        v
provider is still actively changing
        |
        v
short retry
        |
        v
still not stable?
        |
        v
STATUS_UNAVAILABLE
"Contacts are still syncing"
```

NOVA can then say something equivalent to:

```text
"جهات الاتصال لسه بتتزامن، جرب تاني بعد لحظة."
```

Do not tell the user that Ahmed does not exist while the phonebook is still syncing.

---

# 29. Recents Contact-Name Resolution

Call history can arrive before the matching PBAP contact.

Example:

```text
CallLog:
01029914996

PBAP contact arrives later:
Ahmed -> 01029914996
```

The Phone layer must allow identity resolution to be retried after Contacts Provider changes.

Do not permanently negative-cache:

```text
number -> null
```

while PBAP may still be downloading.

---

# 30. Real Profile Independence

Do not assume these Bluetooth capabilities are equivalent:

```text
A2DP  = media audio
HFP   = calls / hands-free
PBAP  = contacts / phonebook
```

It is valid for:

```text
Music        ✅
Real calls   ✅
Contacts     ⏳ syncing
```

NOVA should not infer contact readiness from music or call readiness.

---

# 31. Command Error Examples

The contract exposes semantic errors including:

```text
NO_PHONE_CONNECTED
HFP_NOT_CONNECTED
NO_ACTIVE_CALL
NO_INCOMING_CALL

CONTACT_NOT_FOUND
MULTIPLE_CONTACTS
NUMBER_NOT_FOUND
MULTIPLE_NUMBERS

INVALID_PHONE_NUMBER
CALL_NOT_ALLOWED
CALL_FAILED

ANSWER_FAILED
DECLINE_FAILED
END_FAILED

MUTE_UNAVAILABLE
HOLD_UNAVAILABLE
AUDIO_ROUTE_UNAVAILABLE
DTMF_UNAVAILABLE

STALE_CONTACT_REFERENCE
STALE_CALL_REFERENCE
```

NOVA should map these to concise conversational responses.

Do not discard the error code and reason only from free-form `message`.

---

# 32. Typical NOVA Tool Wrapper

A recommended NOVA-side architecture is:

```text
Voice / text
    |
    v
NLU / Intent
    |
    v
PhoneToolOrchestrator
    |
    +--> PhoneServiceConnection
    |
    +--> ConversationPhoneContext
    |
    +--> requestId generator
    |
    +--> timeout handling
    |
    +--> result mapper
    |
    v
IPhoneCommandService
```

Suggested classes:

```text
PhoneServiceConnection
PhoneCommandClient
PhoneToolOrchestrator
PhoneConversationContext
PhoneResultMapper
```

Keep Binder details out of the LLM/NLU layer.

---

# 33. Conversation Context to Keep in NOVA

Useful short-lived NOVA state:

```text
lastContactCandidates
selectedContactId
selectedNumberId

lastHistoryCandidates
selectedCallId

lastPhoneIntent

currentRealPhoneState
```

Do not persist stale provider IDs indefinitely.

Clear or revalidate them when the underlying Phone state changes significantly.

---

# 34. Example: "Call Youssef"

```text
User
 |
 | "كلم يوسف"
 v
NOVA NLU
 |
 v
searchContacts(
    requestId=UUID,
    query="يوسف",
    limit=5
)
 |
 v
Phone
 |
 +--> real ContactsProvider
 |
 v
PhoneResult
 |
 +--> candidate contact IDs
 |
 v
NOVA
 |
 +--> one candidate?
 |       |
 |       v
 |    getContact(contactId)
 |
 +--> multiple?
         |
         v
     clarification
```

After a real number is selected:

```text
callContact(
    newRequestId,
    contactId,
    numberId
)
```

Wait for the terminal confirmed result before announcing success.

---

# 35. Example: "The Second One"

Previous result:

```text
1. Youssef Ali     contactId=A
2. Youssef Ahmed   contactId=B
3. Youssef Khaled  contactId=C
```

User:

```text
"التاني"
```

NOVA resolves locally:

```text
selectedContactId = B
```

No new fuzzy provider search is required unless the previous candidate list is no longer valid.

---

# 36. Example: Missed Calls

```text
User:
"مين آخر missed calls؟"

NOVA
 |
 v
getCallHistory(
    filter=MISSED,
    limit=...
)
 |
 v
Phone
 |
 v
real CallLog rows
```

If the user then asks:

```text
"يوسف اتصل كام مرة؟"
```

NOVA may reason over the real returned history.

If more data is required, call:

```text
getCallHistoryForContact(...)
```

Do not fabricate counts from incomplete data.

---

# 37. Example: Incoming Call

```text
PhoneState:
callState = INCOMING
activeContactName = "Youssef"
canAnswer = true
canDecline = true
```

User:

```text
"رد عليه"
```

NOVA should prioritize the current real incoming call.

Then:

```text
answerCall(
    requestId,
    callback
)
```

Wait for real confirmation.

---

# 38. Example: No Incoming Call

User:

```text
"رد"
```

but Phone reports no incoming call.

Phone may return:

```text
NO_INCOMING_CALL
```

NOVA response should be based on that real state, for example:

```text
"مفيش مكالمة داخلة دلوقتي."
```

---

# 39. Example: Mute

User:

```text
"اقفل المايك"
```

NOVA:

```text
setMuted(true)
```

Later:

```text
"افتح المايك"
```

NOVA:

```text
setMuted(false)
```

Never map these to `toggleMute()` in the NOVA layer.

---

# 40. Test Client

A dedicated Phone Contract Test Client exists:

```text
HyperNova_Phone_Task_06/phone-contract-test/
```

Package:

```text
com.hypernova.phone.contracttest
```

It can test:

```text
API version
current state
contact search
contact details
history filters
history by contact

call contact
call number
call history entry

answer
decline
end

mute / unmute
hold / resume
audio route
DTMF

status callback
```

This client is a development tool.

It must not be added to the final production image.

---

# 41. Test Client Binding

The test client has a `BIND` button.

If the Phone APK/process is updated while the test-client Activity is still alive, the old Binder can die.

In that case the test client may show:

```text
NOT BOUND
```

Pressing:

```text
BIND
```

reconnects it.

This behavior exposed the requirement that the real NOVA client must implement automatic rebind.

Do not copy the test client's manual-rebind limitation into production NOVA.

---

# 42. Build Phone + Test Client

From:

```text
HyperNova_Phone_Task_06/
```

use:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew \
    --no-daemon \
    --max-workers=10 \
    :app:assembleDebug \
    :phone-contract-test:assembleDebug
```

Expected APKs:

```text
app/build/outputs/apk/debug/app-debug.apk

phone-contract-test/build/outputs/apk/debug/
phone-contract-test-debug.apk
```

---

# 43. Runtime Logs

Useful tags:

```bash
adb logcat -s \
    HN-PhoneCommand \
    HN-Telecom \
    HN-Contacts \
    HN-CallHistory
```

Or:

```bash
adb logcat -d | \
grep -E \
'HN-Phone|HN-PhoneCommand|HN-Telecom|HN-Contacts|HN-CallHistory|FATAL EXCEPTION'
```

---

# 44. Verify PBAP

Useful runtime check:

```bash
adb shell dumpsys bluetooth_manager | \
grep -i -A25 -B5 \
'Profile: PbapClientService'
```

A connected PBAP device should appear under:

```text
Profile: PbapClientService
Devices (...)
```

The PBAP account type is:

```text
com.android.bluetooth.pbapclient
```

Contacts can be checked through the real Android provider:

```bash
adb shell content query \
    --user 10 \
    --uri content://com.android.contacts/data/phones
```

Do not build production logic around shell commands. These are diagnostics only.

---

# 45. Runtime Verification Already Completed

The Phone-side contract path has been exercised on the real AAOS target for scenarios including:

```text
real contact search
contact selection
multiple numbers

real call history
missed / incoming / outgoing semantics

real outgoing call
answer
decline
end

mute / unmute
hold / resume
audio route
DTMF

Binder callback flow
signature-protected IPC

real music interruption during calls
music resume after call

PBAP synchronization
ContactsProvider population
live refresh after PBAP changes
```

This gives NOVA a tested Phone-side backend to consume.

---

# 46. What NOVA Still Needs to Implement

The remaining work is primarily on the consumer side:

```text
[ ] Add HyperNova Contracts dependency to NOVA.
[ ] Add CONTROL_COCKPIT_APPS permission.
[ ] Implement PhoneServiceConnection.
[ ] Implement automatic Binder rebind/death handling.
[ ] Check getApiVersion() after connection.
[ ] Register IPhoneStatusCallback.
[ ] Implement Phone command wrappers.
[ ] Generate one UUID per command intent.
[ ] Map Phone errors to conversational responses.
[ ] Store short-lived candidate context.
[ ] Handle pronouns / ordinals.
[ ] Handle multiple contacts.
[ ] Handle multiple numbers.
[ ] Handle PBAP "still syncing" response.
[ ] Wait for confirmed results before speaking success.
[ ] Integrate with voice/LLM tool orchestration.
```

The Phone app should not need architectural redesign for this step.

---

# 47. Do Not Do These Things

Do not:

```text
Import com.hypernova.phone implementation classes into NOVA.
```

Do not:

```text
Read Contacts Provider directly from NOVA.
```

Do not:

```text
Read CallLog directly from NOVA.
```

Do not:

```text
Control Telecom directly from NOVA.
```

Do not:

```text
Call hidden Bluetooth APIs from NOVA.
```

Do not:

```text
Invent contacts or phone numbers.
```

Do not:

```text
Assume Binder call return == action success.
```

Do not:

```text
Use toggle semantics for mute/hold.
```

Do not:

```text
Reuse requestId values.
```

Do not:

```text
Keep stale contactId/numberId/callId forever.
```

Do not:

```text
Return "contact not found" merely because PBAP is still syncing.
```

---

# 48. Ownership Boundary

Final ownership model:

```text
+------------------------------------------------------+
| NOVA AI                                              |
|------------------------------------------------------|
| Speech recognition                                   |
| NLU / LLM reasoning                                  |
| Dialogue state                                       |
| Pronouns                                             |
| Ordinals                                             |
| Candidate clarification                              |
| Human-friendly timestamps                            |
| Tool orchestration                                   |
+---------------------------+--------------------------+
                            |
                            | AIDL
                            v
+------------------------------------------------------+
| HyperNova Phone                                      |
|------------------------------------------------------|
| Contacts Provider / PBAP truth                       |
| CallLog truth                                        |
| HFP readiness                                        |
| Telecom state                                        |
| Real call execution                                  |
| Answer / decline / end                               |
| Mute / hold / route / DTMF                           |
| Input validation                                     |
| requestId dedup                                      |
| Confirmed authoritative state                        |
+------------------------------------------------------+
```

If a feature requires real phone/system truth, it normally belongs on the Phone side.

If a feature requires understanding what the human meant, it normally belongs on the NOVA side.

---

# 49. Integration Definition of Done

The NOVA ↔ Phone integration is complete when all of the following work without direct Phone implementation imports:

```text
NOVA: "كلم يوسف"
NOVA: "يوسف الكاشف"
NOVA: "التاني"
NOVA: "رنلي عليه"

NOVA: "مين آخر missed calls؟"
NOVA: "يوسف اتصل كام مرة؟"
NOVA: "كانوا إمتى؟"

NOVA: "رد"
NOVA: "اقفل"
NOVA: "اقفل المايك"
NOVA: "افتح المايك"
NOVA: "حط المكالمة hold"
NOVA: "كمل المكالمة"
NOVA: "حول الصوت للموبايل"
NOVA: "حول الصوت للعربية"

Phone disconnected
HFP unavailable
PBAP still syncing
contact not found
multiple contacts
multiple numbers
no incoming call
no active call
Binder restart/reconnect
```

All success statements must be based on the terminal authoritative Phone result.

---

# 50. Related Documents

Read these alongside this handoff:

```text
HyperNova_Contracts/
└── HyperNova_NOVA_Phone_Integration_Scenarios.md

HyperNova_Phone_Task_06/docs/
├── NOVA_PHONE_BACKEND_MASTER_PLAN.md
└── NOVA_PHONE_RUNTIME_TEST_PLAN.md
```

Recommended order for a new NOVA developer:

```text
1. This Developer Handoff
2. Integration Scenarios
3. Runtime Test Plan
4. Backend Master Plan
5. AIDL source itself
```

---

# Final Developer Rule

If you remember only one thing:

```text
NOVA decides what the user means.
Phone decides what is actually true and executes the real action.
```

Keep that boundary intact and the same NOVA integration can remain portable across HyperNova AAOS targets without coupling the AI app to board-specific Bluetooth or Telecom implementation details.
