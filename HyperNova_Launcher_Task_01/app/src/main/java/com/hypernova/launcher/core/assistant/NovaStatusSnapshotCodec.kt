package com.hypernova.launcher.core.assistant

import org.json.JSONObject

/** Strict parser for NOVA's private, read-only launcher presentation stream. */
object NovaStatusSnapshotCodec {
    private const val SUPPORTED_SCHEMA_VERSION = 1

    fun decode(json: String): NovaStatusSnapshot {
        val value = JSONObject(json)
        require(value.getInt("schema_version") == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported NOVA presentation schema"
        }
        return NovaStatusSnapshot(
            connection = NovaServiceConnection.CONNECTED,
            state = value.requiredText("visible_state", 32),
            turnId = value.optionalText("turn_id", 128),
            eyebrow = value.optionalText("eyebrow", 64),
            transcript = value.optionalText("transcript", 320),
            primaryMessage = value.optionalText("primary_message", 1_200),
            secondaryMessage = value.optionalText("secondary_message", 480),
            actionDomain = value.optionalText("action_domain", 32),
            actionName = value.optionalText("action_name", 64),
            blocked = value.optBoolean("blocked", false),
            speaking = value.optBoolean("speaking", false),
            showActivityProgress = value.optBoolean("activity_progress", false),
            muted = value.optBoolean("muted", false),
            deafened = value.optBoolean("deafened", false),
            evidenceCards = value.parseEvidenceCards(),
        )
    }

    private fun JSONObject.requiredText(name: String, maximum: Int): String =
        getString(name).trim().also {
            require(it.isNotEmpty() && it.length <= maximum) { "Invalid $name" }
        }

    private fun JSONObject.optionalText(name: String, maximum: Int): String? =
        optString(name).trim().takeIf { it.isNotEmpty() }?.also {
            require(it.length <= maximum) { "Invalid $name" }
        }

    private fun JSONObject.parseEvidenceCards(): List<NovaEvidenceCard> {
        val cards = optJSONArray("evidence_cards") ?: return emptyList()
        require(cards.length() <= 4) { "Too many NOVA evidence cards" }
        return buildList {
            for (position in 0 until cards.length()) {
                val card = cards.getJSONObject(position)
                val sourceUri = card.optionalText("source_uri", 1_000)
                require(sourceUri == null || sourceUri.startsWith("https://")) {
                    "Invalid NOVA evidence source URI"
                }
                add(
                    NovaEvidenceCard(
                        index = card.optInt("index", position + 1).also {
                            require(it in 1..4) { "Invalid NOVA evidence index" }
                        },
                        title = card.requiredText("title", 120),
                        detail = card.optionalText("detail", 180),
                        source = card.requiredText("source", 40),
                        sourceUri = sourceUri,
                    ),
                )
            }
        }
    }
}
