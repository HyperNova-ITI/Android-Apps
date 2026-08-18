package com.hypernova.launcher.core.settings

import com.hypernova.launcher.core.state.AppConnectionState

/** Read-only Android system settings displayed by the Settings card. */
data class SystemSettingsSnapshot(
    val connectionState: AppConnectionState,
    val wifiEnabled: Boolean? = null,
    val bluetoothEnabled: Boolean? = null,
    val brightnessPercent: Int? = null,
    val mediaVolumePercent: Int? = null,
    val errorMessage: String? = null,
)
