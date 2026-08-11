# HyperNova NOVA ↔ Phone Integration Scenarios

Status: **Phone Contract V1 — Scenario Design Draft**

This document defines the expected conversational behavior between:

- NOVA AI / Agent
- HyperNova Phone
- Android Telecom
- Android CallLog
- Android Contacts Provider / PBAP
- Bluetooth HFP

The purpose of this document is to freeze the required user journeys before
the Phone AIDL API is implemented.

The Phone application is always the source of truth for:

- Real contacts
- Real phone numbers
- Real call history
- Real missed calls
- Real incoming calls
- Real active-call state
- Real Bluetooth/HFP availability
- Real Telecom command results

NOVA is responsible for:

- Natural-language understanding
- Conversation context
- Resolving pronouns such as "him", "her", "the second one"
- Asking clarification questions
- Selecting IDs returned by Phone
- Formatting timestamps for the user
- Speaking the final result

NOVA must never invent contacts, phone numbers, call-history entries,
call states, or successful Telecom actions.

---

# 1. Architecture

```text
Driver
  |
  | Natural language
  v
NOVA AI / Agent
  |
  | Understand intent
  | Maintain conversation context
  | Resolve follow-up references
  v
HyperNova Phone AIDL Contract
  |
  +----------------------+-----------------------+
  |                      |                       |
  v                      v                       v
Contacts Provider      CallLog              Telecom
      ^                   ^                    ^
      |                   |                    |
     PBAP             Real history             HFP
                                               |
                                               v
                                       Connected phone
```

The AI never accesses ContactsProvider, CallLog, or Telecom directly.

The HyperNova Phone application owns those Android-domain integrations and
exports only a controlled signature-protected contract.

---

# 2. Core Contract Rule

For every Phone action:

```text
NOVA request
     |
     v
Phone validates current real state
     |
     v
Phone sends Android Telecom / Phone operation
     |
     v
Wait for real state transition
     |
     v
Return final result to NOVA
```

NOVA must never announce success before HyperNova Phone confirms the
real operation.

Example:

```text
Wrong:

NOVA -> callContact()
NOVA -> "Calling Youssef."

Correct:

NOVA -> callContact()
Phone -> STATUS_ACCEPTED
Telecom -> DIALING
Phone -> STATUS_CONFIRMED
NOVA -> "Calling Youssef El-Kashef."
```

---

# 3. Scenario PHONE-01 — Search for a Contact by First Name

## User journey

Driver:

```text
Call Youssef.
```

NOVA interprets:

```text
intent = PHONE_SEARCH_CONTACT
query  = "Youssef"
```

NOVA requests:

```text
searchContacts(
    requestId = req-1,
    query = "Youssef"
)
```

Phone searches the REAL synchronized contacts.

Example result:

```text
1.
contactId = contact_182
displayName = Youssef El-Kashef

2.
contactId = contact_441
displayName = Youssef Ahmed

3.
contactId = contact_773
displayName = Youssef Mahmoud
```

NOVA stores these candidates in conversational context.

Example:

```text
PhoneConversationContext

lastContactQuery = "Youssef"

candidates =
[
    contact_182 -> Youssef El-Kashef,
    contact_441 -> Youssef Ahmed,
    contact_773 -> Youssef Mahmoud
]

selectedContactId = null
selectedNumberId  = null
```

NOVA responds naturally:

```text
I found three contacts named Youssef:
Youssef El-Kashef, Youssef Ahmed, and Youssef Mahmoud.
Which one do you mean?
```

NOVA must NOT automatically select one contact when multiple valid
matches exist.

---

# 4. Scenario PHONE-02 — Refine a Previous Contact Search

Previous context:

```text
Youssef El-Kashef
Youssef Ahmed
Youssef Mahmoud
```

Driver:

```text
Do I have someone called Youssef El-Kashef?
```

NOVA should first inspect the candidates already returned by Phone.

If present:

```text
selectedContactId = contact_182
```

NOVA:

```text
Yes, you have Youssef El-Kashef.
```

Driver:

```text
Call him.
```

NOVA resolves:

```text
"him"
   |
   v
selectedContactId
   |
   v
contact_182
```

Then NOVA requests the call using the Phone-owned ID.

NOVA must NOT send:

```text
callContact("Youssef El-Kashef")
```

The preferred flow is:

```text
Natural-language name
       |
       v
searchContacts()
       |
       v
Real Phone-owned contactId
       |
       v
Agent resolves conversation
       |
       v
callContact(contactId)
```

