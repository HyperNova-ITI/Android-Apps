package com.hypernova.ai.status

import com.hypernova.ai.runtime.NovaRuntimeSnapshot
import com.hypernova.ai.runtime.NovaEvidenceCard
import com.hypernova.ai.ui.NovaVisibleState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NovaPresentationSnapshotCodecTest {
    @Test
    fun `launcher snapshot carries real response and action domain`() {
        val encoded = NovaPresentationSnapshotCodec.encode(
            NovaRuntimeSnapshot(
                visibleState = NovaVisibleState.SPEAKING,
                turnId = "turn-7",
                transcript = "Take me home",
                spokenText = "Home is set as your destination",
                actionDomain = "navigation",
                actionName = "set_destination",
            ),
            muted = true,
            deafened = true,
        )

        val value = JSONObject(encoded)
        assertEquals(1, value.getInt("schema_version"))
        assertEquals("SPEAKING", value.getString("visible_state"))
        assertEquals("Home is set as your destination", value.getString("primary_message"))
        assertEquals("navigation", value.getString("action_domain"))
        assertEquals("set_destination", value.getString("action_name"))
        assertEquals(true, value.getBoolean("muted"))
        assertEquals(true, value.getBoolean("deafened"))
    }

    @Test
    fun `launcher snapshot excludes absent internal fields`() {
        val value = JSONObject(NovaPresentationSnapshotCodec.encode(NovaRuntimeSnapshot()))

        assertFalse(value.has("turn_id"))
        assertFalse(value.has("route_tier"))
    }

    @Test
    fun `launcher snapshot carries bounded maps evidence cards`() {
        val value = JSONObject(NovaPresentationSnapshotCodec.encode(
            NovaRuntimeSnapshot(
                evidenceCards = listOf(
                    NovaEvidenceCard(
                        index = 1,
                        title = "Cairo Service Center",
                        detail = "Nasr City",
                        source = "Google Maps",
                        sourceUri = "https://maps.google.com/?cid=1",
                    ),
                ),
            ),
        ))

        val card = value.getJSONArray("evidence_cards").getJSONObject(0)
        assertEquals("Cairo Service Center", card.getString("title"))
        assertEquals("Google Maps", card.getString("source"))
    }
}
