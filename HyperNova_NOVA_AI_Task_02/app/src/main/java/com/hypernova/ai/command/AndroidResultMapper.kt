package com.hypernova.ai.command

import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.climate.ClimateCapabilities
import com.hypernova.contracts.climate.ClimateResult
import com.hypernova.contracts.climate.ClimateState
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.contracts.navigation.NavigationDestination
import com.hypernova.contracts.navigation.NavigationResult
import com.hypernova.contracts.navigation.NavigationPlaceContactResult
import com.hypernova.contracts.phone.PhoneCallHistoryEntry
import com.hypernova.contracts.phone.PhoneContact
import com.hypernova.contracts.phone.PhoneContactNumber
import com.hypernova.contracts.phone.PhoneContract
import com.hypernova.contracts.phone.PhoneResult
import com.hypernova.contracts.phone.PhoneState

object AndroidResultMapper {
    fun navigation(request: CommandRequest, result: NavigationResult?): CommandResult {
        if (result == null) return invalidProviderResult(request)
        if (result.requestId != request.requestId || result.operation != request.operation) {
            return invalidProviderResult(request)
        }
        val status = CommandStatus.fromContract(result.status) ?: return invalidProviderResult(request)
        val data = linkedMapOf<String, Any?>()
        if (result.destinations.isNotEmpty()) {
            data["destinations"] = result.destinations
                .take(NavigationContract.MAX_DESTINATION_RESULTS)
                .map { it.toWireMap() }
        }
        result.selectedDestination?.let { data["selected_destination"] = it.toWireMap() }
        if (result.navigationState > 0) {
            data["navigation_state"] = navigationState(result.navigationState)
        }
        if (result.etaSeconds >= 0) data["eta_seconds"] = result.etaSeconds
        if (result.distanceMeters >= 0) data["distance_meters"] = result.distanceMeters
        return CommandResult(
            request = request,
            status = status,
            message = result.message.orEmpty().ifBlank { status.wireValue },
            errorCode = result.errorCode?.takeIf(String::isNotBlank),
            data = data,
        )
    }

    fun navigationPlaceContact(
        request: CommandRequest,
        result: NavigationPlaceContactResult?,
    ): CommandResult {
        if (result == null || result.requestId != request.requestId) {
            return invalidProviderResult(request)
        }
        val status = CommandStatus.fromContract(result.status) ?: return invalidProviderResult(request)
        val data = linkedMapOf<String, Any?>()
        result.destinationId?.takeIf(String::isNotBlank)?.let { data["destination_id"] = it }
        result.displayName?.takeIf(String::isNotBlank)?.let { data["display_name"] = it }
        result.phoneNumber?.takeIf(String::isNotBlank)?.let { data["phone_number"] = it }
        return CommandResult(
            request = request,
            status = status,
            message = result.message.orEmpty().ifBlank { status.wireValue },
            errorCode = result.errorCode?.takeIf(String::isNotBlank),
            data = data,
        )
    }

    fun climate(request: CommandRequest, result: ClimateResult?): CommandResult {
        if (result == null) return invalidProviderResult(request)
        if (result.requestId != request.requestId || result.operation != request.operation) {
            return invalidProviderResult(request)
        }
        val status = CommandStatus.fromContract(result.status) ?: return invalidProviderResult(request)
        val data = linkedMapOf<String, Any?>()
        result.capabilities?.let { data["capabilities"] = it.toWireMap() }
        result.confirmedState?.let { data["confirmed_state"] = it.toWireMap() }
        return CommandResult(
            request = request,
            status = status,
            message = result.message.orEmpty().ifBlank { status.wireValue },
            errorCode = result.errorCode?.takeIf(String::isNotBlank),
            data = data,
        )
    }

    fun phone(request: CommandRequest, result: PhoneResult?): CommandResult {
        if (result == null) return invalidProviderResult(request)
        if (result.requestId != request.requestId || result.operation != request.operation) {
            return invalidProviderResult(request)
        }
        val status = CommandStatus.fromContract(result.status) ?: return invalidProviderResult(request)
        val data = linkedMapOf<String, Any?>()
        if (result.totalMatches >= 0) data["total_matches"] = result.totalMatches
        if (result.contacts.isNotEmpty()) {
            data["contacts"] = result.contacts
                .take(PhoneContract.MAX_CONTACT_RESULT_LIMIT)
                .map { it.toWireMap() }
        }
        result.contact?.let { data["contact"] = it.toWireMap() }
        if (result.callHistory.isNotEmpty()) {
            data["call_history"] = result.callHistory
                .take(PhoneContract.MAX_CALL_HISTORY_LIMIT)
                .map { it.toWireMap() }
        }
        result.confirmedState?.let { data["confirmed_state"] = it.toWireMap() }
        return CommandResult(
            request = request,
            status = status,
            message = result.message.orEmpty().ifBlank { status.wireValue },
            errorCode = result.errorCode?.takeIf(String::isNotBlank),
            data = data,
        )
    }

    private fun invalidProviderResult(request: CommandRequest): CommandResult = request.failure(
        status = CommandStatus.UNAVAILABLE,
        message = "${request.domain.replaceFirstChar(Char::uppercase)} returned an invalid result",
        errorCode = HyperNovaContract.ERROR_INTERNAL,
    )

