# HyperNova APK Bundle

Generated: 2026-08-13T12:56:26+03:00

Source baseline: combined `main` at `79195e4`, plus the standard-Android/network fixes awaiting the
next commit

This directory contains the currently available release/runtime
APKs generated from the HyperNova Android applications.

## APKs

- `HyperNovaLauncher-release-unsigned.apk` — 71M
- `HyperNovaClimate-release-unsigned.apk` — 15M
- `HyperNovaMedia-platform-runtime.apk` — 8.7M
- `HyperNovaMedia-release-unsigned.apk` — 8.6M
- `HyperNovaNavigation-release-unsigned.apk` — 60M
- `HyperNovaPhone-release-unsigned.apk` — 12M
- `HyperNovaVehicleGateway-demo-release-unsigned.apk` — 2.1M

## Notes

- `*-release-unsigned.apk` files are Android Studio release artifacts.
- `HyperNovaMedia-platform-runtime.apk` is the platform-signed APK
  runtime-tested on the Raspberry Pi AAOS target.
- `HyperNovaLauncher-release-unsigned.apk` was rebuilt from the combined source containing the
  standard-Android/NXP compatibility fixes, stock Settings fallback, and NOVA Launcher stage/status
  API v2. It replaces the older artifact generated from `feature/ayman-dev`.
- The demo Gateway artifact is built for `192.168.0.51:6100` with plaintext explicitly enabled for
  the isolated wired demo only. Production/default release builds remain fail-closed.
- NOVA is not copied here because a deployable release must be built with the same private
  `novaLinkToken` configured on the Pi. Do not commit that token into this directory.
- The unsigned artifacts must be signed with one agreed certificate so the signature-protected
  Launcher/NOVA/Navigation/Climate/Phone/Gateway Binder contracts work.
- Do not deploy `HyperNovaMedia-platform-runtime.apk` to the standard Android NXP guest; it is kept
  only as a record of the earlier RPi AAOS runtime test. Use the unsigned Media artifact and sign it
  with the NXP image certificate.
- SHA-256 hashes are stored in `SHA256SUMS.txt`.
