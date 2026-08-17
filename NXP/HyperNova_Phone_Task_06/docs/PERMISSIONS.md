# Permissions and roles

| Capability | Standalone use | Classification |
|---|---|---|
| `BLUETOOTH_CONNECT` | View real Bluetooth state and paired devices | Runtime on Android 12+; requested from Bluetooth setup |
| `READ_CONTACTS` | Query real contacts | Runtime; requested from Contacts |
| `READ_CALL_LOG` | Query real recent calls | Runtime; requested from Recents |
| `CALL_PHONE` | Submit `tel:` request to Telecom | Runtime; requested from Keypad/call action |
| Dialer role | Receive/control calls through `InCallService` | User role; explicitly requested, never forced |

Not declared now: `BLUETOOTH_SCAN`, `WRITE_CALL_LOG`, `ANSWER_PHONE_CALLS`, `MANAGE_OWN_CALLS`, `READ_PHONE_STATE`, and notification permissions. They are not required for this standalone foundation and are not added speculatively. A future parked-safe discovery implementation may add `BLUETOOTH_SCAN` contextually.

AAOS HFP/PBAP access may require platform permissions unavailable to a normal APK. Those should be granted only through a reviewed privapp/platform integration.