---

# 5. Scenario PHONE-03 — Requested Contact Was Not in Previous Results

Previous search:

```text
Youssef Ahmed
Youssef Mahmoud
Youssef Ali
```

Driver:

```text
Do I have Youssef El-Kashef?
```

Because the requested full name is not present in the previous candidates,
NOVA may perform another real Phone search:

```text
searchContacts(
    requestId = req-2,
    query = "Youssef El-Kashef"
)
```

If Phone returns no contacts:

```text
STATUS_REJECTED
error = CONTACT_NOT_FOUND
```

NOVA:

```text
I couldn't find a contact called Youssef El-Kashef.
```

Driver:

```text
Who are the Youssefs I have then?
```

NOVA may reuse the still-valid previous candidate list:

```text
Youssef Ahmed
Youssef Mahmoud
Youssef Ali
```

Driver:

```text
Youssef Mahmoud.
```

NOVA:

```text
selectedContactId = contact_773
```

Driver:

```text
Call him.
```

NOVA calls `contact_773`.

---

# 6. Scenario PHONE-04 — Contact Has Multiple Phone Numbers

Driver:

```text
Call Youssef El-Kashef.
```

Phone returns:

```text
PhoneContact

contactId = contact_182
displayName = Youssef El-Kashef

numbers =
[
    {
        numberId = number_1
        label = Mobile
        isPrimary = true
    },

    {
        numberId = number_2
        label = Work
        isPrimary = false
    }
]
```

If product policy does not allow automatic selection, NOVA asks:

```text
Youssef El-Kashef has a Mobile number and a Work number.
Which one should I call?
```

Driver:

```text
Mobile.
```

NOVA resolves:

```text
selectedContactId = contact_182
selectedNumberId  = number_1
```

Then:

```text
callContact(
    requestId = req-3,
    contactId = contact_182,
    numberId = number_1
)
```

Phone owns the real phone number.

NOVA should work with `numberId` instead of reconstructing or inventing the
actual number.

---

# 7. Scenario PHONE-05 — Ordinal and Pronoun Resolution

NOVA previously returned:

```text
1. Youssef El-Kashef
2. Youssef Ahmed
3. Youssef Mahmoud
```

Driver:

```text
What about the second one?
```

NOVA resolves:

```text
"the second one"
       |
       v
candidate[1]
       |
       v
Youssef Ahmed
       |
       v
contact_441
```

Driver:

```text
How many numbers does he have?
```

NOVA resolves `he` to `contact_441`.

Driver:

```text
Call his work number.
```

NOVA resolves:

```text
selectedContactId = contact_441
selectedNumberId  = matching Work number
```

Then requests the Phone action.

Phone does not perform NLP reference resolution.

---

# 8. Scenario PHONE-06 — Last Missed Calls with Exact Date and Time

Driver:

```text
Who were my latest missed calls?
```

NOVA requests real call history:

```text
getCallHistory(
    requestId = req-10,
    filter = MISSED,
    limit = 5
)
```

Phone returns entries ordered newest first.

Example contract data:

```text
[
    {
        callId = call_901
        contactId = contact_182
        displayName = Youssef El-Kashef
        callType = MISSED
        timestampEpochMillis = <real timestamp>
    },

    {
        callId = call_900
        contactId = contact_441
        displayName = Ahmed Hassan
        callType = MISSED
        timestampEpochMillis = <real timestamp>
    },

    {
        callId = call_899
        contactId = null
        displayName = null
        phoneNumber = <real number when presentation allows>
        callType = MISSED
        timestampEpochMillis = <real timestamp>
    }
]
```

Phone returns an absolute timestamp:

```text
timestampEpochMillis
```

Phone does NOT return conversational strings such as:

```text
"yesterday evening"
"two hours ago"
"last Monday"
```

NOVA formats the real timestamp using the vehicle's current locale and timezone.

Example response:

```text
Your latest missed calls were:

Youssef El-Kashef on August 10 at 8:42 PM,
Ahmed Hassan on August 9 at 11:16 AM,
and an unknown number on August 9 at 9:30 AM.
```

The example times above are illustrative only.

Production answers must use the real timestamps returned by Phone.

---

# 9. Scenario PHONE-07 — Follow-up Questions About Missed Calls

Previous result:

```text
Youssef El-Kashef
Ahmed Hassan
Unknown number
```

Driver:

```text
How many times did Youssef call?
```

NOVA resolves Youssef using the previous call-history context.

