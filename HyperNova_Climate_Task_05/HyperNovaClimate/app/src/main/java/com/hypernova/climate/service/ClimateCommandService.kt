package com.hypernova.climate.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.hypernova.climate.BuildConfig
import com.hypernova.climate.model.AcMode
import com.hypernova.climate.model.ClimateAvailability as AppClimateAvailability
import com.hypernova.climate.model.ClimateMode
import com.hypernova.climate.model.ClimateState as AppClimateState
import com.hypernova.climate.model.ClimateZoneMode
import com.hypernova.climate.runtime.ClimateStateStore
import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.climate.ClimateCapabilities
import com.hypernova.contracts.climate.ClimateContract
import com.hypernova.contracts.climate.ClimateResult
import com.hypernova.contracts.climate.ClimateState
import com.hypernova.contracts.climate.IClimateCommandCallback
import com.hypernova.contracts.climate.IClimateCommandService
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.round

/**
 * Frozen NOVA/Launcher Climate API.
 *
 * The debug variant uses the shared preview state as an explicit laptop demo backend. Release
 * mutations stay unavailable until the Android vehicle-gateway/QNX backend replaces this adapter;
 * release code never reports a local UI mutation as a hardware confirmation.
 */
class ClimateCommandService : Service() {
    private data class Cached(val result: ClimateResult, val storedAt: Long)

    private val handler = Handler(Looper.getMainLooper())
    private val cache = ConcurrentHashMap<String, Cached>()
    private val pendingCallbacks =
        ConcurrentHashMap<String, CopyOnWriteArrayList<IClimateCommandCallback>>()

