package com.hypernova.ai.ui

data class NovaUiState(
    val visibleState: NovaVisibleState = NovaVisibleState.UNAVAILABLE,
    val eyebrow: String = "VOICE SESSION",
    val driverName: String? = null,
    val transcript: String? = null,
    val primaryMessage: String = "NOVA AI is unavailable",
    val secondaryMessage: String? = "Check the connection and try again",
    val isMicrophoneAvailable: Boolean = false,
    val canCancel: Boolean = false,
    val isSpeaking: Boolean = false,
)
