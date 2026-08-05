package com.hypernova.ai.command

import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.climate.ClimateContract
import com.hypernova.contracts.navigation.NavigationContract

fun interface Cancelable {
    fun cancel()

    companion object {
        val NONE = Cancelable {}
    }
}

fun interface CommandScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): Cancelable
}

interface CommandExecutor {
    fun execute(request: CommandRequest, onResult: (CommandResult) -> Unit)
    fun shutdown()
}

/**
 * Owns request correlation, timeouts, and broker-side duplicate suppression.
 *
 * Destination services still keep their contract-required ten-minute caches. This second cache
 * prevents a replayed Pi message from crossing Binder again at all.
 */
class CommandCoordinator(
    private val executor: CommandExecutor,
    private val scheduler: CommandScheduler,
    private val resultSink: (CommandResult) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val dedupTtlMillis: Long = HyperNovaContract.REQUEST_DEDUP_TTL_MILLIS,
) {
    private data class ActiveRequest(
        val request: CommandRequest,
        var latest: CommandResult? = null,
        var timeout: Cancelable = Cancelable.NONE,
    )

    private data class CompletedRequest(
        val result: CommandResult,
        val expiresAtMillis: Long,
    )

    private val active = mutableMapOf<String, ActiveRequest>()
    private val completed = mutableMapOf<String, CompletedRequest>()

    fun submit(request: CommandRequest) {
        val replay = synchronized(this) {
            pruneCompleted()
            val cached = completed[request.requestId]
            if (cached != null) {
                return@synchronized if (sameCommand(cached.result.request, request)) {
                    cached.result
                } else {
                    request.requestIdConflict()
                }
            }

            val running = active[request.requestId]
            if (running != null) {
                return@synchronized if (sameCommand(running.request, request)) {
                    running.latest ?: CommandResult(
                        request = request,
                        status = CommandStatus.ACCEPTED,
                        message = "Request is already in progress",
                    )
                } else {
                    request.requestIdConflict()
                }
            }

            active[request.requestId] = ActiveRequest(request)
            null
        }

        if (replay != null) {
            resultSink(replay)
            return
        }

        val timeout = scheduler.schedule(timeoutFor(request)) {
            finish(
                request.failure(
                    status = CommandStatus.TIMEOUT,
                    message = "${request.operation.replace('_', ' ')} timed out",
                    errorCode = HyperNovaContract.ERROR_TIMEOUT,
                ),
            )
        }
        synchronized(this) {
            active[request.requestId]?.timeout = timeout
        }

        try {
            executor.execute(request, ::finish)
        } catch (_: Exception) {
            finish(
                request.failure(
                    status = CommandStatus.UNAVAILABLE,
                    message = "${request.domain.replaceFirstChar(Char::uppercase)} service is unavailable",
                    errorCode = HyperNovaContract.ERROR_SERVICE_UNAVAILABLE,
                ),
            )
        }
    }

    fun shutdown() {
        val timeouts = synchronized(this) {
            val values = active.values.map { it.timeout }
            active.clear()
            completed.clear()
            values
        }
        timeouts.forEach(Cancelable::cancel)
        executor.shutdown()
    }

    private fun finish(result: CommandResult) {
        val shouldPublish = synchronized(this) {
            val running = active[result.request.requestId] ?: return@synchronized false
            if (!sameCommand(running.request, result.request)) return@synchronized false

            if (result.status.isFinal) {
                active.remove(result.request.requestId)
                running.timeout.cancel()
                completed[result.request.requestId] = CompletedRequest(
                    result = result,
                    expiresAtMillis = nowMillis() + dedupTtlMillis,
                )
            } else {
                running.latest = result
            }
            true
        }
        if (shouldPublish) resultSink(result)
    }

    private fun pruneCompleted() {
        val now = nowMillis()
        completed.entries.removeAll { it.value.expiresAtMillis <= now }
    }

    private fun timeoutFor(request: CommandRequest): Long = when (request.operation) {
        ClimateContract.OP_GET_CAPABILITIES,
        ClimateContract.OP_GET_CURRENT_STATE,
        -> ClimateContract.QUERY_TIMEOUT_MILLIS

        ClimateContract.OP_SET_POWER,
        ClimateContract.OP_SET_TEMPERATURE,
        ClimateContract.OP_SET_FAN_LEVEL,
        ClimateContract.OP_SET_AC,
        ClimateContract.OP_SET_AUTO,
        ClimateContract.OP_SET_RECIRCULATION,
        -> ClimateContract.COMMAND_TIMEOUT_MILLIS

        NavigationContract.OP_SET_DESTINATION,
        NavigationContract.OP_CANCEL_NAVIGATION,
        -> NavigationContract.ROUTE_TIMEOUT_MILLIS

        else -> NavigationContract.SEARCH_TIMEOUT_MILLIS
    }

    private fun sameCommand(first: CommandRequest, second: CommandRequest): Boolean =
        first.domain == second.domain &&
            first.operation == second.operation &&
            first.arguments == second.arguments

    private fun CommandRequest.requestIdConflict() = failure(
        status = CommandStatus.REJECTED,
        message = "request_id was already used for a different command",
        errorCode = HyperNovaContract.ERROR_INVALID_ARGUMENT,
    )
}