    private val binder = object : IClimateCommandService.Stub() {
        override fun getApiVersion(): Int = HyperNovaContract.API_VERSION

        override fun getCapabilities(requestId: String, callback: IClimateCommandCallback) {
            if (replay(requestId, callback)) return
            if (!validRequestId(requestId, ClimateContract.OP_GET_CAPABILITIES, callback)) return
            val capabilities = contractCapabilities()
            if (capabilities == null) {
                unavailable(requestId, ClimateContract.OP_GET_CAPABILITIES, callback)
                return
            }
            finish(callback, result(
                requestId,
                ClimateContract.OP_GET_CAPABILITIES,
                HyperNovaContract.STATUS_CONFIRMED,
                "Climate capabilities available",
                capabilities = capabilities,
            ))
        }

        override fun getCurrentState(requestId: String, callback: IClimateCommandCallback) {
            if (replay(requestId, callback)) return
            if (!validRequestId(requestId, ClimateContract.OP_GET_CURRENT_STATE, callback)) return
            val state = contractState()
            if (state == null) {
                unavailable(requestId, ClimateContract.OP_GET_CURRENT_STATE, callback)
                return
            }
            finish(callback, result(
                requestId,
                ClimateContract.OP_GET_CURRENT_STATE,
                HyperNovaContract.STATUS_CONFIRMED,
                "Climate state is available",
                state = state,
            ))
        }

        override fun setPowerEnabled(
            requestId: String,
            enabled: Boolean,
            callback: IClimateCommandCallback,
        ) = mutate(requestId, ClimateContract.OP_SET_POWER, callback) { state ->
            state.copy(
                powerEnabled = enabled,
                mode = if (enabled) {
                    if (state.autoModeEnabled == true) ClimateMode.AUTO else ClimateMode.MANUAL
                } else {
                    ClimateMode.OFF
                },
                acMode = if (enabled) state.acMode else AcMode.OFF,
            ) to "Climate power ${if (enabled) "on" else "off"}"
        }

        override fun setTargetTemperature(
            requestId: String,
            zone: Int,
            temperatureC: Float,
            callback: IClimateCommandCallback,
        ) {
            if (replay(requestId, callback)) return
            if (!validRequestId(requestId, ClimateContract.OP_SET_TEMPERATURE, callback)) return
            if (!BuildConfig.DEBUG) {
                unavailable(requestId, ClimateContract.OP_SET_TEMPERATURE, callback)
                return
            }

            val capabilities = ClimateStateStore.snapshot().capabilities
            val supportedZones = if (capabilities?.zoneMode == ClimateZoneMode.DUAL) {
                setOf(ClimateContract.ZONE_ALL, ClimateContract.ZONE_DRIVER, ClimateContract.ZONE_PASSENGER)
            } else {
                setOf(ClimateContract.ZONE_ALL, ClimateContract.ZONE_DRIVER)
            }
            if (zone !in supportedZones) {
                reject(
                    requestId,
                    ClimateContract.OP_SET_TEMPERATURE,
                    "That climate zone is unsupported",
                    ClimateContract.ERROR_UNSUPPORTED_ZONE,
                    callback,
                )
                return
            }

            val minimum = capabilities?.minimumTemperatureC
            val maximum = capabilities?.maximumTemperatureC
            val step = capabilities?.temperatureStepC
            if (
                minimum == null || maximum == null || step == null ||
                temperatureC !in minimum..maximum || !alignedToStep(temperatureC, minimum, step)
            ) {
                reject(
                    requestId,
                    ClimateContract.OP_SET_TEMPERATURE,
                    "Temperature is outside the supported range",
                    ClimateContract.ERROR_OUT_OF_RANGE,
                    callback,
                )
                return
            }

            mutate(
                requestId,
                ClimateContract.OP_SET_TEMPERATURE,
                callback,
                alreadyChecked = true,
            ) { state ->
                val updated = when (zone) {
                    ClimateContract.ZONE_DRIVER -> state.copy(driverTargetTemperatureC = temperatureC)
                    ClimateContract.ZONE_PASSENGER -> state.copy(passengerTargetTemperatureC = temperatureC)
                    else -> state.copy(
                        driverTargetTemperatureC = temperatureC,
                        passengerTargetTemperatureC = temperatureC,
                    )
                }.copy(
                    powerEnabled = true,
                    mode = if (state.autoModeEnabled == true) ClimateMode.AUTO else ClimateMode.MANUAL,
                )
                updated to "Climate set to ${temperatureC.pretty()}°C"
            }
        }

        override fun setFanLevel(
            requestId: String,
            level: Int,
            callback: IClimateCommandCallback,
        ) {
            if (replay(requestId, callback)) return
            if (!validRequestId(requestId, ClimateContract.OP_SET_FAN_LEVEL, callback)) return
            if (!BuildConfig.DEBUG) {
                unavailable(requestId, ClimateContract.OP_SET_FAN_LEVEL, callback)
                return
            }
            val maximum = ClimateStateStore.snapshot().capabilities?.maximumFanLevel
            if (maximum == null || level !in 0..maximum) {
                reject(
                    requestId,
                    ClimateContract.OP_SET_FAN_LEVEL,
                    "Fan level is outside the supported range",
                    ClimateContract.ERROR_OUT_OF_RANGE,
                    callback,
                )
                return
            }
            mutate(
                requestId,
                ClimateContract.OP_SET_FAN_LEVEL,
                callback,
                alreadyChecked = true,
            ) { state ->
                state.copy(fanLevel = level) to "Fan set to level $level"
            }
        }

        override fun setAcEnabled(
            requestId: String,
            enabled: Boolean,
            callback: IClimateCommandCallback,
        ) = mutate(requestId, ClimateContract.OP_SET_AC, callback) { state ->
            state.copy(
                powerEnabled = state.powerEnabled || enabled,
                acMode = if (enabled) AcMode.COOL else AcMode.OFF,
                mode = when {
                    enabled -> ClimateMode.COOLING
                    state.autoModeEnabled == true -> ClimateMode.AUTO
                    state.powerEnabled -> ClimateMode.MANUAL
                    else -> ClimateMode.OFF
                },
            ) to "Air conditioning ${if (enabled) "on" else "off"}"
        }

        override fun setAutoModeEnabled(
            requestId: String,
            enabled: Boolean,
            callback: IClimateCommandCallback,
        ) = mutate(requestId, ClimateContract.OP_SET_AUTO, callback) { state ->
            state.copy(
                autoModeEnabled = enabled,
                mode = when {
                    !state.powerEnabled -> ClimateMode.OFF
                    enabled -> ClimateMode.AUTO
                    else -> ClimateMode.MANUAL
                },
            ) to "Automatic climate ${if (enabled) "enabled" else "disabled"}"
        }

        override fun setRecirculationEnabled(
            requestId: String,
            enabled: Boolean,
            callback: IClimateCommandCallback,
        ) = mutate(requestId, ClimateContract.OP_SET_RECIRCULATION, callback) { state ->
            state.copy(
                recirculationEnabled = enabled,
                freshAirEnabled = if (enabled) false else state.freshAirEnabled,
            ) to "Recirculation ${if (enabled) "enabled" else "disabled"}"
        }
    }

