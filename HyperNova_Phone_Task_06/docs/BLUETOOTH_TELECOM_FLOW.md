# Bluetooth and Telecom flow

```text
Pairing / Bluetooth platform
  -> public adapter state + bonded devices (standalone)
  -> AAOS HFP Client confirms hands-free profile (future privileged adapter)
  -> Android Telecom phone account / InCallService
  -> HyperNova Phone real call StateFlow
```

Standalone behavior:

- Observes Bluetooth on/off, generic adapter connection events, and paired device names after `BLUETOOTH_CONNECT` is granted.
- Reads ContactsProvider and CallLog only after their respective runtime permissions.
- Sends `tel:` requests to `TelecomManager` only after `CALL_PHONE` is granted. A dispatched request is not displayed as a successful/active call; only `InCallService` callbacks publish call state.
- Registers `HyperNovaInCallService` as a foundation for the Dialer role. Android selects it only when role/platform conditions permit.

HFP Client, PBAP Client, device discovery/connect, and vehicle call audio require AAOS platform integration. No hidden APIs or reflection are used.
