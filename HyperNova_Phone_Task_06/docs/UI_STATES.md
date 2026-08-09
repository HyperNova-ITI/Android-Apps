# UI states

Production UI derives from real availability, provider, and Telecom values. Implemented visual branches cover Home/disconnected, Bluetooth disabled, connecting, devices, keypad, contacts, recents, incoming, dialing, active, hold, and ended call states. Call state screens are reachable only from actual Telecom callbacks.

Test-only fixture names reserve screenshot coverage for the approved 14 reference states. They are stored exclusively in `src/androidTest` and are not included in production behavior.

The standalone default is the premium disconnected state. It intentionally explains the unavailable HFP/PBAP path instead of simulating a connected phone.
