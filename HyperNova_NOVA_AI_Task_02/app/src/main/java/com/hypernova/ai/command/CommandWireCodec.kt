package com.hypernova.ai.command

import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.climate.ClimateContract
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.contracts.phone.PhoneContract
import org.json.JSONArray
import org.json.JSONObject

class CommandParseException(message: String) : IllegalArgumentException(message)

object CommandWireCodec {
    fun parseRequest(message: JSONObject): CommandRequest {
        if (message.optString("type") != "command_request") {
            throw CommandParseException("Expected command_request")
        }
        if (message.optInt("v", -1) != HyperNovaContract.API_VERSION) {
            throw CommandParseException("Unsupported command protocol version")
        }

        val requestId = message.requiredText("request_id")
        val turnId = message.optionalText("turn_id")
        val domain = message.requiredText("domain").lowercase()
        val operation = message.requiredText("operation").lowercase()
        val args = message.optJSONObject("args") ?: JSONObject()

        val arguments = when (domain) {
            DOMAIN_NAVIGATION -> parseNavigation(operation, args)
            DOMAIN_CLIMATE -> parseClimate(operation, args)
            DOMAIN_PHONE -> parsePhone(operation, args)
            DOMAIN_MEDIA -> parseMedia(operation, args)
            else -> throw CommandParseException("Unsupported command domain: $domain")
        }

        return CommandRequest(
            turnId = turnId,
            requestId = requestId,
            domain = domain,
            operation = operation,
            arguments = arguments,
        )
    }

    fun invalidRequest(message: JSONObject, error: Exception): CommandResult {
        val request = CommandRequest(
            turnId = message.optionalText("turn_id"),
            requestId = message.optionalText("request_id")
                ?: "invalid-${message.optLong("seq", System.currentTimeMillis())}",
            domain = message.optionalText("domain") ?: "unknown",
            operation = message.optionalText("operation") ?: "unknown",
            arguments = CommandArguments.None,
        )
        return request.failure(
            status = CommandStatus.REJECTED,
            message = error.message ?: "Invalid command request",
            errorCode = HyperNovaContract.ERROR_INVALID_ARGUMENT,
        )
    }

    fun toJson(result: CommandResult): JSONObject = JSONObject().apply {
        put("type", "command_result")
        put("v", HyperNovaContract.API_VERSION)
        result.request.turnId?.let { put("turn_id", it) }
        put("request_id", result.request.requestId)
        put("domain", result.request.domain)
        put("operation", result.request.operation)
        put("status", result.status.wireValue)
        put("message", result.message)
        result.errorCode?.takeIf(String::isNotBlank)?.let { put("error_code", it) }
        put("data", result.data.toJsonObject())
    }

    private fun parseNavigation(operation: String, args: JSONObject): CommandArguments =
        when (operation) {
            NavigationContract.OP_SEARCH_DESTINATIONS ->
                CommandArguments.Search(args.requiredText("query"))

            NavigationContract.OP_GET_SAVED_DESTINATIONS,
            NavigationContract.OP_START_NAVIGATION,
            NavigationContract.OP_CANCEL_NAVIGATION,
            -> CommandArguments.None

            NavigationContract.OP_SET_DESTINATION ->
                CommandArguments.Destination(args.requiredText("destination_id"))

            NavigationContract.OP_GET_DESTINATION_CONTACT ->
                CommandArguments.Destination(args.requiredText("destination_id"))

            else -> throw CommandParseException("Unsupported Navigation operation: $operation")
        }

    private fun parseClimate(operation: String, args: JSONObject): CommandArguments =
        when (operation) {
            ClimateContract.OP_GET_CAPABILITIES,
            ClimateContract.OP_GET_CURRENT_STATE,
            -> CommandArguments.None

            ClimateContract.OP_SET_POWER,
            ClimateContract.OP_SET_AC,
            ClimateContract.OP_SET_AUTO,
            ClimateContract.OP_SET_RECIRCULATION,
            -> CommandArguments.Enabled(args.requiredBoolean("enabled"))

            ClimateContract.OP_SET_TEMPERATURE -> CommandArguments.Temperature(
                zone = args.requiredZone("zone"),
                temperatureC = args.requiredFloat("temperature_c"),
            )

            ClimateContract.OP_SET_FAN_LEVEL ->
                CommandArguments.FanLevel(args.requiredInt("fan_level"))

            else -> throw CommandParseException("Unsupported Climate operation: $operation")
        }

