package com.hypernova.ai.command

import com.hypernova.contracts.HyperNovaContract

sealed interface CommandArguments {
    data object None : CommandArguments
    data class Search(val query: String) : CommandArguments
    data class Destination(val destinationId: String) : CommandArguments
    data class Enabled(val enabled: Boolean) : CommandArguments
    data class Temperature(val zone: Int, val temperatureC: Float) : CommandArguments
    data class FanLevel(val level: Int) : CommandArguments
    data class ContactSearch(val query: String, val limit: Int) : CommandArguments
    data class Contact(val contactId: String) : CommandArguments
    data class ContactCall(val contactId: String, val numberId: String?) : CommandArguments
    data class PhoneNumber(val phoneNumber: String) : CommandArguments
    data class CallHistory(val contactId: String?, val filter: Int, val limit: Int) : CommandArguments
    data class CallHistoryEntry(val callId: String) : CommandArguments
    data class AudioRoute(val route: Int) : CommandArguments
    data class Dtmf(val digit: String) : CommandArguments
    data class VolumePercent(val percent: Int) : CommandArguments
}

data class CommandRequest(
    val turnId: String?,
    val requestId: String,
    val domain: String,
    val operation: String,
    val arguments: CommandArguments,
)

enum class CommandStatus(
    val wireValue: String,
    val isFinal: Boolean,
) {
    ACCEPTED("accepted", false),
    CONFIRMED("confirmed", true),
    REJECTED("rejected", true),
    UNAVAILABLE("unavailable", true),
    TIMEOUT("timeout", true),
    CANCELLED("cancelled", true);

    companion object {
        fun fromContract(value: Int): CommandStatus? = when (value) {
            HyperNovaContract.STATUS_ACCEPTED -> ACCEPTED
            HyperNovaContract.STATUS_CONFIRMED -> CONFIRMED
            HyperNovaContract.STATUS_REJECTED -> REJECTED
            HyperNovaContract.STATUS_UNAVAILABLE -> UNAVAILABLE
            HyperNovaContract.STATUS_TIMEOUT -> TIMEOUT
            HyperNovaContract.STATUS_CANCELLED -> CANCELLED
            else -> null
        }
    }
}

data class CommandResult(
    val request: CommandRequest,
    val status: CommandStatus,
    val message: String,
    val errorCode: String? = null,
    val data: Map<String, Any?> = emptyMap(),
)

fun CommandRequest.failure(
    status: CommandStatus,
    message: String,
    errorCode: String,
): CommandResult = CommandResult(
    request = this,
    status = status,
    message = message,
    errorCode = errorCode,
)