    private fun NavigationDestination.toWireMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "source" to sourceName(source),
        "title" to title,
        "subtitle" to subtitle,
        "category" to category,
        "distance_meters" to distanceMeters.takeIf { it >= 0 },
    ).filterValues { it != null }

    private fun ClimateCapabilities.toWireMap(): Map<String, Any?> = linkedMapOf(
        "zone_mode" to if (zoneMode == 2) "dual" else "single",
        "minimum_temperature_c" to minimumTemperatureC,
        "maximum_temperature_c" to maximumTemperatureC,
        "temperature_step_c" to temperatureStepC,
        "maximum_fan_level" to maximumFanLevel,
        "supports_power" to supportsPower(),
        "supports_temperature" to supportsTemperature(),
        "supports_ac" to supportsAc(),
        "supports_auto" to supportsAuto(),
        "supports_recirculation" to supportsRecirculation(),
    )

    private fun ClimateState.toWireMap(): Map<String, Any?> = linkedMapOf(
        "availability" to when (availability) {
            1 -> "available"
            2 -> "stale"
            else -> "unavailable"
        },
        "power_enabled" to isPowerEnabled,
        "driver_temperature_c" to driverTargetTemperatureC.takeUnless(Float::isNaN),
        "passenger_temperature_c" to passengerTargetTemperatureC.takeUnless(Float::isNaN),
        "fan_level" to fanLevel.takeIf { it >= 0 },
        "ac_enabled" to isAcEnabled,
        "auto_enabled" to isAutoModeEnabled,
        "recirculation_enabled" to isRecirculationEnabled,
        "updated_at_epoch_millis" to updatedAtEpochMillis,
    ).filterValues { it != null }

    private fun PhoneContact.toWireMap(): Map<String, Any?> = linkedMapOf(
        "id" to contactId,
        "display_name" to displayName,
        "numbers" to numbers.map { it.toWireMap() },
    )

    private fun PhoneContactNumber.toWireMap(): Map<String, Any?> = linkedMapOf(
        "id" to numberId,
        "label" to label,
        "display_number" to displayNumber,
        "primary" to isPrimary,
    )

    private fun PhoneCallHistoryEntry.toWireMap(): Map<String, Any?> = linkedMapOf(
        "id" to callId,
        "contact_id" to contactId,
        "display_name" to displayName,
        "phone_number" to phoneNumber,
        "presentation" to numberPresentation,
        "call_type" to callType,
        "timestamp_epoch_millis" to timestampEpochMillis,
        "duration_seconds" to durationSeconds,
    ).filterValues { it != null }

    private fun PhoneState.toWireMap(): Map<String, Any?> = linkedMapOf(
        "availability" to when (availability) {
            PhoneContract.AVAILABILITY_READY -> "ready"
            PhoneContract.AVAILABILITY_CONNECTING -> "connecting"
            PhoneContract.AVAILABILITY_DISCONNECTED -> "disconnected"
            else -> "unavailable"
        },
        "connected_device_name" to connectedDeviceName,
        "hfp_connected" to isHfpConnected,
        "call_state" to when (callState) {
            PhoneContract.CALL_STATE_INCOMING -> "incoming"
            PhoneContract.CALL_STATE_DIALING -> "dialing"
            PhoneContract.CALL_STATE_ACTIVE -> "active"
            PhoneContract.CALL_STATE_HELD -> "held"
            PhoneContract.CALL_STATE_DISCONNECTING -> "disconnecting"
            PhoneContract.CALL_STATE_ENDED -> "ended"
            PhoneContract.CALL_STATE_FAILED -> "failed"
            else -> "idle"
        },
        "active_contact_id" to activeContactId,
        "active_contact_name" to activeContactName,
        "active_phone_number" to activePhoneNumber,
        "call_duration_seconds" to callDurationSeconds,
        "muted" to isMuted,
        "held" to isHeld,
        "audio_route" to audioRoute,
        "can_answer" to canAnswer(),
        "can_decline" to canDecline(),
        "can_end" to canEnd(),
        "can_hold" to canHold(),
        "can_mute" to canMute(),
        "can_send_dtmf" to canSendDtmf(),
        "updated_at_epoch_millis" to updatedAtEpochMillis,
    ).filterValues { it != null }

    private fun sourceName(source: Int): String = when (source) {
        NavigationContract.SOURCE_SEARCH -> "search"
        NavigationContract.SOURCE_SAVED_HOME -> "saved_home"
        NavigationContract.SOURCE_SAVED_WORK -> "saved_work"
        NavigationContract.SOURCE_SAVED_FAVORITE -> "saved_favorite"
        else -> "unknown"
    }

    private fun navigationState(state: Int): String = when (state) {
        NavigationContract.STATE_IDLE -> "IDLE"
        NavigationContract.STATE_CALCULATING -> "CALCULATING"
        NavigationContract.STATE_ACTIVE -> "ACTIVE"
        NavigationContract.STATE_ARRIVED -> "ARRIVED"
        else -> "ERROR"
    }
}
