package com.hypernova.ai.ui

import com.hypernova.ai.runtime.NovaEvidenceCard

data class NovaUiState(
    val visibleState: NovaVisibleState = NovaVisibleState.UNAVAILABLE,
    val eyebrow: String = "VOICE SESSION",
    val driverName: String? = null,
    val transcript: String? = null,
    val primaryMessage: String = "NOVA AI is unavailable",
    val secondaryMessage: String? = "Check the connection and try again",
    val followUpDeadlineElapsedRealtimeMs: Long? = null,
    val isMicrophoneAvailable: Boolean = false,
    val canCancel: Boolean = false,
    val isSpeaking: Boolean = false,
    val showActivityProgress: Boolean = false,
    val evidenceCards: List<NovaEvidenceCard> = emptyList(),
)
