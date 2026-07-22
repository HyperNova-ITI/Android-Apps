package com.hypernova.ai.ui

import com.hypernova.ai.runtime.NovaRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaUiStateFactoryTest {
    @Test
    fun `processing card shows the real transcript`() {
        val ui = NovaUiStateFactory.create(
            NovaRuntimeSnapshot(
                visibleState = NovaVisibleState.PROCESSING,
                transcript = "Set the climate to 22 degrees",
            ),
        )

        assertEquals("YOU SAID", ui.eyebrow)
        assertEquals("Set the climate to 22 degrees", ui.primaryMessage)
        assertTrue(ui.canCancel)
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
