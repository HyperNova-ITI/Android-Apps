package com.hypernova.ai.ui

import com.hypernova.ai.runtime.NovaRuntimeSnapshot
import java.util.Locale

/** Converts real runtime data into concise, driver-facing state cards. */
object NovaUiStateFactory {
    fun create(snapshot: NovaRuntimeSnapshot): NovaUiState = (when (snapshot.visibleState) {
        NovaVisibleState.IDLE -> if (snapshot.isVehicleAlert()) {
            NovaUiState(
                visibleState = snapshot.visibleState,
                eyebrow = "SAFETY ALERT",
                primaryMessage = snapshot.errorMessage
                    ?: snapshot.actionResult
                    ?: snapshot.spokenText
                    ?: "A vehicle alert is active",
                secondaryMessage = "Vehicle alert remains active",
            )
        } else if (!snapshot.spokenText.isNullOrBlank()) {
            NovaUiState(
                visibleState = snapshot.visibleState,
                eyebrow = "LAST RESPONSE",
                transcript = snapshot.transcript,
                primaryMessage = snapshot.spokenText,
                secondaryMessage = "Say “Hey NOVA” to ask something else",
            )
        } else {
            NovaUiState(
                visibleState = snapshot.visibleState,
                eyebrow = "VOICE READY",
                primaryMessage = "Ready when you are",
                secondaryMessage = "Say “Hey NOVA” to begin",
            )
        }
        NovaVisibleState.LISTENING -> if (snapshot.followUpDeadlineElapsedRealtimeMs != null) {
            NovaUiState(
                visibleState = snapshot.visibleState,
                eyebrow = "STILL LISTENING",
                primaryMessage = "Anything else?",
                secondaryMessage = "Continue speaking",
                followUpDeadlineElapsedRealtimeMs = snapshot.followUpDeadlineElapsedRealtimeMs,
                canCancel = true,
            )
        } else {
            NovaUiState(
                visibleState = snapshot.visibleState,
                eyebrow = "LISTENING",
                primaryMessage = "I’m listening…",
                secondaryMessage = "Speak naturally",
                canCancel = true,
            )
        }
        NovaVisibleState.PROCESSING -> if (snapshot.progressText != null) {
            NovaUiState(
                visibleState = snapshot.visibleState,
                eyebrow = "WORKING ON IT",
                transcript = snapshot.transcript,
                primaryMessage = snapshot.progressText,
                secondaryMessage = snapshot.transcript?.let { "You asked: $it" }
                    ?: "This may take a moment",
                canCancel = true,
                showActivityProgress = true,
            )
        } else {
            NovaUiState(
                visibleState = snapshot.visibleState,
                eyebrow = if (snapshot.transcript != null) "I HEARD YOU" else "UNDERSTANDING",
                transcript = snapshot.transcript,
                primaryMessage = snapshot.transcript ?: "Understanding your request…",
                secondaryMessage = "Preparing the next step",
                canCancel = true,
                showActivityProgress = true,
            )
        }
        NovaVisibleState.EXECUTING -> NovaUiState(
            visibleState = snapshot.visibleState,
            eyebrow = actionLabel(snapshot.actionName),
            transcript = snapshot.transcript,
            primaryMessage = snapshot.actionResult ?: "Completing your request…",
            secondaryMessage = snapshot.transcript?.let { "Request: $it" }
                ?: "Applying the requested change",
            canCancel = true,
            showActivityProgress = true,
        )
        NovaVisibleState.SUCCESS -> NovaUiState(
            visibleState = snapshot.visibleState,
            eyebrow = "COMMAND COMPLETED",
            transcript = snapshot.transcript,
            primaryMessage = snapshot.spokenText
                ?: snapshot.progressText
                ?: snapshot.actionResult
                ?: "Command completed",
            secondaryMessage = "Your request is complete",
        )
        NovaVisibleState.ERROR -> NovaUiState(
            visibleState = snapshot.visibleState,
            eyebrow = if (snapshot.isVehicleAlert()) "SAFETY ALERT" else "UNABLE TO COMPLETE",
            transcript = snapshot.transcript,
            primaryMessage = snapshot.errorMessage ?: "Unable to complete the command",
            secondaryMessage = if (snapshot.isVehicleAlert()) {
                "Vehicle alert remains active"
            } else {
                "No changes were made"
            },
        )
        NovaVisibleState.SPEAKING -> NovaUiState(
            visibleState = snapshot.visibleState,
            eyebrow = when {
                snapshot.isVehicleAlert() -> "SAFETY ALERT"
                snapshot.blocked -> "UNABLE TO COMPLETE"
                else -> "NOVA RESPONSE"
            },
            transcript = snapshot.transcript,
            // A proactive fault first arrives as ERROR and then becomes SPEAKING while Android
            // plays its warning. Keep the authoritative fault detail on screen across that
            // transition; swapping it for the shorter spoken sentence made the launcher restart
            // its typewriter and look like duplicated/corrupted fault events.
            primaryMessage = if (snapshot.blocked) {
                snapshot.errorMessage
                    ?: snapshot.spokenText
                    ?: snapshot.progressText
                    ?: snapshot.actionResult
                    ?: "A vehicle alert is active"
            } else {
                snapshot.spokenText
                    ?: snapshot.progressText
                    ?: snapshot.actionResult
                    ?: "NOVA is responding…"
            },
            secondaryMessage = when {
                snapshot.isVehicleAlert() -> "Vehicle alert remains active"
                snapshot.blocked -> "No changes were made"
                else -> "NOVA is speaking"
            },
            isSpeaking = true,
            canCancel = true,
        )
        NovaVisibleState.UNAVAILABLE -> NovaUiState()
    }).copy(evidenceCards = snapshot.evidenceCards)

    private fun actionLabel(actionName: String?): String {
        if (actionName.isNullOrBlank()) return "EXECUTING"
        return actionName
            .removePrefix("set_")
            .replace('_', ' ')
            .uppercase(Locale.ROOT)
    }

    private fun NovaRuntimeSnapshot.isVehicleAlert(): Boolean =
        blocked && actionDomain == "vehicle"
}