NOVA may request filtered history if necessary.

Phone returns the authoritative history.

Example:

```text
Youssef El-Kashef

Missed:
10 August - 8:42 PM
10 August - 8:37 PM
9 August  - 6:15 PM
```

NOVA:

```text
Youssef El-Kashef tried to call you three times.
```

Driver:

```text
When?
```

NOVA:

```text
August 10 at 8:42 PM,
August 10 at 8:37 PM,
and August 9 at 6:15 PM.
```

Driver:

```text
Call him.
```

NOVA resolves:

```text
him
 |
 v
Youssef El-Kashef
 |
 v
contact_182
```

Then:

```text
Phone.callContact(...)
```

This produces a complete flow:

```text
"Who were my missed calls?"
        |
        v
Real CallLog results
        |
        v
"How many times did Youssef call?"
        |
        v
Conversation refinement
        |
        v
"When?"
        |
        v
Real timestamps
        |
        v
"Call him."
        |
        v
Real Telecom call
```

---

# 10. Scenario PHONE-08 — Missed and Rejected Calls Must Be Different

The AI contract must distinguish:

```text
MISSED
REJECTED
```

Definitions:

```text
MISSED
=
An incoming call that was not answered and was not explicitly declined
by the driver.

REJECTED
=
An incoming call explicitly declined by the user/system according to
product policy.
```

These must NOT be merged in the NOVA-facing contract.

Examples:

Driver:

```text
Who called me while I wasn't answering?
```

Filter:

```text
MISSED
```

Driver:

```text
Which calls did I reject?
```

Filter:

```text
REJECTED
```

---

# 11. Scenario PHONE-09 — Last Incoming Caller

Driver:

```text
Who was the last person that called me?
```

NOVA requests:

```text
getCallHistory(
    filter = INCOMING,
    limit = 1
)
```

Phone returns the real latest incoming history entry.

NOVA may answer:

```text
The last person who called you was Ahmed Hassan
today at 6:20 PM.
```

The date and time are derived from the returned real timestamp.

---

# 12. Scenario PHONE-10 — Redial Last Outgoing Call

Driver:

```text
Call the last person I called.
```

NOVA requests:

```text
getCallHistory(
    filter = OUTGOING,
    limit = 1
)
```

Phone returns:

```text
callId
contactId when resolvable
displayName
number reference
timestamp
```

NOVA:

```text
The last person you called was Youssef El-Kashef.
Calling him now.
```

Only say the second sentence after Phone accepts and confirms the real
Telecom transition according to the contract semantics.

---

# 13. Scenario PHONE-11 — Incoming Call Identity

Real-world event:

```text
Incoming Telecom call
        |
        v
Phone state = INCOMING
        |
        v
caller = Youssef El-Kashef
```

Driver:

```text
Who is calling?
```

NOVA queries:

```text
getCurrentState()
```

Phone returns the real incoming-call state.

NOVA:

```text
Youssef El-Kashef is calling.
```

Driver:

```text
Answer him.
```

Because there is an active real-world incoming-call event, the pronoun
`him` refers to the incoming caller.

NOVA:

```text
answerCall(req-x)
```

Phone:

```text
STATUS_ACCEPTED
      |
      v
Telecom answer()
      |
      v
Call state ACTIVE
      |
      v
STATUS_CONFIRMED
```

Only then does NOVA announce success.

---

# 14. Scenario PHONE-12 — Incoming Call Decline

Driver:

```text
Decline the call.
```

Valid only when:

```text
callState = INCOMING
```

NOVA:

```text
declineCall()
```

If there is no incoming call:

```text
STATUS_REJECTED
error = NO_INCOMING_CALL
```

NOVA:

```text
There is no incoming call right now.
```

---

# 15. Scenario PHONE-13 — Active Call End

Driver:

```text
End the call.
```

NOVA:

```text
endCall()
```

Valid when a disconnectable call exists.

NOVA must wait for real Phone/Telecom confirmation.

If no call exists:

```text
STATUS_REJECTED
error = NO_ACTIVE_CALL
```

---

# 16. Scenario PHONE-14 — Mute and Unmute

Driver:

```text
Mute the call.
```

Contract should use:

```text
setMuted(true)
```

NOT:

```text
toggleMute()
```

Driver:

```text
Unmute the call.
```

Contract:

```text
setMuted(false)
```

Reason:

Agent commands should be idempotent.

If the call is already muted:

```text
setMuted(true)
```

must not accidentally unmute it.

---

