package com.hypernova.climate.ui

import androidx.lifecycle.ViewModel
import com.hypernova.climate.model.AcMode
import com.hypernova.climate.model.AirflowMode
import com.hypernova.climate.model.ClimateCapabilities
import com.hypernova.climate.model.ClimateState
import com.hypernova.climate.runtime.ClimateStateStore
import com.hypernova.climate.ui.state.ClimateUiState
import kotlinx.coroutines.flow.StateFlow

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

    val uiState: StateFlow<ClimateUiState> = ClimateStateStore.state

    /**
     * Cycle the A/C control OFF → COOL → HEAT → OFF.
     *
     * Applied locally/optimistically for now; once the command pipeline is
     * wired this becomes a confirmed backend command instead.
     */
    fun cycleAcMode() {
        ClimateStateStore.update { state ->
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
    /** Toggle zone SYNC. Turning it on copies the driver's temp + airflow to the passenger. */
    fun toggleSync() = updateConfirmed { c ->
        val on = !(c.zonesSynchronized ?: false)
        if (on) {
            c.copy(
                zonesSynchronized = true,
                passengerTargetTemperatureC = c.driverTargetTemperatureC,
                passengerAirflowMode = c.driverAirflowMode
            )
        } else {
            c.copy(zonesSynchronized = false)
        }
    }

    private val ClimateState.synced: Boolean get() = zonesSynchronized == true

    // ---- Fan ------------------------------------------------------------
    fun fanUp() = adjustFan(+1)
    fun fanDown() = adjustFan(-1)

    private fun adjustFan(dir: Int) = updateWithCaps { c, caps ->
        val max = caps?.maximumFanLevel ?: 5
        c.copy(fanLevel = ((c.fanLevel ?: 0) + dir).coerceIn(0, max))
    }

    // ---- Zone target temperature ---------------------------------------
    fun driverTempUp() = adjustDriverTemp(+1)
    fun driverTempDown() = adjustDriverTemp(-1)
    fun passengerTempUp() = adjustPassengerTemp(+1)
    fun passengerTempDown() = adjustPassengerTemp(-1)

    private fun adjustDriverTemp(dir: Int) = updateWithCaps { c, caps ->
        val next = stepTemp(c.driverTargetTemperatureC, dir, caps)
        if (c.synced) c.copy(driverTargetTemperatureC = next, passengerTargetTemperatureC = next)
        else c.copy(driverTargetTemperatureC = next)
    }

    private fun adjustPassengerTemp(dir: Int) = updateWithCaps { c, caps ->
        val next = stepTemp(c.passengerTargetTemperatureC, dir, caps)
        if (c.synced) c.copy(driverTargetTemperatureC = next, passengerTargetTemperatureC = next)
        else c.copy(passengerTargetTemperatureC = next)
    }

    private fun stepTemp(current: Float?, dir: Int, caps: ClimateCapabilities?): Float {
        val step = caps?.temperatureStepC ?: 0.5f
        val min = caps?.minimumTemperatureC ?: 16f
        val max = caps?.maximumTemperatureC ?: 30f
        return ((current ?: min) + dir * step).coerceIn(min, max)
    }

    // ---- Airflow (independent per zone unless synced) ------------------
    fun setDriverAirflow(mode: AirflowMode) = updateConfirmed { c ->
        if (c.synced) c.copy(driverAirflowMode = mode, passengerAirflowMode = mode)
        else c.copy(driverAirflowMode = mode)
    }

    fun setPassengerAirflow(mode: AirflowMode) = updateConfirmed { c ->
        if (c.synced) c.copy(driverAirflowMode = mode, passengerAirflowMode = mode)
        else c.copy(passengerAirflowMode = mode)
    }

    /** General airflow bar: sets both zones. */
    fun setBothAirflow(mode: AirflowMode) = updateConfirmed {
        it.copy(driverAirflowMode = mode, passengerAirflowMode = mode)
    }

    // ---- Air source (mutually exclusive) -------------------------------
    fun toggleFreshAir() = updateConfirmed {
        val on = !(it.freshAirEnabled ?: false)
        it.copy(freshAirEnabled = on, recirculationEnabled = if (on) false else it.recirculationEnabled)
    }

    fun toggleRecirculation() = updateConfirmed {
        val on = !(it.recirculationEnabled ?: false)
        it.copy(recirculationEnabled = on, freshAirEnabled = if (on) false else it.freshAirEnabled)
    }

    // ---- Defrost --------------------------------------------------------
    fun toggleFrontDefrost() = updateConfirmed { it.copy(frontDefrostEnabled = !(it.frontDefrostEnabled ?: false)) }
    fun toggleRearDefrost() = updateConfirmed { it.copy(rearDefrostEnabled = !(it.rearDefrostEnabled ?: false)) }
    fun toggleMaxDefrost() = updateConfirmed { it.copy(maxDefrostEnabled = !(it.maxDefrostEnabled ?: false)) }

    // ---- Seat heating (cycle 0..levels) --------------------------------
    fun cycleDriverSeatHeat() = updateWithCaps { c, caps ->
        c.copy(driverSeatHeatingLevel = nextLevel(c.driverSeatHeatingLevel, caps?.driverSeatHeatingLevels ?: 3))
    }

    fun cyclePassengerSeatHeat() = updateWithCaps { c, caps ->
        c.copy(passengerSeatHeatingLevel = nextLevel(c.passengerSeatHeatingLevel, caps?.passengerSeatHeatingLevels ?: 3))
    }

    private fun nextLevel(current: Int?, max: Int): Int =
        if (max <= 0) 0 else ((current ?: 0) + 1) % (max + 1)

    // ---- Helpers --------------------------------------------------------
    private fun updateConfirmed(transform: (ClimateState) -> ClimateState) {
        ClimateStateStore.update { state ->
            val confirmed = state.confirmedState ?: return@update state
            state.copy(confirmedState = transform(confirmed))
        }
    }

    private fun updateWithCaps(
        transform: (ClimateState, ClimateCapabilities?) -> ClimateState
    ) {
        ClimateStateStore.update { state ->
            val confirmed = state.confirmedState ?: return@update state
            state.copy(confirmedState = transform(confirmed, state.capabilities))
        }
    }
}
