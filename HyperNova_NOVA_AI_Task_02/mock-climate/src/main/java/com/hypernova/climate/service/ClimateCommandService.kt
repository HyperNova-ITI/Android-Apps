package com.hypernova.climate.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.hypernova.climate.MockMode
import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.climate.ClimateCapabilities
import com.hypernova.contracts.climate.ClimateContract
import com.hypernova.contracts.climate.ClimateResult
import com.hypernova.contracts.climate.ClimateState
import com.hypernova.contracts.climate.IClimateCommandCallback
import com.hypernova.contracts.climate.IClimateCommandService
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.round

class ClimateCommandService : Service() {
    private data class Cached(val result: ClimateResult, val storedAt: Long)

    private val handler = Handler(Looper.getMainLooper())
    private val cache = ConcurrentHashMap<String, Cached>()
    private val stateLock = Any()
    private var powerEnabled = true
    private var driverTemperature = 22f
    private var passengerTemperature = 22f
    private var fanLevel = 3
    private var acEnabled = true
    private var autoEnabled = false
    private var recirculationEnabled = false

    private val capabilities = ClimateCapabilities(
        ClimateContract.ZONE_MODE_DUAL,
        16f,
        30f,
        0.5f,
        5,
        true,
        true,
        true,
        true,
        true,
    )

    private val binder = object : IClimateCommandService.Stub() {
        override fun getApiVersion(): Int = HyperNovaContract.API_VERSION

        override fun getCapabilities(requestId: String, callback: IClimateCommandCallback) {
            if (replay(requestId, callback)) return
            if (applyFailureMode(requestId, ClimateContract.OP_GET_CAPABILITIES, callback)) return
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
            if (applyFailureMode(requestId, ClimateContract.OP_GET_CURRENT_STATE, callback)) return
            finish(callback, result(
                requestId,
                ClimateContract.OP_GET_CURRENT_STATE,
                HyperNovaContract.STATUS_CONFIRMED,
                "Climate state is available",
                state = snapshot(),
            ))
        }

        override fun setPowerEnabled(
            requestId: String,
            enabled: Boolean,
            callback: IClimateCommandCallback,
        ) = mutate(requestId, ClimateContract.OP_SET_POWER, callback) {
            powerEnabled = enabled
            if (!enabled) acEnabled = false
            "Climate power ${if (enabled) "on" else "off"}"
        }

        override fun setTargetTemperature(
            requestId: String,
            zone: Int,
            temperatureC: Float,
            callback: IClimateCommandCallback,
        ) {
            if (replay(requestId, callback)) return
            if (applyFailureMode(requestId, ClimateContract.OP_SET_TEMPERATURE, callback)) return
            if (zone !in setOf(
                    ClimateContract.ZONE_ALL,
                    ClimateContract.ZONE_DRIVER,
                    ClimateContract.ZONE_PASSENGER,
                )
            ) {
                reject(
                    requestId,
                    ClimateContract.OP_SET_TEMPERATURE,
                    "That climate zone is unsupported",
                    ClimateContract.ERROR_UNSUPPORTED_ZONE,
                    callback,
                )
                return
            }
            if (
                temperatureC !in capabilities.minimumTemperatureC..capabilities.maximumTemperatureC ||
                !alignedToStep(temperatureC)
            ) {
                reject(
                    requestId,
                    ClimateContract.OP_SET_TEMPERATURE,
                    "Temperature must be between 16°C and 30°C in 0.5°C steps",
                    ClimateContract.ERROR_OUT_OF_RANGE,
                    callback,
                )
                return
            }
            mutate(requestId, ClimateContract.OP_SET_TEMPERATURE, callback, failureModeChecked = true) {
                powerEnabled = true
                when (zone) {
                    ClimateContract.ZONE_DRIVER -> driverTemperature = temperatureC
                    ClimateContract.ZONE_PASSENGER -> passengerTemperature = temperatureC
                    else -> {
                        driverTemperature = temperatureC
                        passengerTemperature = temperatureC
                    }
                }
                "Climate set to ${temperatureC.pretty()}°C"
            }
        }

        override fun setFanLevel(
            requestId: String,
            level: Int,
            callback: IClimateCommandCallback,
        ) {
            if (replay(requestId, callback)) return
            if (applyFailureMode(requestId, ClimateContract.OP_SET_FAN_LEVEL, callback)) return
            if (level !in 0..capabilities.maximumFanLevel) {
                reject(
                    requestId,
                    ClimateContract.OP_SET_FAN_LEVEL,
                    "Fan level must be between 0 and ${capabilities.maximumFanLevel}",
                    ClimateContract.ERROR_OUT_OF_RANGE,
                    callback,
                )
                return
            }
            mutate(requestId, ClimateContract.OP_SET_FAN_LEVEL, callback, failureModeChecked = true) {
                fanLevel = level
                "Fan set to level $level"
            }
        }

        override fun setAcEnabled(
            requestId: String,
            enabled: Boolean,
            callback: IClimateCommandCallback,
        ) = mutate(requestId, ClimateContract.OP_SET_AC, callback) {
            acEnabled = enabled
            if (enabled) powerEnabled = true
            "Air conditioning ${if (enabled) "on" else "off"}"
        }

        override fun setAutoModeEnabled(
            requestId: String,
            enabled: Boolean,
            callback: IClimateCommandCallback,
        ) = mutate(requestId, ClimateContract.OP_SET_AUTO, callback) {
            autoEnabled = enabled
            "Automatic climate ${if (enabled) "enabled" else "disabled"}"
        }

        override fun setRecirculationEnabled(
            requestId: String,
            enabled: Boolean,
            callback: IClimateCommandCallback,
        ) = mutate(requestId, ClimateContract.OP_SET_RECIRCULATION, callback) {
            recirculationEnabled = enabled
            "Recirculation ${if (enabled) "enabled" else "disabled"}"
        }
    }

