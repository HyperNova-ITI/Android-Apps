package com.hypernova.launcher.core.assistant

import com.hypernova.launcher.core.integration.AppDestination
import com.hypernova.launcher.core.state.LauncherUiState

/** A lightweight contextual result card; it never embeds or impersonates another application. */
data class NovaContextCard(
    val domain: String,
    val label: String,
    val title: String,
    val detail: String,
    val metadata: String? = null,
    val destination: AppDestination? = null,
)

object NovaContextCardFactory {
    fun create(state: LauncherUiState): NovaContextCard? = when (state.assistant.actionDomain) {
        "navigation" -> navigation(state)
        "media" -> media(state)
        "phone" -> phone(state)
        "climate" -> climate(state)
        "vehicle" -> NovaContextCard(
            domain = "vehicle",
            label = "VEHICLE",
            title = if (state.assistant.blocked) "Action unavailable" else "Vehicle insight",
            detail = state.assistant.primaryMessage,
        )
        else -> null
    }

    private fun navigation(state: LauncherUiState): NovaContextCard {
        val navigation = state.navigation
        val hasDestination = navigation.hasActiveRoute ||
            navigation.destination.isNotBlank() && !navigation.destination.startsWith("No active")
        val metadata = listOf(navigation.eta, navigation.distance)
            .filterNot { it.contains('—') }
            .joinToString("  •  ")
            .takeIf(String::isNotBlank)
        return NovaContextCard(
            domain = "navigation",
            label = "NAVIGATION",
            title = navigation.destination.takeIf { hasDestination } ?: "Destination ready",
            detail = navigation.routeName.takeIf { hasDestination }
                ?: "Open Navigation to view the route preview",
            metadata = metadata,
            destination = AppDestination.NAVIGATION,
        )
    }

    private fun media(state: LauncherUiState): NovaContextCard = NovaContextCard(
        domain = "media",
        label = "NOW PLAYING",
        title = state.media.title,
        detail = state.media.artist,
        metadata = state.media.playbackState.name.lowercase().replace('_', ' '),
        destination = AppDestination.MEDIA,
    )

    private fun phone(state: LauncherUiState): NovaContextCard = NovaContextCard(
        domain = "phone",
        label = "PHONE",
        title = state.phone.title,
        detail = state.phone.statusMessage,
        destination = AppDestination.PHONE,
    )

    private fun climate(state: LauncherUiState): NovaContextCard = NovaContextCard(
        domain = "climate",
        label = "CLIMATE",
        title = listOf(state.climate.temperature, state.climate.fan)
            .filterNot { it.contains("--") || it.contains('—') }
            .joinToString("  •  ")
            .ifBlank { "Climate" },
        detail = state.climate.statusMessage,
        metadata = state.climate.autoModeEnabled?.let { if (it) "AUTO" else "MANUAL" },
        destination = AppDestination.CLIMATE,
    )
}
