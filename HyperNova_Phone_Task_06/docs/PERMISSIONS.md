# Permissions and roles

| Capability | Standalone use | Classification |
|---|---|---|
| `BLUETOOTH_CONNECT` | View real Bluetooth state and paired devices | Runtime on Android 12+; requested from Bluetooth setup |
| `READ_PHONE_STATE` | Verify the NXP HFP Client `PhoneAccount` through public Telecom APIs | Runtime; requested with Bluetooth/phone setup |
| `READ_CONTACTS` | Query real contacts | Runtime; requested from Contacts |
| `READ_CALL_LOG` | Query real recent calls | Runtime; requested from Recents |
| `CALL_PHONE` | Submit `tel:` request to Telecom | Runtime; requested from Keypad/call action |
| `POST_NOTIFICATIONS` | Show the notification fallback if the compact incoming bar cannot launch | Runtime on Android 13+; requested with Bluetooth/phone setup |
| `MANAGE_ONGOING_CALLS` | Participate in ongoing call management where the platform grants it | Declared; availability remains platform controlled |
| Dialer role | Receive/control calls through `InCallService` | User role; explicitly requested, never forced |

Not declared: `BLUETOOTH_SCAN`, `WRITE_CALL_LOG`, `ANSWER_PHONE_CALLS`, and `MANAGE_OWN_CALLS`. HyperNova does not scan, write call history, fabricate calls, or use hidden Telecom APIs.

On the NXP image these permissions should be provisioned by product policy so the automotive UI does not interrupt the driver with runtime dialogs. PBAP synchronization itself is owned by the Bluetooth/platform stack: HyperNova reads only `ContactsProvider` and `CallLog`.
