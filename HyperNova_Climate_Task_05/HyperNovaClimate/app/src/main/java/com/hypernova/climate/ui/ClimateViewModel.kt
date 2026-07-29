package com.hypernova.climate.ui

import androidx.lifecycle.ViewModel
import com.hypernova.climate.model.AcMode
import com.hypernova.climate.ui.state.ClimatePreview
import com.hypernova.climate.ui.state.ClimateUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Exposes the immutable [ClimateUiState] the screen renders from (README §36).
 *
 * The initial state comes from [ClimatePreview], which is provided per build
 * variant:
 *  - debug   -> a populated sample so the emulator matches the reference,
 *  - release -> an honest empty/unavailable state (no fake data ships).
 *
 * In later phases this flow is driven by the real ClimateBackend state instead
 * of the seeded value; the UI rendering does not change.
 */
class ClimateViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ClimatePreview.initialUiState())
    val uiState: StateFlow<ClimateUiState> = _uiState.asStateFlow()

    /**
     * Cycle the A/C control OFF → COOL → HEAT → OFF.
     *
     * Applied locally/optimistically for now; once the command pipeline is
     * wired this becomes a confirmed backend command instead.
     */
    fun cycleAcMode() {
        _uiState.update { state ->
            val confirmed = state.confirmedState ?: return@update state
            val next = when (confirmed.acMode) {
                AcMode.OFF -> AcMode.COOL
                AcMode.COOL -> AcMode.HEAT
                AcMode.HEAT -> AcMode.OFF
            }
            state.copy(confirmedState = confirmed.copy(acMode = next))
        }
    }

    /** Toggle master power (local/optimistic for now). */
    fun togglePower() = updateConfirmed { it.copy(powerEnabled = !it.powerEnabled) }

    /** Toggle AUTO mode. */
    fun toggleAuto() = updateConfirmed {
        it.copy(autoModeEnabled = !(it.autoModeEnabled ?: false))
    }

    /** Toggle zone SYNC. */
    fun toggleSync() = updateConfirmed {
        it.copy(zonesSynchronized = !(it.zonesSynchronized ?: false))
    }

    private inline fun updateConfirmed(crossinline transform: (com.hypernova.climate.model.ClimateState) -> com.hypernova.climate.model.ClimateState) {
        _uiState.update { state ->
            val confirmed = state.confirmedState ?: return@update state
            state.copy(confirmedState = transform(confirmed))
        }
    }
}