# 17. Scenario PHONE-15 — Hold and Resume

Driver:

```text
Put the call on hold.
```

NOVA:

```text
setHeld(true)
```

Driver:

```text
Resume the call.
```

NOVA:

```text
setHeld(false)
```

Phone validates whether the current call supports hold.

---

# 18. Scenario PHONE-16 — Audio Route

Driver:

```text
Move the call audio to my phone.
```

NOVA:

```text
setAudioRoute(PHONE)
```

Driver:

```text
Move it back to the car.
```

NOVA resolves `it` to the current active call and requests:

```text
setAudioRoute(VEHICLE)
```

Possible routes should be represented by stable contract constants.

Example:

```text
UNKNOWN
VEHICLE
PHONE
BLUETOOTH
```

The exact implementation mapping remains owned by HyperNova Phone.

---

# 19. Scenario PHONE-17 — DTMF / Automated Phone Menus

During an active call:

Automated service:

```text
Press 1 for English.
```

Driver:

```text
Press one.
```

NOVA:

```text
sendDtmf(
    requestId = req-x,
    digit = "1"
)
```

Phone validates that a DTMF-capable call exists.

If not:

```text
STATUS_REJECTED
error = DTMF_UNAVAILABLE
```

---

# 20. Scenario PHONE-18 — Phone Connection State

Driver:

```text
Is my phone connected?
```

NOVA requests:

```text
getCurrentState()
```

The authoritative answer must use the real HFP phone readiness.

Bluetooth simply being enabled is NOT enough.

Possible response:

```text
Yes. Your phone is connected for hands-free calling.
```

or:

```text
Bluetooth is on, but no phone is connected for hands-free calling.
```

---

# 21. Scenario PHONE-19 — No Phone Connected

Driver:

```text
Call Youssef.
```

Phone state:

```text
hfpConnected = false
```

The Phone service returns:

```text
STATUS_UNAVAILABLE
error = NO_PHONE_CONNECTED
```

NOVA must NOT respond:

```text
Calling Youssef.
```

Instead:

```text
I can't place the call because no phone is currently connected
for hands-free calling.
```

The selected conversational contact may remain in NOVA context.

Driver later says:

```text
Try again now.
```

NOVA may reuse the selected contact but MUST generate a new `requestId`.

---

# 22. Scenario PHONE-20 — Contact Does Not Exist

Driver:

```text
Call Mostafa Salem.
```

Phone search returns no real contact.

NOVA:

```text
I couldn't find Mostafa Salem in your contacts.
```

NOVA must NOT:

- Call a similar contact automatically.
- Invent a phone number.
- Use an LLM-generated phone number.
- Call the first fuzzy match without clarification.

---

# 23. Scenario PHONE-21 — Ambiguous Fuzzy Match

Driver:

```text
Call Youssef Kashef.
```

Real contacts:

```text
Youssef El-Kashef
Youssef Kashef
```

NOVA must ask for clarification:

```text
I found Youssef El-Kashef and Youssef Kashef.
Which one do you mean?
```

Do not guess.

---

# 24. Scenario PHONE-22 — Command in Invalid Call State

Driver:

```text
Answer.
```

Current Phone state:

```text
IDLE
```

Phone:

```text
STATUS_REJECTED
error = NO_INCOMING_CALL
```

NOVA:

```text
There is no incoming call right now.
```

Another example:

Driver:

```text
Mute the call.
```

Current state:

```text
IDLE
```

Phone:

```text
STATUS_REJECTED
error = NO_ACTIVE_CALL
```

---

# 25. Scenario PHONE-23 — Active Real-World Event Has Context Priority

Previous conversation:

```text
selectedContact = Ahmed Hassan
```

Then a real incoming call arrives:

```text
caller = Youssef El-Kashef
callState = INCOMING
```

Driver:

```text
Who is calling?
```

NOVA:

```text
Youssef El-Kashef.
```

Driver:

```text
Answer him.
```

`him` must resolve to the CURRENT incoming caller, not the older
selected contact Ahmed.

Recommended reference priority:

```text
Current real-world Phone event
            >
Current explicit conversation selection
            >
Recent candidate list
            >
Older conversation context
```

---

# 26. Scenario PHONE-24 — Duplicate Agent Requests Must Not Duplicate Calls

Every asynchronous command uses a caller-generated non-empty UUID:

```text
requestId
```

Example:

```text
requestId = 7f1d...
```

NOVA sends:

```text
callContact(requestId, contact_182)
```

