package com.hypernova.launcher.core.assistant

enum class NovaServiceConnection {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

data class NovaEvidenceCard(
    val index: Int,
    val title: String,
    val detail: String? = null,
    val source: String,
    val sourceUri: String? = null,
)

data class NovaStatusSnapshot(
    val connection: NovaServiceConnection,
    val state: String? = null,
    val turnId: String? = null,
    val eyebrow: String? = null,
    val transcript: String? = null,
    val primaryMessage: String? = null,
    val secondaryMessage: String? = null,
    val actionDomain: String? = null,
    val actionName: String? = null,
    val blocked: Boolean = false,
    val speaking: Boolean = false,
    val showActivityProgress: Boolean = false,
    val muted: Boolean = false,
    val deafened: Boolean = false,
    val evidenceCards: List<NovaEvidenceCard> = emptyList(),
)
