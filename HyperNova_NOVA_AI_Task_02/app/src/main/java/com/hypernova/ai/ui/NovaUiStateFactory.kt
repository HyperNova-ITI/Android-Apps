package com.hypernova.ai.ui

import com.hypernova.ai.runtime.NovaRuntimeSnapshot
import java.util.Locale

/** Converts real runtime data into concise, driver-facing state cards. */
object NovaUiStateFactory {
    fun create(snapshot: NovaRuntimeSnapshot): NovaUiState = when (snapshot.visibleState) {
        NovaVisibleState.IDLE -> NovaUiState(
            visibleState = snapshot.visibleState,
            eyebrow = "VOICE READY",
            primaryMessage = "Ready when you are",
            secondaryMessage = "Say “Hey NOVA” to begin",
        )
        NovaVisibleState.LISTENING -> NovaUiState(
            visibleState = snapshot.visibleState,
            eyebrow = "LISTENING",
            primaryMessage = "I’m listening…",
            secondaryMessage = "Speak naturally",
            canCancel = true,
        )
        NovaVisibleState.PROCESSING -> NovaUiState(
            visibleState = snapshot.visibleState,
            eyebrow = if (snapshot.transcript != null) "YOU SAID" else "PROCESSING",
            transcript = snapshot.transcript,
            primaryMessage = snapshot.transcript ?: "Understanding your request…",
            secondaryMessage = "Understanding your request",
            canCancel = true,
        )
        NovaVisibleState.EXECUTING -> NovaUiState(
            visibleState = snapshot.visibleState,
            eyebrow = actionLabel(snapshot.actionName),
            transcript = snapshot.transcript,
            primaryMessage = snapshot.actionResult ?: "Completing your request…",
            secondaryMessage = snapshot.transcript?.let { "Request: $it" }
                ?: "Applying the requested change",
            canCancel = true,
        )
        NovaVisibleState.SUCCESS -> NovaUiState(
            visibleState = snapshot.visibleState,
            eyebrow = "COMMAND COMPLETED",
            transcript = snapshot.transcript,
            primaryMessage = snapshot.spokenText
                ?: snapshot.actionResult
                ?: "Command completed",
            secondaryMessage = "Your request is complete",
        )
        NovaVisibleState.ERROR -> NovaUiState(
            visibleState = snapshot.visibleState,
            eyebrow = "UNABLE TO COMPLETE",
            transcript = snapshot.transcript,
            primaryMessage = snapshot.errorMessage ?: "Unable to complete the command",
            secondaryMessage = "No changes were made",
        )
        NovaVisibleState.SPEAKING -> NovaUiState(
            visibleState = snapshot.visibleState,
            eyebrow = "NOVA RESPONSE",
            transcript = snapshot.transcript,
            primaryMessage = snapshot.spokenText
                ?: snapshot.actionResult
                ?: "NOVA is responding…",
            secondaryMessage = "NOVA is speaking",
            isSpeaking = true,
            canCancel = true,
        )
        NovaVisibleState.UNAVAILABLE -> NovaUiState()
    }

    private fun actionLabel(actionName: String?): String {
        if (actionName.isNullOrBlank()) return "EXECUTING"
        return actionName
            .removePrefix("set_")
            .replace('_', ' ')
            .uppercase(Locale.ROOT)
    }
}
