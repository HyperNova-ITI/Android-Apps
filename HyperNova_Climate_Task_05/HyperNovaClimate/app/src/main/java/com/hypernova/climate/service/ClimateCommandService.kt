package com.hypernova.climate.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.hypernova.climate.data.ClimateStateOwner
import com.hypernova.climate.data.ClimateZone
import com.hypernova.climate.model.ClimateAvailability
import com.hypernova.climate.model.ClimateCapabilities as InternalClimateCapabilities
import com.hypernova.climate.model.ClimateState as InternalClimateState
import com.hypernova.climate.model.ClimateZoneMode
import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.climate.ClimateCapabilities
import com.hypernova.contracts.climate.ClimateContract
import com.hypernova.contracts.climate.ClimateResult
import com.hypernova.contracts.climate.ClimateState
import com.hypernova.contracts.climate.IClimateCommandCallback
import com.hypernova.contracts.climate.IClimateCommandService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.round

/**
 * Signature-protected Climate API backed by the same state owner as the UI.
 *
 * Demo builds confirm mutations immediately in [ClimateStateOwner]. Release
 * builds start unavailable, so no simulated controller values or confirmations
 * escape into production while the real vehicle backend is unfinished.
 */
class ClimateCommandService : Service() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val resultCache = ConcurrentHashMap<String, CachedResult>()

    private val binder = object : IClimateCommandService.Stub() {
        override fun getApiVersion(): Int = HyperNovaContract.API_VERSION

        override fun getCapabilities(
            requestId: String?,
            callback: IClimateCommandCallback?,
        ) = submit(requestId, ClimateContract.OP_GET_CAPABILITIES, callback) { id ->
            val state = ClimateStateOwner.currentState()
            val capabilities = state.capabilities?.toContract()
            if (capabilities == null || !state.hasAuthoritativeState()) {
                unavailable(id, ClimateContract.OP_GET_CAPABILITIES, "Climate capabilities unavailable")
            } else {
                result(
                    requestId = id,
                    operation = ClimateContract.OP_GET_CAPABILITIES,
                    status = HyperNovaContract.STATUS_CONFIRMED,
                    message = "Climate capabilities available",
                    capabilities = capabilities,
                )
            }
        }

        override fun getCurrentState(
            requestId: String?,
            callback: IClimateCommandCallback?,
        ) = submit(requestId, ClimateContract.OP_GET_CURRENT_STATE, callback) { id ->
            val state = ClimateStateOwner.currentState()
            if (state.hasAuthoritativeState()) {
                result(
                    requestId = id,
                    operation = ClimateContract.OP_GET_CURRENT_STATE,
                    status = HyperNovaContract.STATUS_CONFIRMED,
                    message = "Climate state available",
                )
            } else {
                unavailable(id, ClimateContract.OP_GET_CURRENT_STATE, "Climate state unavailable")
            }
        }

        override fun setPowerEnabled(
            requestId: String?,
            enabled: Boolean,
            callback: IClimateCommandCallback?,
        ) = mutate(requestId, ClimateContract.OP_SET_POWER, callback) {
            ClimateStateOwner.setPowerEnabled(enabled)
        }

        override fun setTargetTemperature(
            requestId: String?,
            zone: Int,
            temperatureC: Float,
            callback: IClimateCommandCallback?,
        ) = mutate(
            requestId = requestId,
            operation = ClimateContract.OP_SET_TEMPERATURE,
            callback = callback,
            validate = { id -> validateTemperature(id, zone, temperatureC) },
        ) {
            ClimateStateOwner.setTargetTemperature(zone.toInternalZone(), temperatureC)
        }

        override fun setFanLevel(
            requestId: String?,
            fanLevel: Int,
            callback: IClimateCommandCallback?,
        ) = mutate(
            requestId = requestId,
            operation = ClimateContract.OP_SET_FAN_LEVEL,
            callback = callback,
            validate = { id -> validateFanLevel(id, fanLevel) },
        ) {
            ClimateStateOwner.setFanLevel(fanLevel)
        }

        override fun setAcEnabled(
            requestId: String?,
            enabled: Boolean,
            callback: IClimateCommandCallback?,
        ) = mutate(
            requestId = requestId,
            operation = ClimateContract.OP_SET_AC,
            callback = callback,
            validate = { id -> requireCapability(id, ClimateContract.OP_SET_AC) { it.supportsAc } },
        ) {
            ClimateStateOwner.setAcEnabled(enabled)
        }

        override fun setAutoModeEnabled(
            requestId: String?,
            enabled: Boolean,
            callback: IClimateCommandCallback?,
        ) = mutate(
            requestId = requestId,
            operation = ClimateContract.OP_SET_AUTO,
            callback = callback,
            validate = { id ->
                requireCapability(id, ClimateContract.OP_SET_AUTO) { it.supportsAutoMode }
            },
        ) {
            ClimateStateOwner.setAutoModeEnabled(enabled)
        }

        override fun setRecirculationEnabled(
            requestId: String?,
            enabled: Boolean,
            callback: IClimateCommandCallback?,
        ) = mutate(
            requestId = requestId,
            operation = ClimateContract.OP_SET_RECIRCULATION,
            callback = callback,
            validate = { id ->
                requireCapability(id, ClimateContract.OP_SET_RECIRCULATION) {
                    it.supportsRecirculation
                }
            },
        ) {
            ClimateStateOwner.setRecirculationEnabled(enabled)
        }
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == ClimateContract.BIND_COMMAND_ACTION) binder else null

    override fun onDestroy() {
        executor.shutdownNow()
        resultCache.clear()
        super.onDestroy()
    }

    private fun mutate(
        requestId: String?,
        operation: String,
        callback: IClimateCommandCallback?,
        validate: (String) -> ClimateResult? = { null },
        mutation: () -> Boolean,
    ) = submit(requestId, operation, callback) { id ->
        serviceUnavailable(id, operation)?.let { return@submit it }
        validate(id)?.let { return@submit it }

        send(
            callback,
            result(
                requestId = id,
                operation = operation,
                status = HyperNovaContract.STATUS_ACCEPTED,
                message = "Climate request accepted",
            ),
        )

        if (mutation()) {
            result(
                requestId = id,
                operation = operation,
                status = HyperNovaContract.STATUS_CONFIRMED,
                message = "Climate request confirmed",
            )
        } else {
            unavailable(id, operation, "Climate service unavailable")
        }
    }

    private fun submit(
        requestId: String?,
        operation: String,
        callback: IClimateCommandCallback?,
        task: (String) -> ClimateResult,
    ) {
        if (callback == null) return
        executor.execute {
            val id = requestId.orEmpty()
            if (id.isBlank()) {
                send(callback, rejected(id, operation, "Request ID is required"))
                return@execute
            }

            purgeExpiredResults()
            resultCache[id]?.let {
                send(callback, it.result)
                return@execute
            }

            val finalResult = try {
                task(id)
            } catch (exception: Exception) {
                Log.e(TAG, "Climate contract request failed", exception)
                result(
                    requestId = id,
                    operation = operation,
                    status = HyperNovaContract.STATUS_REJECTED,
                    message = "Climate request failed",
                    errorCode = HyperNovaContract.ERROR_INTERNAL,
                )
            }
            resultCache[id] = CachedResult(SystemClock.elapsedRealtime(), finalResult)
            send(callback, finalResult)
        }
    }

    private fun validateTemperature(
        requestId: String,
        zone: Int,
        temperatureC: Float,
    ): ClimateResult? {
        val operation = ClimateContract.OP_SET_TEMPERATURE
        val state = ClimateStateOwner.currentState()
        val capabilities = state.capabilities ?: return unavailable(
            requestId,
            operation,
            "Climate service unavailable",
        )
        val internalZone = zone.toInternalZoneOrNull() ?: return result(
            requestId = requestId,
            operation = operation,
            status = HyperNovaContract.STATUS_REJECTED,
            message = "Unsupported climate zone",
            errorCode = ClimateContract.ERROR_UNSUPPORTED_ZONE,
        )
        if (internalZone == ClimateZone.PASSENGER && capabilities.zoneMode != ClimateZoneMode.DUAL) {
            return result(
                requestId = requestId,
                operation = operation,
                status = HyperNovaContract.STATUS_REJECTED,
                message = "Unsupported climate zone",
                errorCode = ClimateContract.ERROR_UNSUPPORTED_ZONE,
            )
        }

        val minimum = capabilities.minimumTemperatureC
        val maximum = capabilities.maximumTemperatureC
        val step = capabilities.temperatureStepC
        if (minimum == null || maximum == null || step == null || step <= 0f) {
            return unsupported(requestId, operation)
        }
        val increments = (temperatureC - minimum) / step
        if (
            !temperatureC.isFinite() ||
            temperatureC < minimum ||
            temperatureC > maximum ||
            abs(increments - round(increments)) > STEP_EPSILON
        ) {
            return result(
                requestId = requestId,
                operation = operation,
                status = HyperNovaContract.STATUS_REJECTED,
                message = "Temperature is outside the supported range",
                errorCode = ClimateContract.ERROR_OUT_OF_RANGE,
            )
        }
        return null
    }

    private fun validateFanLevel(requestId: String, fanLevel: Int): ClimateResult? {
        val operation = ClimateContract.OP_SET_FAN_LEVEL
        val maximum = ClimateStateOwner.currentState().capabilities?.maximumFanLevel
            ?: return unavailable(requestId, operation, "Climate service unavailable")
        return if (fanLevel in 0..maximum) {
            null
        } else {
            result(
                requestId = requestId,
                operation = operation,
                status = HyperNovaContract.STATUS_REJECTED,
                message = "Fan level is outside the supported range",
                errorCode = ClimateContract.ERROR_OUT_OF_RANGE,
            )
        }
    }

    private fun requireCapability(
        requestId: String,
        operation: String,
        supported: (InternalClimateCapabilities) -> Boolean,
    ): ClimateResult? {
        val capabilities = ClimateStateOwner.currentState().capabilities
            ?: return unavailable(requestId, operation, "Climate service unavailable")
        return if (supported(capabilities)) null else unsupported(requestId, operation)
    }

    private fun serviceUnavailable(requestId: String, operation: String): ClimateResult? =
        if (ClimateStateOwner.currentState().canAcceptCommands()) {
            null
        } else {
            unavailable(requestId, operation, "Climate service unavailable")
        }

    private fun unsupported(requestId: String, operation: String): ClimateResult = result(
        requestId = requestId,
        operation = operation,
        status = HyperNovaContract.STATUS_REJECTED,
        message = "Climate operation is not supported",
        errorCode = HyperNovaContract.ERROR_UNSUPPORTED_OPERATION,
    )

    private fun rejected(requestId: String, operation: String, message: String): ClimateResult =
        result(
            requestId = requestId,
            operation = operation,
            status = HyperNovaContract.STATUS_REJECTED,
            message = message,
            errorCode = HyperNovaContract.ERROR_INVALID_ARGUMENT,
        )

    private fun unavailable(requestId: String, operation: String, message: String): ClimateResult =
        result(
            requestId = requestId,
            operation = operation,
            status = HyperNovaContract.STATUS_UNAVAILABLE,
            message = message,
            errorCode = HyperNovaContract.ERROR_SERVICE_UNAVAILABLE,
        )

    private fun result(
        requestId: String,
        operation: String,
        status: Int,
        message: String,
        errorCode: String = HyperNovaContract.ERROR_NONE,
        capabilities: ClimateCapabilities? = null,
    ): ClimateResult = ClimateResult(
        requestId,
        operation,
        status,
        message,
        errorCode,
        capabilities,
        ClimateStateOwner.currentState().confirmedState.toContract(),
    )

    private fun send(callback: IClimateCommandCallback?, result: ClimateResult) {
        try {
            callback?.onResult(result)
        } catch (_: RemoteException) {
            // The caller went away; the authoritative state remains valid.
        }
    }

    private fun purgeExpiredResults() {
        val cutoff = SystemClock.elapsedRealtime() - HyperNovaContract.REQUEST_DEDUP_TTL_MILLIS
        resultCache.entries.removeIf { it.value.createdAtElapsedMillis < cutoff }
    }

    private fun com.hypernova.climate.ui.state.ClimateUiState.hasAuthoritativeState(): Boolean =
        confirmedState?.availability == ClimateAvailability.AVAILABLE ||
            confirmedState?.availability == ClimateAvailability.STALE

    private fun com.hypernova.climate.ui.state.ClimateUiState.canAcceptCommands(): Boolean =
        canSendCommands && confirmedState?.availability == ClimateAvailability.AVAILABLE

    private fun InternalClimateCapabilities.toContract(): ClimateCapabilities = ClimateCapabilities(
        if (zoneMode == ClimateZoneMode.DUAL) {
            ClimateContract.ZONE_MODE_DUAL
        } else {
            ClimateContract.ZONE_MODE_SINGLE
        },
        minimumTemperatureC ?: Float.NaN,
        maximumTemperatureC ?: Float.NaN,
        temperatureStepC ?: Float.NaN,
        maximumFanLevel ?: 0,
        true,
        minimumTemperatureC != null && maximumTemperatureC != null && temperatureStepC != null,
        supportsAc,
        supportsAutoMode,
        supportsRecirculation,
    )

    private fun InternalClimateState?.toContract(): ClimateState {
        val state = this
        return ClimateState(
            when (state?.availability) {
                ClimateAvailability.AVAILABLE -> ClimateContract.AVAILABILITY_AVAILABLE
                ClimateAvailability.STALE -> ClimateContract.AVAILABILITY_STALE
                else -> ClimateContract.AVAILABILITY_UNAVAILABLE
            },
            state?.powerEnabled ?: false,
            state?.driverTargetTemperatureC ?: Float.NaN,
            state?.passengerTargetTemperatureC ?: Float.NaN,
            state?.fanLevel ?: -1,
            state?.acMode != null && state.acMode != com.hypernova.climate.model.AcMode.OFF,
            state?.autoModeEnabled ?: false,
            state?.recirculationEnabled ?: false,
            state?.updatedAtEpochMillis ?: 0L,
        )
    }

    private fun Int.toInternalZoneOrNull(): ClimateZone? = when (this) {
        ClimateContract.ZONE_ALL -> ClimateZone.ALL
        ClimateContract.ZONE_DRIVER -> ClimateZone.DRIVER
        ClimateContract.ZONE_PASSENGER -> ClimateZone.PASSENGER
        else -> null
    }

    private fun Int.toInternalZone(): ClimateZone = requireNotNull(toInternalZoneOrNull())

    private data class CachedResult(
        val createdAtElapsedMillis: Long,
        val result: ClimateResult,
    )

    private companion object {
        const val TAG = "HN-ClimateCommand"
        const val STEP_EPSILON = 0.001f
    }
}