    override fun onBind(intent: Intent?): IBinder? =
        binder.takeIf { intent?.action == ClimateContract.BIND_COMMAND_ACTION }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        pendingCallbacks.clear()
        super.onDestroy()
    }

    private fun mutate(
        requestId: String,
        operation: String,
        callback: IClimateCommandCallback,
        alreadyChecked: Boolean = false,
        update: (AppClimateState) -> Pair<AppClimateState, String>,
    ) {
        if (!alreadyChecked && replay(requestId, callback)) return
        if (!alreadyChecked && !validRequestId(requestId, operation, callback)) return
        if (!BuildConfig.DEBUG || ClimateStateStore.snapshot().confirmedState == null) {
            unavailable(requestId, operation, callback)
            return
        }

        finish(callback, result(
            requestId,
            operation,
            HyperNovaContract.STATUS_ACCEPTED,
            "Waiting for climate confirmation",
        ))
        handler.postDelayed({
            var message = "Climate updated"
            val updated = ClimateStateStore.updateConfirmed { state ->
                update(state).also { message = it.second }.first
            }
            finish(callback, result(
                requestId,
                operation,
                HyperNovaContract.STATUS_CONFIRMED,
                message,
                state = updated.confirmedState?.toContract(),
            ))
        }, DEMO_CONFIRMATION_DELAY_MILLIS)
    }

    private fun validRequestId(
        requestId: String,
        operation: String,
        callback: IClimateCommandCallback,
    ): Boolean {
        if (requestId.isNotBlank()) return true
        reject(
            requestId,
            operation,
            "requestId must not be blank",
            HyperNovaContract.ERROR_INVALID_ARGUMENT,
            callback,
        )
        return false
    }

    private fun unavailable(
        requestId: String,
        operation: String,
        callback: IClimateCommandCallback,
    ) = finish(callback, result(
        requestId,
        operation,
        HyperNovaContract.STATUS_UNAVAILABLE,
        "Vehicle climate gateway is unavailable",
        HyperNovaContract.ERROR_SERVICE_UNAVAILABLE,
        state = contractState(),
    ))

    private fun reject(
        requestId: String,
        operation: String,
        message: String,
        errorCode: String,
        callback: IClimateCommandCallback,
    ) = finish(callback, result(
        requestId,
        operation,
        HyperNovaContract.STATUS_REJECTED,
        message,
        errorCode,
        state = contractState(),
    ))

    private fun result(
        requestId: String,
        operation: String,
        status: Int,
        message: String,
        errorCode: String = HyperNovaContract.ERROR_NONE,
        capabilities: ClimateCapabilities? = null,
        state: ClimateState? = null,
    ) = ClimateResult(
        requestId,
        operation,
        status,
        message,
        errorCode,
        capabilities,
        state,
    )

    private fun contractCapabilities(): ClimateCapabilities? =
        ClimateStateStore.snapshot().capabilities?.let { capabilities ->
            ClimateCapabilities(
                if (capabilities.zoneMode == ClimateZoneMode.DUAL) {
                    ClimateContract.ZONE_MODE_DUAL
                } else {
                    ClimateContract.ZONE_MODE_SINGLE
                },
                capabilities.minimumTemperatureC ?: Float.NaN,
                capabilities.maximumTemperatureC ?: Float.NaN,
                capabilities.temperatureStepC ?: Float.NaN,
                capabilities.maximumFanLevel ?: -1,
                true,
                capabilities.minimumTemperatureC != null && capabilities.maximumTemperatureC != null,
                capabilities.supportsAc,
                capabilities.supportsAutoMode,
                capabilities.supportsRecirculation,
            )
        }

    private fun contractState(): ClimateState? =
        ClimateStateStore.snapshot().confirmedState?.toContract()

    private fun AppClimateState.toContract() = ClimateState(
        when (availability) {
            AppClimateAvailability.AVAILABLE -> ClimateContract.AVAILABILITY_AVAILABLE
            AppClimateAvailability.STALE -> ClimateContract.AVAILABILITY_STALE
            AppClimateAvailability.UNAVAILABLE -> ClimateContract.AVAILABILITY_UNAVAILABLE
        },
        powerEnabled,
        driverTargetTemperatureC ?: Float.NaN,
        passengerTargetTemperatureC ?: Float.NaN,
        fanLevel ?: -1,
        acMode != AcMode.OFF,
        autoModeEnabled == true,
        recirculationEnabled == true,
        updatedAtEpochMillis,
    )

    private fun alignedToStep(value: Float, minimum: Float, step: Float): Boolean {
        if (!value.isFinite() || !minimum.isFinite() || !step.isFinite() || step <= 0f) return false
        val steps = (value - minimum) / step
        return abs(steps - round(steps)) < 0.001f
    }

    private fun finish(callback: IClimateCommandCallback, result: ClimateResult) {
        pruneCache()
        if (result.status == HyperNovaContract.STATUS_ACCEPTED) {
            pendingCallbacks.computeIfAbsent(result.requestId) { CopyOnWriteArrayList() }
                .addIfAbsent(callback)
        }
        cache[result.requestId] = Cached(result, System.currentTimeMillis())

        val callbacks = if (result.status == HyperNovaContract.STATUS_ACCEPTED) {
            listOf(callback)
        } else {
            val waiting = pendingCallbacks.remove(result.requestId)?.toMutableList() ?: mutableListOf()
            if (callback !in waiting) waiting += callback
            waiting
        }
        callbacks.forEach { deliver(it, result) }
    }

    private fun deliver(callback: IClimateCommandCallback, result: ClimateResult) {
        try {
            callback.onResult(result)
        } catch (_: Exception) {
            // The caller owns reconnection and timeout recovery.
        }
    }

    private fun replay(requestId: String, callback: IClimateCommandCallback): Boolean {
        pruneCache()
        val cached = cache[requestId]?.result ?: return false
        if (cached.status == HyperNovaContract.STATUS_ACCEPTED) {
            pendingCallbacks.computeIfAbsent(requestId) { CopyOnWriteArrayList() }
                .addIfAbsent(callback)
        }
        deliver(callback, cached)
        return true
    }

    private fun pruneCache() {
        val cutoff = System.currentTimeMillis() - HyperNovaContract.REQUEST_DEDUP_TTL_MILLIS
        cache.entries.removeAll { it.value.storedAt < cutoff }
    }

    private fun Float.pretty(): String =
        if (this % 1f == 0f) toInt().toString() else String.format(Locale.US, "%.1f", this)

    private companion object {
        const val DEMO_CONFIRMATION_DELAY_MILLIS = 350L
    }
}