If NOVA retries the exact same request because of Binder/network timing:

```text
callContact(requestId, contact_182)
```

Phone must NOT place a second call.

The duplicate request must return the cached accepted/final result
according to the shared HyperNova contract rules.

A genuinely new user command gets a new requestId.

---

# 27. Scenario PHONE-25 — Phone State Query

Driver may ask:

```text
Who am I talking to?
Is the call muted?
Is the call on hold?
Where is the audio playing?
How long have I been on this call?
Is anyone calling me?
```

These questions should use:

```text
getCurrentState()
```

Suggested authoritative state fields:

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

isMuted
isHeld

audioRoute

canAnswer
canDecline
canEnd
canHold
canMute
canSendDtmf

updatedAtEpochMillis
```

---

# 28. Agent Context Model

The following is an AI-side concept.

It is NOT Phone-owned state.

```text
PhoneConversationContext

lastContactQuery

contactCandidates[]

selectedContactId
selectedNumberId

lastCallHistoryFilter
lastCallHistoryResults[]

lastReferencedCallId

lastIntent
```

Example:

```text
User:
"Who were my missed calls?"

AI:
lastCallHistoryFilter = MISSED
lastCallHistoryResults = [...]

User:
"Youssef called how many times?"

AI:
resolves Youssef from previous results

User:
"Call him."

AI:
resolves selected contact
then invokes Phone
```

The Phone application does not need to understand:

```text
him
her
the first one
the second one
the last one
that person
call him again
```

NOVA resolves those phrases into stable Phone-owned IDs.

---

# 29. Read Operations vs Action Operations

## Read-only operations

These do not change Phone/Telecom state.

```text
getCurrentState()
searchContacts()
getContact()
getCallHistory()
```

## Actions

These may change real Phone/Telecom state.

```text
callContact()
callNumber()
answerCall()
declineCall()
endCall()
setMuted()
setHeld()
setAudioRoute()
sendDtmf()
```

This distinction should also be reflected in NOVA's tool definitions.

---

# 30. Proposed V1 Phone Contract Surface

The exact AIDL syntax will be frozen after these scenarios are approved.

Candidate methods:

```text
getApiVersion()

getCurrentState(
    requestId,
    callback
)

searchContacts(
    requestId,
    query,
    callback
)

getContact(
    requestId,
    contactId,
    callback
)

getCallHistory(
    requestId,
    filter,
    limit,
    callback
)

callContact(
    requestId,
    contactId,
    numberId,
    callback
)

callNumber(
    requestId,
    phoneNumber,
    callback
)

answerCall(
    requestId,
    callback
)

declineCall(
    requestId,
    callback
)

endCall(
    requestId,
    callback
)

setMuted(
    requestId,
    muted,
    callback
)

setHeld(
    requestId,
    held,
    callback
)

setAudioRoute(
    requestId,
    route,
    callback
)

sendDtmf(
    requestId,
    digit,
    callback
)
```

---

# 31. Proposed V1 Data Models

## PhoneContact

```text
contactId
displayName
numbers[]
```

## PhoneContactNumber

```text
numberId
label
displayNumber
isPrimary
```

## PhoneCallHistoryEntry

```text
callId
contactId?
displayName?
phoneNumber?
callType
timestampEpochMillis
durationSeconds
```

## PhoneState

```text
availability
connectedDeviceName
hfpConnected

callState

activeContactId?
activeContactName?
activePhoneNumber?

callStartedAtEpochMillis?
callDurationSeconds

isMuted
isHeld

audioRoute

canAnswer
canDecline
canEnd
canHold
canMute
canSendDtmf

updatedAtEpochMillis
```

---

# 32. Call Types

The contract must preserve the real semantic difference between:

```text
INCOMING
OUTGOING
MISSED
REJECTED
```

Do not merge `MISSED` and `REJECTED` in the NOVA-facing contract.

---

# 33. Suggested Phone-Specific Error Codes

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

Common HyperNova contract errors remain reusable:

```text
INVALID_ARGUMENT
UNSUPPORTED_OPERATION
SERVICE_UNAVAILABLE
BUSY
TIMEOUT
PERMISSION_DENIED
INTERNAL_ERROR
```

---

# 34. Security Boundary

The Phone Binder service must be exported only behind:

```text
com.hypernova.permission.CONTROL_COCKPIT_APPS
```

Protection level:

```text
signature
```

Only trusted HyperNova platform-signed applications such as NOVA may invoke
the Phone command service.

---

# 35. Source-of-Truth Rules

## Contacts

Source:

```text
Android Contacts Provider
       ^
       |
