package com.hypernova.ai.status

import com.hypernova.ai.runtime.NovaRuntimeSnapshot
import com.hypernova.ai.ui.NovaUiStateFactory
import org.json.JSONArray
import org.json.JSONObject

/**
 * Encodes the trusted, read-only launcher presentation contract.
 *
 * This is deliberately not the Pi control protocol. It contains only bounded, driver-facing text
 * and presentation hints; application state remains owned by the launcher app clients.
 */
object NovaPresentationSnapshotCodec {
    const val SCHEMA_VERSION = 1

    fun encode(snapshot: NovaRuntimeSnapshot, muted: Boolean = false, deafened: Boolean = false): String {
        val ui = NovaUiStateFactory.create(snapshot)
        return JSONObject().apply {
            put("schema_version", SCHEMA_VERSION)
            put("visible_state", ui.visibleState.name)
            putOptionalText("turn_id", snapshot.turnId, 128)
            putOptionalText("eyebrow", ui.eyebrow, 64)
            putOptionalText("transcript", ui.transcript, 320)
            putOptionalText("primary_message", ui.primaryMessage, 1_200)
            putOptionalText("secondary_message", ui.secondaryMessage, 480)
            putOptionalText("action_domain", snapshot.actionDomain, 32)
            putOptionalText("action_name", snapshot.actionName, 64)
            put("blocked", snapshot.blocked)
            put("speaking", ui.isSpeaking)
            put("activity_progress", ui.showActivityProgress)
            put("muted", muted)
            put("deafened", deafened)
            if (snapshot.evidenceCards.isNotEmpty()) {
                put("evidence_cards", JSONArray().apply {
                    snapshot.evidenceCards.take(4).forEach { card ->
                        put(JSONObject().apply {
                            put("index", card.index.coerceIn(1, 4))
                            putOptionalText("title", card.title, 120)
                            putOptionalText("detail", card.detail, 180)
                            putOptionalText("source", card.source, 40)
                            putOptionalText("source_uri", card.sourceUri, 1_000)
                        })
                    }
                })
            }
        }.toString()
    }

    private fun JSONObject.putOptionalText(name: String, value: String?, maximum: Int) {
        value?.trim()?.takeIf(String::isNotEmpty)?.let { put(name, it.take(maximum)) }
    }
}
