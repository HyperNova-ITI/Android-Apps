package com.hypernova.launcher.core.state

import android.net.Uri
import com.hypernova.launcher.core.integration.AppDestination

/**
 * Complete state displayed by the HyperNova Launcher.
 *
 * Every section displays either:
 *
 * - Real data received from the responsible application.
 * - A real package or service state.
 * - An honest unavailable value.
 *
 * The launcher must not generate fake application data.
 */
data class LauncherUiState(
    val system: SystemUiState,
    val assistant: AssistantUiState,
    val navigation: NavigationUiState,
    val media: MediaUiState,
    val phone: SimpleAppUiState,
    val climate: SimpleAppUiState,
    val weather: WeatherUiState,
    val driver: DriverUiState,
    val settings: SimpleAppUiState
)

/**
 * Information displayed in the top status area.
 */
data class SystemUiState(
    val statusText: String,
    val outsideTemperature: String,
    val networkText: String
)

/**
 * State displayed by the NOVA AI section.
 */
data class AssistantUiState(
    val connectionState: AppConnectionState,
    val runtimeState: AssistantRuntimeState,
    val headline: String,
    val subtitle: String,
    val artworkVisible: Boolean
)

enum class AssistantRuntimeState {
    UNAVAILABLE,
    IDLE,
    LISTENING,
    PROCESSING,
    EXECUTING,
    SUCCESS,
    ERROR,
    SPEAKING,
}

/**
 * State displayed by the Navigation card.
 *
 * HyperNova Navigation owns all route information.
 */
data class NavigationUiState(
    val connectionState: AppConnectionState,
    val hasActiveRoute: Boolean,
    val destination: String,
    val routeName: String,
    val eta: String,
    val distance: String,
    val arrivalTime: String,
    val previewVisible: Boolean,
    val vehicleMarkerVisible: Boolean
)

/**
 * State displayed by the Media card.
 *
 * The launcher receives these values from MediaSessionClient.
 * The launcher does not own media playback or metadata.
 */
data class MediaUiState(
    val connectionState: AppConnectionState,
    val hasActiveSession: Boolean,
    val hasActiveMediaItem: Boolean,
    val title: String,
    val artist: String,
    val artworkUri: Uri?,
    val elapsedTime: String,
    val duration: String,
    val progressFraction: Float,
    val isPlaying: Boolean,
    val canPlayPause: Boolean,
    val canSkipPrevious: Boolean,
    val canSkipNext: Boolean,
    val artworkVisible: Boolean
)

/**
 * State displayed by the Weather quick card.
 *
 * HyperNova Weather owns temperature, location and condition data.
 */
data class WeatherUiState(
    val connectionState: AppConnectionState,
    val temperature: String,
    val location: String,
    val condition: String
)

/**
 * State displayed by the Driver Profile areas.
 */
data class DriverUiState(
    val connectionState: AppConnectionState,
    val displayName: String,
    val avatarVisible: Boolean
)

/**
 * Basic state for applications whose detailed service
 * contracts will be connected later.
 *
 * Currently used by:
 *
 * - Phone
 * - Climate
 * - Settings
 */
data class SimpleAppUiState(
    val destination: AppDestination,
    val connectionState: AppConnectionState,
    val title: String,
    val statusMessage: String
)
