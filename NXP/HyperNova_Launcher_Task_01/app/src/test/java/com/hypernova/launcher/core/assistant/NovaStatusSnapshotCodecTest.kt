package com.hypernova.launcher.core.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NovaStatusSnapshotCodecTest {
    @Test
    fun `decodes the bounded launcher presentation snapshot`() {
        val result = NovaStatusSnapshotCodec.decode(
            """{"schema_version":1,"visible_state":"SPEAKING","turn_id":"t-1","primary_message":"Destination set","action_domain":"navigation","action_name":"set_destination","speaking":true}""",
        )

        assertEquals(NovaServiceConnection.CONNECTED, result.connection)
        assertEquals("Destination set", result.primaryMessage)
        assertEquals("navigation", result.actionDomain)
        assertEquals("set_destination", result.actionName)
        assertEquals(true, result.speaking)
        assertFalse(result.blocked)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an unsupported schema`() {
        NovaStatusSnapshotCodec.decode("""{"schema_version":2,"visible_state":"IDLE"}""")
    }
}
