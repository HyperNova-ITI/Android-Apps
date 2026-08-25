package com.hypernova.ai.command

import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.climate.ClimateContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandCoordinatorTest {
    private val request = CommandRequest(
        turnId = "turn-1",
        requestId = "request-1",
        domain = CommandWireCodec.DOMAIN_CLIMATE,
        operation = ClimateContract.OP_SET_TEMPERATURE,
        arguments = CommandArguments.Temperature(ClimateContract.ZONE_ALL, 22f),
    )

    @Test
    fun `accepted is intermediate and confirmed is the only success`() {
        val fixture = Fixture()
        fixture.coordinator.submit(request)

        fixture.executor.reply(CommandStatus.ACCEPTED, "Waiting for controller")
        fixture.executor.reply(CommandStatus.CONFIRMED, "Climate set to 22°C")

        assertEquals(
            listOf(CommandStatus.ACCEPTED, CommandStatus.CONFIRMED),
            fixture.results.map { it.status },
        )
        assertEquals("Climate set to 22°C", fixture.results.last().message)
        assertTrue(fixture.scheduler.tasks.single().cancelled)
    }

    @Test
    fun `rejected and unavailable are final honest outcomes`() {
        val rejected = Fixture()
        rejected.coordinator.submit(request)
        rejected.executor.reply(
            CommandStatus.REJECTED,
            "Controller rejected the command",
            "HARDWARE_REJECTED",
        )

        val unavailable = Fixture()
        unavailable.coordinator.submit(request)
        unavailable.executor.reply(
            CommandStatus.UNAVAILABLE,
            "Climate service unavailable",
            HyperNovaContract.ERROR_SERVICE_UNAVAILABLE,
        )

        assertEquals(CommandStatus.REJECTED, rejected.results.single().status)
        assertEquals(CommandStatus.UNAVAILABLE, unavailable.results.single().status)
        assertTrue(rejected.scheduler.tasks.single().cancelled)
        assertTrue(unavailable.scheduler.tasks.single().cancelled)
    }

    @Test
    fun `timeout becomes final and ignores a late provider success`() {
        val fixture = Fixture()
        fixture.coordinator.submit(request)

        fixture.scheduler.runPending()
        fixture.executor.reply(CommandStatus.CONFIRMED, "Late controller acknowledgement")

        assertEquals(1, fixture.results.size)
        assertEquals(CommandStatus.TIMEOUT, fixture.results.single().status)
        assertEquals(HyperNovaContract.ERROR_TIMEOUT, fixture.results.single().errorCode)
    }

    @Test
    fun `duplicate final request replays cache without executing again`() {
        val fixture = Fixture()
        fixture.coordinator.submit(request)
        fixture.executor.reply(CommandStatus.CONFIRMED, "Climate set to 22°C")

        fixture.coordinator.submit(request)

        assertEquals(1, fixture.executor.executeCount)
        assertEquals(2, fixture.results.size)
        assertEquals(fixture.results.first(), fixture.results.last())
    }

    @Test
    fun `duplicate active request does not execute twice`() {
        val fixture = Fixture()
        fixture.coordinator.submit(request)
        fixture.coordinator.submit(request)

        assertEquals(1, fixture.executor.executeCount)
        assertEquals(CommandStatus.ACCEPTED, fixture.results.single().status)
        assertEquals("Request is already in progress", fixture.results.single().message)
    }

    @Test
    fun `reused request id with different command is rejected without replacing original`() {
        val fixture = Fixture()
        fixture.coordinator.submit(request)
        fixture.coordinator.submit(
            request.copy(
                operation = ClimateContract.OP_SET_FAN_LEVEL,
                arguments = CommandArguments.FanLevel(3),
            ),
        )
        fixture.executor.reply(CommandStatus.CONFIRMED, "Climate set to 22°C")

        assertEquals(1, fixture.executor.executeCount)
        assertEquals(CommandStatus.REJECTED, fixture.results.first().status)
        assertEquals(HyperNovaContract.ERROR_INVALID_ARGUMENT, fixture.results.first().errorCode)
        assertEquals(CommandStatus.CONFIRMED, fixture.results.last().status)
    }

    @Test
    fun `cancelled turn drops its timeout and every late provider callback`() {
        val fixture = Fixture()
        fixture.coordinator.submit(request)

        fixture.coordinator.cancelTurn(request.turnId)
        fixture.scheduler.runPending()
        fixture.executor.reply(CommandStatus.CONFIRMED, "Late controller acknowledgement")

        assertTrue(fixture.scheduler.tasks.single().cancelled)
        assertTrue(fixture.results.isEmpty())
    }

    private class Fixture {
        val results = mutableListOf<CommandResult>()
        val executor = FakeExecutor()
        val scheduler = FakeScheduler()
        val coordinator = CommandCoordinator(
            executor = executor,
            scheduler = scheduler,
            resultSink = results::add,
        )
    }

    private class FakeExecutor : CommandExecutor {
        var executeCount = 0
        private lateinit var request: CommandRequest
        private lateinit var callback: (CommandResult) -> Unit

        override fun execute(request: CommandRequest, onResult: (CommandResult) -> Unit) {
            executeCount += 1
            this.request = request
            callback = onResult
        }

        override fun shutdown() = Unit

        fun reply(status: CommandStatus, message: String, errorCode: String? = null) {
            callback(CommandResult(request, status, message, errorCode))
        }
    }

    private class FakeScheduler : CommandScheduler {
        data class Task(val action: () -> Unit, var cancelled: Boolean = false)

        val tasks = mutableListOf<Task>()

        override fun schedule(delayMillis: Long, action: () -> Unit): Cancelable {
            val task = Task(action)
            tasks += task
            return Cancelable { task.cancelled = true }
        }

        fun runPending() {
            tasks.filterNot(Task::cancelled).forEach { it.action() }
        }
    }
}
