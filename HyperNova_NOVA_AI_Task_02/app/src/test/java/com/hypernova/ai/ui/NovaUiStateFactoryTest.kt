package com.hypernova.ai.ui

import com.hypernova.ai.runtime.NovaRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaUiStateFactoryTest {
    @Test
    fun `idle retains the last verified response beside its evidence`() {
        val ui = NovaUiStateFactory.create(
            NovaRuntimeSnapshot(
                visibleState = NovaVisibleState.IDLE,
                transcript = "When is the match?",
                spokenText = "The match starts at 9 PM Cairo time.",
            ),
        )

        assertEquals("LAST RESPONSE", ui.eyebrow)
        assertEquals("The match starts at 9 PM Cairo time.", ui.primaryMessage)
        assertEquals("When is the match?", ui.transcript)
    }

    @Test
    fun `fresh idle without a response keeps the ready prompt`() {
        val ui = NovaUiStateFactory.create(
            NovaRuntimeSnapshot(visibleState = NovaVisibleState.IDLE),
        )

        assertEquals("VOICE READY", ui.eyebrow)
        assertEquals("Ready when you are", ui.primaryMessage)
    }

    @Test
    fun `processing card shows the real transcript`() {
        val ui = NovaUiStateFactory.create(
            NovaRuntimeSnapshot(
                visibleState = NovaVisibleState.PROCESSING,
                transcript = "Set the climate to 22 degrees",
            ),
        )

        assertEquals("I HEARD YOU", ui.eyebrow)
        assertEquals("Set the climate to 22 degrees", ui.primaryMessage)
        assertTrue(ui.canCancel)
        assertTrue(ui.showActivityProgress)
    }

    @Test
    fun `progress replaces transcript as the active task state without exposing route tier`() {
        val ui = NovaUiStateFactory.create(
            NovaRuntimeSnapshot(
                visibleState = NovaVisibleState.PROCESSING,
                transcript = "Will I reach Valeo by nine?",
                progressText = "Checking traffic and your schedule.",
                routeTier = "connected",
            ),
        )

        assertEquals("WORKING ON IT", ui.eyebrow)
        assertEquals("Checking traffic and your schedule.", ui.primaryMessage)
        assertEquals("You asked: Will I reach Valeo by nine?", ui.secondaryMessage)
        assertFalse(ui.primaryMessage.contains("connected"))
        assertTrue(ui.showActivityProgress)
    }

    @Test
    fun `follow up listening is distinct and carries its deadline`() {
        val deadline = 42_000L
        val ui = NovaUiStateFactory.create(
            NovaRuntimeSnapshot(
                visibleState = NovaVisibleState.LISTENING,
                followUpWindowMs = 5_000L,
                followUpDeadlineElapsedRealtimeMs = deadline,
            ),
        )

        assertEquals("STILL LISTENING", ui.eyebrow)
        assertEquals("Anything else?", ui.primaryMessage)
        assertEquals(deadline, ui.followUpDeadlineElapsedRealtimeMs)
    }

    @Test
    fun `executing card shows real tool result without infrastructure wording`() {
        val ui = NovaUiStateFactory.create(
            NovaRuntimeSnapshot(
                visibleState = NovaVisibleState.EXECUTING,
                transcript = "Turn on the AC",
                actionName = "set_climate",
                actionResult = "Air conditioning is on",
            ),
        )

        assertEquals("CLIMATE", ui.eyebrow)
        assertEquals("Air conditioning is on", ui.primaryMessage)
        assertEquals("Request: Turn on the AC", ui.secondaryMessage)
        assertTrue(ui.showActivityProgress)
    }

    @Test
    fun `speaking and success cards keep the real response`() {
        val snapshot = NovaRuntimeSnapshot(
            visibleState = NovaVisibleState.SPEAKING,
            spokenText = "Cabin temperature is now 22 degrees",
        )
        val speaking = NovaUiStateFactory.create(snapshot)
        val success = NovaUiStateFactory.create(
            snapshot.copy(visibleState = NovaVisibleState.SUCCESS),
        )

        assertEquals("Cabin temperature is now 22 degrees", speaking.primaryMessage)
        assertTrue(speaking.isSpeaking)
        assertEquals("Cabin temperature is now 22 degrees", success.primaryMessage)
        assertFalse(success.isSpeaking)
        assertFalse(success.showActivityProgress)
    }

    @Test
    fun `error card reports the real safe failure`() {
        val ui = NovaUiStateFactory.create(
            NovaRuntimeSnapshot(
                visibleState = NovaVisibleState.ERROR,
                errorMessage = "Opening the trunk is unavailable while driving",
                blocked = true,
            ),
        )

        assertEquals("Opening the trunk is unavailable while driving", ui.primaryMessage)
        assertEquals("No changes were made", ui.secondaryMessage)
    }
}