PBAP synchronized contacts
```

Do not create fake production contacts.

## Call history

Source:

```text
Android CallLog
```

Do not fabricate:

- caller names
- call types
- timestamps
- durations

## Calls

Source:

```text
Android Telecom / InCallService
```

Do not announce success before real Telecom state confirms it.

## Phone connection

Source:

```text
Real HFP integration
```

Bluetooth ON does not mean a calling-capable phone is connected.

---

# 36. NOVA Responsibilities

NOVA owns:

```text
Speech
Natural-language interpretation
Conversation memory
Candidate selection
Pronoun resolution
Ordinal resolution
Clarification questions
Date/time phrasing
User-facing response generation
```

Examples:

```text
"him"
"the second Youssef"
"the mobile number"
"the last caller"
"call him back"
"when did he call?"
```

NOVA resolves these phrases.

---

# 37. Phone Responsibilities

HyperNova Phone owns:

```text
Real contact lookup
Real contact IDs
Real number IDs
Real CallLog queries
Real call IDs
Real timestamps
Real HFP state
Real Telecom state
Real call execution
Real call-control execution
Validation
Final command confirmation
```

Phone must not depend on LLM behavior.

---

# 38. Important Demo Journeys

The initial demo should prove at least these journeys.

## Journey A — Conversational contact selection

```text
Driver:
Call Youssef.

NOVA:
I found Youssef El-Kashef, Youssef Ahmed, and Youssef Mahmoud.

Driver:
Do I have Youssef El-Kashef?

NOVA:
Yes.

Driver:
Call him.

Phone:
Real Telecom call to the selected Phone-owned contact.
```

## Journey B — Multiple phone numbers

```text
Driver:
Call Youssef El-Kashef.

NOVA:
He has Mobile and Work. Which one?

Driver:
Mobile.

Phone:
Calls the exact selected numberId.
```

## Journey C — Missed calls

```text
Driver:
Who were my latest missed calls?

NOVA:
Youssef El-Kashef on <real date/time>,
Ahmed Hassan on <real date/time>,
...

Driver:
How many times did Youssef call?

NOVA:
Three times.

Driver:
When?

NOVA:
<real timestamps>

Driver:
Call him.

Phone:
Real Telecom call.
```

## Journey D — Incoming call

```text
Phone:
Incoming call from Youssef El-Kashef.

Driver:
Who is calling?

NOVA:
Youssef El-Kashef.

Driver:
Answer him.

Phone:
Telecom answer -> ACTIVE.

NOVA:
Confirms only after real state changes.
```

## Journey E — Failure and retry

```text
Driver:
Call Youssef.

Phone:
NO_PHONE_CONNECTED.

NOVA:
I can't place the call because no phone is connected.

<Phone becomes connected>

Driver:
Try again now.

NOVA:
Reuses the selected contact,
creates a NEW requestId,
and sends a new Phone command.
```

---

# 39. Out of Scope for Initial V1

Do not include these in the first Phone Agent contract until separately
designed and reviewed:

```text
Emergency calling policy
Conference calls
Call transfer
Multiple simultaneous calls
Automatic call recording
Voicemail control
Contact creation/editing/deletion
Arbitrary SMS/message sending
Automatic fuzzy-match calling without confirmation
```

These may be added later with explicit product and safety behavior.

---

# 40. Implementation Direction

The intended implementation is:

```text
HyperNova_Contracts
        |
        | Phone AIDL + Parcelable models
        v
HyperNova Phone
        |
        | Signature-protected Binder service
        v
Existing Phone repositories / Telecom controller
        |
        v
Android Telecom / CallLog / Contacts / HFP
```

NOVA consumes the SAME `HyperNova_Contracts` module.

Do not copy independent AIDL definitions into the NOVA application.

Both Phone and NOVA must compile against the shared contract source.

---

# 41. Contract Freeze Rule

This document defines the intended Phone V1 behavior.

Before implementation:

1. Review all scenarios.
2. Agree on method names.
3. Agree on data models.
4. Agree on error semantics.
5. Freeze the AIDL wire format.
6. Implement Phone service.
7. Implement NOVA client.
8. Test the contract without relying on UI.
9. Verify real Contacts, CallLog, HFP, and Telecom behavior.
10. Only then bake the verified implementation into the HyperNova AAOS image.

A build success is not sufficient.

Real runtime behavior must be verified.
