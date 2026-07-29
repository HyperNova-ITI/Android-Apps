package com.hypernova.climate.ui.state

/**
 * RELEASE variant of the preview provider.
 *
 * Ships an honest empty state: no capabilities, no confirmed values. The screen
 * renders "unavailable" until the real vehicle backend supplies authoritative
 * data. No dummy climate data exists in release builds (README §5).
 */
object ClimatePreview {

    fun initialUiState(): ClimateUiState = ClimateUiState(
        capabilities = null,
        confirmedState = null,
        requested = null,
        isCommandPending = false,
        canSendCommands = false,
        isStale = false,
        message = null
    )
}
