package com.hypernova.launcher.core.phone

import com.hypernova.launcher.core.state.AppConnectionState

/** Safe non-privileged Phone summary; null means Bluetooth state was unavailable. */
data class PhoneSnapshot(
    val connectionState: AppConnectionState,
    val bluetoothEnabled: Boolean? = null,
)
