package com.hypernova.climate.runtime

import com.hypernova.climate.model.ClimateState
import com.hypernova.climate.ui.state.ClimatePreview
import com.hypernova.climate.ui.state.ClimateUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide source of truth shared by the Climate UI and its exported AIDL service.
 *
 * Debug builds start from [ClimatePreview] so the laptop demo can exercise the full IPC path.
 * Release builds start unavailable and stay honest until the vehicle-gateway backend publishes
 * authoritative data here.
 */
object ClimateStateStore {
    private val mutableState = MutableStateFlow(ClimatePreview.initialUiState())

    val state: StateFlow<ClimateUiState> = mutableState.asStateFlow()

    fun snapshot(): ClimateUiState = mutableState.value

    @Synchronized
    fun update(transform: (ClimateUiState) -> ClimateUiState): ClimateUiState {
        val updated = transform(mutableState.value)
        mutableState.value = updated
        return updated
    }

    fun updateConfirmed(transform: (ClimateState) -> ClimateState): ClimateUiState =
        update { uiState ->
            val confirmed = uiState.confirmedState ?: return@update uiState
            uiState.copy(
                confirmedState = transform(confirmed).copy(
                    updatedAtEpochMillis = System.currentTimeMillis(),
                ),
                requested = null,
                isCommandPending = false,
                message = null,
            )
        }
}
