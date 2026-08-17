# AOSP / AAOS integration plan

Package and action remain frozen:

```text
package: com.hypernova.phone
APK module suggestion: HyperNovaPhone
OPEN action: com.hypernova.phone.action.OPEN
```

Use this same source for the privileged build. Do not make it HOME; HyperNova Launcher remains HOME.

Required platform work:

1. Add the APK as a product `priv-app` only after permissions are reviewed and platform-sign it if required by the selected permissions/roles.
2. Add narrowly scoped `privapp-permissions-*.xml` allowlists for approved Bluetooth HFP Client / PBAP Client access; do not grant broad permissions by default.
3. Configure the Dialer role/default-dialer policy and validate `HyperNovaInCallService` lifecycle with the Telecom phone account backed by AAOS HFP.
4. Implement platform adapters behind `BluetoothPhoneClient` for verified HFP connection, PBAP sync progress, and vehicle audio endpoints. Keep raw PBAP payloads out of logs.
5. Review SELinux domains, service discovery, product overlays, and automotive UX restrictions for the actual image. No Raspberry Pi-specific code belongs in this app module.
6. If Launcher or NOVA needs state/commands, add a versioned service protected by `com.hypernova.permission.ACCESS_COCKPIT_SERVICES`; do not expose an unprotected Binder API.

Standalone works for public Bluetooth state, providers, role requests, and Telecom request dispatch. It cannot establish or claim AAOS HFP/PBAP phone readiness.
