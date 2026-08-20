package com.hypernova.navigation.navigation

import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigatorReadinessGateTest {
    private val failure = NavigatorInitializationFailure.NETWORK

    @Test
    fun awaitingGateIsPendingWhileWaiting() = runTest {
        val gate = NavigatorReadinessGate()
        var resolved: NavigatorReadinessGate.State? = null
        val job = launch { resolved = gate.await() }

        runCurrent()

        assertNull(resolved)
        gate.ready()
        job.join()
        assertEquals(NavigatorReadinessGate.State.Ready, resolved)
    }

    @Test
    fun awaitingReadyGateResolvesImmediately() = runTest {
        val gate = NavigatorReadinessGate(NavigatorReadinessGate.State.Ready)

        assertEquals(NavigatorReadinessGate.State.Ready, gate.await())
    }

    @Test
    fun awaitingTerminalGateResolvesImmediately() = runTest {
        val gate = NavigatorReadinessGate(NavigatorReadinessGate.State.TerminalFailure(failure))

        assertEquals(NavigatorReadinessGate.State.TerminalFailure(failure), gate.await())
    }

    @Test
    fun terminalFailureResolvesPendingWaiters() = runTest {
        val gate = NavigatorReadinessGate()
        var resolved: NavigatorReadinessGate.State? = null
        val job = launch { resolved = gate.await() }

        runCurrent()
        gate.terminal(failure)
        job.join()

        assertEquals(NavigatorReadinessGate.State.TerminalFailure(failure), resolved)
    }

    @Test
    fun waitingResetsTerminalFailureBackToPending() = runTest {
        val gate = NavigatorReadinessGate(NavigatorReadinessGate.State.TerminalFailure(failure))

        gate.waiting()

        var resolved: NavigatorReadinessGate.State? = null
        val job = launch { resolved = gate.await() }
        runCurrent()
        assertNull(resolved)

        gate.ready()
        job.join()
        assertEquals(NavigatorReadinessGate.State.Ready, resolved)
    }

    @Test
    fun readyStateIsTerminalAndDrainsFurtherStateChanges() = runTest {
        val gate = NavigatorReadinessGate()

        gate.ready()
        gate.terminal(failure)
        gate.waiting()

        assertEquals(NavigatorReadinessGate.State.Ready, gate.await())
    }

    @Test
    fun terminalFailureIsReplacedByAReadySignal() = runTest {
        val gate = NavigatorReadinessGate(NavigatorReadinessGate.State.TerminalFailure(failure))

        gate.ready()

        assertEquals(NavigatorReadinessGate.State.Ready, gate.await())
    }
}