    override fun onBind(intent: Intent?): IBinder? =
        binder.takeIf { intent?.action == ClimateContract.BIND_COMMAND_ACTION }

    private fun mutate(
        requestId: String,
        operation: String,
        callback: IClimateCommandCallback,
        failureModeChecked: Boolean = false,
        update: () -> String,
    ) {
        if (replay(requestId, callback)) return
        if (!failureModeChecked && applyFailureMode(requestId, operation, callback)) return
        finish(callback, result(
            requestId,
            operation,
            HyperNovaContract.STATUS_ACCEPTED,
            "Waiting for controller confirmation",
        ))
        handler.postDelayed({
            val message = synchronized(stateLock) { update() }
            val confirmed = snapshot()
            finish(callback, result(
                requestId,
                operation,
                HyperNovaContract.STATUS_CONFIRMED,
                message,
                state = confirmed,
            ))
            MockMode.status(
                this,
                "${confirmed.driverTargetTemperatureC.pretty()}°C · fan ${confirmed.fanLevel} · " +
                    "A/C ${if (confirmed.isAcEnabled) "on" else "off"}",
            )
        }, ACK_DELAY_MILLIS)
    }

    private fun applyFailureMode(
        requestId: String,
        operation: String,
        callback: IClimateCommandCallback,
    ): Boolean = when (MockMode.get(this)) {
        MockMode.REJECT -> {
            reject(
                requestId,
                operation,
                "The controller rejected the climate command",
                ClimateContract.ERROR_HARDWARE_REJECTED,
                callback,
            )
            true
        }
        MockMode.UNAVAILABLE -> {
            finish(callback, result(
                requestId,
                operation,
                HyperNovaContract.STATUS_UNAVAILABLE,
                "Climate controller is unavailable",
                HyperNovaContract.ERROR_SERVICE_UNAVAILABLE,
            ))
            true
        }
        MockMode.TIMEOUT -> {
            finish(callback, result(
                requestId,
                operation,
                HyperNovaContract.STATUS_ACCEPTED,
                "Waiting for controller confirmation",
            ))
            MockMode.status(this, "Holding request to demonstrate NOVA timeout")
            true
        }
        else -> false
    }

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
        state = snapshot(),
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

    private fun snapshot(): ClimateState = synchronized(stateLock) {
        ClimateState(
            ClimateContract.AVAILABILITY_AVAILABLE,
            powerEnabled,
            driverTemperature,
            passengerTemperature,
            fanLevel,
            acEnabled,
            autoEnabled,
            recirculationEnabled,
            System.currentTimeMillis(),
        )
    }

    private fun alignedToStep(value: Float): Boolean {
        val steps = (value - capabilities.minimumTemperatureC) / capabilities.temperatureStepC
        return abs(steps - round(steps)) < 0.001f
    }

    private fun finish(callback: IClimateCommandCallback, result: ClimateResult) {
        pruneCache()
        cache[result.requestId] = Cached(result, System.currentTimeMillis())
        try {
            callback.onResult(result)
        } catch (_: Exception) {
            // The caller owns reconnection and timeout recovery.
        }
    }

    private fun replay(requestId: String, callback: IClimateCommandCallback): Boolean {
        pruneCache()
        val cached = cache[requestId]?.result ?: return false
        try {
            callback.onResult(cached)
        } catch (_: Exception) {
            // The caller may have disconnected after retrying.
        }
        return true
    }

    private fun pruneCache() {
        val cutoff = System.currentTimeMillis() - HyperNovaContract.REQUEST_DEDUP_TTL_MILLIS
        cache.entries.removeAll { it.value.storedAt < cutoff }
    }

    private fun Float.pretty(): String =
        if (this % 1f == 0f) toInt().toString() else String.format(Locale.US, "%.1f", this)

    private companion object {
        const val ACK_DELAY_MILLIS = 450L
    }
}
