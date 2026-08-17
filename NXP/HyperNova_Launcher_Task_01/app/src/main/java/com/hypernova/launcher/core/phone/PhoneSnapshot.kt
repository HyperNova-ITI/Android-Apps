package com.hypernova.launcher.core.phone

import com.hypernova.launcher.core.state.AppConnectionState

/**
 * Real Bluetooth-backed Phone summary for the Launcher.
 *
 * bluetoothEnabled:
 *   Local Bluetooth adapter state.
 *
 * phoneConnected:
 *   True only when Android reports a connected Bluetooth device whose
 *   Bluetooth device class is PHONE.
 *
 * This is intentionally stronger than "Bluetooth is ON", but it is not yet
 * an HFP Client readiness claim. Exact HFP readiness belongs to the AAOS
 * platform integration layer.
 */
data class PhoneSnapshot(
    val connectionState: AppConnectionState,
    val bluetoothEnabled: Boolean? = null,
    val phoneConnected: Boolean = false,
    val connectedPhoneName: String? = null,
)
