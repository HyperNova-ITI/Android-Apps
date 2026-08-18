package com.hypernova.ai.runtime

enum class NovaMessageRole {
    DRIVER,
    NOVA,
}

enum class NovaMessageTone {
    NEUTRAL,
    SUCCESS,
    ERROR,
}

/**
 * The structured half of a NOVA turn: what was actually done, and where the driver can go to see
 * it. This is the same idea as the launcher's context card, built from the action NOVA reports
 * rather than from the launcher's own view of the cockpit.
 */
data class NovaActionCard(
    val domain: String,
    val label: String,
    val title: String,
)

/**
 * One line of the conversation.
 *
 * [key] identifies the turn and side, so a repeated report about the same turn updates the
 * existing line instead of adding another. A proactive vehicle alert has no turn of its own and is
 * keyed by its fault code, which is what stops a re-sent fault from printing again and again.
 */
data class NovaMessage(
    val key: String,
    val role: NovaMessageRole,
    val text: String,
    val tone: NovaMessageTone = NovaMessageTone.NEUTRAL,
    val pending: Boolean = false,
    val action: NovaActionCard? = null,
    val evidence: List<NovaEvidenceCard> = emptyList(),
)
