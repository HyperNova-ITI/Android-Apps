package com.hypernova.launcher.core.assistant

import com.hypernova.launcher.core.state.AssistantRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Test

class NovaAssistantStateParserTest {
    @Test
    fun `parses every supported NOVA state case-insensitively`() {
        AssistantRuntimeState.entries.forEach { state ->
            assertEquals(state, NovaAssistantStateParser.parse(state.name.lowercase()))
        }
    }

    @Test
    fun `unknown or absent state is safely unavailable`() {
        assertEquals(AssistantRuntimeState.UNAVAILABLE, NovaAssistantStateParser.parse("future-state"))
        assertEquals(AssistantRuntimeState.UNAVAILABLE, NovaAssistantStateParser.parse(null))
    }
}
