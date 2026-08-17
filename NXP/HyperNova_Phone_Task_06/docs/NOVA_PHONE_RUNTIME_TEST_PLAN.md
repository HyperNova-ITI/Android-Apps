# HyperNova Phone -> NOVA Runtime Test

## Read-only first
1. API version.
2. Current state.
3. Search contacts.
4. Get selected contact by real contactId.
5. Verify real numberId values.
6. Query ALL / MISSED / INCOMING / OUTGOING / REJECTED.
7. Verify realtime state callback.

## Real call tests
1. callContact(contactId, numberId).
2. callNumber(number).
3. callHistoryEntry(callId).
4. answer / decline / end.
5. setMuted(true/false).
6. setHeld(true/false).
7. setAudioRoute().
8. sendDtmf().

## Core rules
- Phone owns Android truth.
- NOVA owns language/context.
- MISSED != REJECTED.
- IDs are opaque.
- Setter commands are idempotent.
- Same requestId must not execute twice.
- Terminal success requires confirmed Android state.
