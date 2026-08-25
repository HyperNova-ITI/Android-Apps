package com.hypernova.launcher.core.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NovaStatusSnapshotCodecTest {
    @Test
    fun `decodes the bounded launcher presentation snapshot`() {
        val result = NovaStatusSnapshotCodec.decode(
            """{"schema_version":1,"visible_state":"SPEAKING","turn_id":"t-1","primary_message":"Destination set","action_domain":"navigation","action_name":"set_destination","speaking":true,"muted":true,"deafened":true}""",
        )

        assertEquals(NovaServiceConnection.CONNECTED, result.connection)
        assertEquals("Destination set", result.primaryMessage)
        assertEquals("navigation", result.actionDomain)
        assertEquals("set_destination", result.actionName)
        assertEquals(true, result.speaking)
        assertEquals(true, result.muted)
        assertEquals(true, result.deafened)
        assertFalse(result.blocked)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an unsupported schema`() {
        NovaStatusSnapshotCodec.decode("""{"schema_version":2,"visible_state":"IDLE"}""")
    }

    @Test
    fun `decodes optional maps evidence without changing schema one`() {
        val result = NovaStatusSnapshotCodec.decode(
            """{"schema_version":1,"visible_state":"SPEAKING","evidence_cards":[{"index":1,"title":"Cairo Service Center","detail":"Nasr City","source":"Google Maps","source_uri":"https://maps.google.com/?cid=1"}]}""",
        )

        assertEquals(1, result.evidenceCards.size)
        assertEquals("Cairo Service Center", result.evidenceCards[0].title)
        assertEquals("Google Maps", result.evidenceCards[0].source)
    }
}