    private fun parsePhone(operation: String, args: JSONObject): CommandArguments =
        when (operation) {
            PhoneContract.OP_GET_CURRENT_STATE,
            PhoneContract.OP_ANSWER_CALL,
            PhoneContract.OP_DECLINE_CALL,
            PhoneContract.OP_END_CALL,
            -> CommandArguments.None

            PhoneContract.OP_SEARCH_CONTACTS -> CommandArguments.ContactSearch(
                query = args.requiredText("query"),
                limit = args.optionalInt("limit")
                    ?.coerceIn(1, PhoneContract.MAX_CONTACT_RESULT_LIMIT)
                    ?: PhoneContract.DEFAULT_CONTACT_RESULT_LIMIT,
            )

            PhoneContract.OP_GET_CONTACT ->
                CommandArguments.Contact(args.requiredText("contact_id"))

            PhoneContract.OP_GET_CALL_HISTORY -> CommandArguments.CallHistory(
                contactId = null,
                filter = args.optionalInt("filter") ?: PhoneContract.HISTORY_FILTER_ALL,
                limit = args.optionalInt("limit")
                    ?.coerceIn(1, PhoneContract.MAX_CALL_HISTORY_LIMIT)
                    ?: PhoneContract.DEFAULT_CALL_HISTORY_LIMIT,
            )

            PhoneContract.OP_GET_CONTACT_CALL_HISTORY -> CommandArguments.CallHistory(
                contactId = args.requiredText("contact_id"),
                filter = args.optionalInt("filter") ?: PhoneContract.HISTORY_FILTER_ALL,
                limit = args.optionalInt("limit")
                    ?.coerceIn(1, PhoneContract.MAX_CALL_HISTORY_LIMIT)
                    ?: PhoneContract.DEFAULT_CALL_HISTORY_LIMIT,
            )

            PhoneContract.OP_CALL_CONTACT -> CommandArguments.ContactCall(
                contactId = args.requiredText("contact_id"),
                numberId = args.optionalText("number_id"),
            )

            PhoneContract.OP_CALL_NUMBER ->
                CommandArguments.PhoneNumber(args.requiredText("phone_number"))

            PhoneContract.OP_CALL_HISTORY_ENTRY ->
                CommandArguments.CallHistoryEntry(args.requiredText("call_id"))

            PhoneContract.OP_SET_MUTED,
            PhoneContract.OP_SET_HELD,
            -> CommandArguments.Enabled(args.requiredBoolean("enabled"))

            PhoneContract.OP_SET_AUDIO_ROUTE ->
                CommandArguments.AudioRoute(args.requiredInt("route"))

            PhoneContract.OP_SEND_DTMF ->
                CommandArguments.Dtmf(args.requiredText("digit"))

            else -> throw CommandParseException("Unsupported Phone operation: $operation")
        }

    private fun parseMedia(operation: String, args: JSONObject): CommandArguments =
        when (operation) {
            MediaWireContract.OP_GET_CURRENT_STATE,
            MediaWireContract.OP_PLAY,
            MediaWireContract.OP_PAUSE,
            MediaWireContract.OP_NEXT,
            MediaWireContract.OP_PREVIOUS,
            -> CommandArguments.None

            MediaWireContract.OP_SET_VOLUME -> {
                val percent = args.requiredInt("volume_percent")
                if (percent !in 0..100) {
                    throw CommandParseException("volume_percent must be between 0 and 100")
                }
                CommandArguments.VolumePercent(percent)
            }

            MediaWireContract.OP_PLAY_RADIO ->
                CommandArguments.RadioQuery(args.requiredText("query"))

            else -> throw CommandParseException("Unsupported Media operation: $operation")
        }

    private fun JSONObject.requiredText(name: String): String =
        optionalText(name) ?: throw CommandParseException("Missing or blank $name")

    private fun JSONObject.optionalText(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).trim().takeIf(String::isNotEmpty)
    }

    private fun JSONObject.requiredBoolean(name: String): Boolean {
        if (!has(name) || isNull(name) || get(name) !is Boolean) {
            throw CommandParseException("$name must be boolean")
        }
        return getBoolean(name)
    }

    private fun JSONObject.requiredInt(name: String): Int {
        if (!has(name) || isNull(name) || get(name) !is Number) {
            throw CommandParseException("$name must be an integer")
        }
        val number = getDouble(name)
        if (!number.isFinite() || number % 1.0 != 0.0) {
            throw CommandParseException("$name must be an integer")
        }
        return number.toInt()
    }

    private fun JSONObject.optionalInt(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        if (get(name) !is Number) throw CommandParseException("$name must be an integer")
        val number = getDouble(name)
        if (!number.isFinite() || number % 1.0 != 0.0) {
            throw CommandParseException("$name must be an integer")
        }
        return number.toInt()
    }

    private fun JSONObject.requiredFloat(name: String): Float {
        if (!has(name) || isNull(name) || get(name) !is Number) {
            throw CommandParseException("$name must be numeric")
        }
        return getDouble(name).toFloat().also {
            if (!it.isFinite()) throw CommandParseException("$name must be finite")
        }
    }

    private fun JSONObject.requiredZone(name: String): Int {
        if (!has(name) || isNull(name)) throw CommandParseException("Missing $name")
        return when (val value = get(name)) {
            is Number -> value.toInt()
            is String -> when (value.lowercase()) {
                "all" -> ClimateContract.ZONE_ALL
                "driver" -> ClimateContract.ZONE_DRIVER
                "passenger" -> ClimateContract.ZONE_PASSENGER
                else -> throw CommandParseException("Unsupported zone: $value")
            }
            else -> throw CommandParseException("$name must be a zone name or integer")
        }
    }

    private fun Map<String, Any?>.toJsonObject(): JSONObject = JSONObject().also { json ->
        forEach { (key, value) -> json.put(key, value.toJsonValue()) }
    }

    private fun Any?.toJsonValue(): Any = when (this) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { nested ->
            forEach { (key, value) ->
                if (key is String) nested.put(key, value.toJsonValue())
            }
        }
        is Iterable<*> -> JSONArray().also { array -> forEach { array.put(it.toJsonValue()) } }
        else -> this
    }

    const val DOMAIN_NAVIGATION = "navigation"
    const val DOMAIN_CLIMATE = "climate"
    const val DOMAIN_PHONE = "phone"
    const val DOMAIN_MEDIA = "media"
}
