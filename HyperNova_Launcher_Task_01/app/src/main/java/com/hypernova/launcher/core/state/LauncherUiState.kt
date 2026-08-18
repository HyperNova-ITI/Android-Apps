package com.hypernova.launcher.core.state

import android.net.Uri
import com.hypernova.launcher.core.assistant.NovaEvidenceCard
import com.hypernova.launcher.core.climate.ClimateAvailability
import com.hypernova.launcher.core.integration.AppAvailability
import com.hypernova.launcher.core.integration.AppDestination
import com.hypernova.launcher.core.media.MediaPlaybackState
import com.hypernova.launcher.core.navigation.NavigationRuntimeState
import com.hypernova.launcher.core.navigation.NavigationPreviewPoint

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
    val phone: PhoneUiState,
    val climate: ClimateUiState,
    val settings: SettingsUiState
)

/** Runtime connection is intentionally separate from package availability. */
enum class RuntimeConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

/**
 * Shared installed/available/connected/active/error dimensions for integrated
 * applications. No one boolean is used as a substitute for another.
 */
data class IntegratedAppState(
    val availability: AppAvailability,
    val connectionState: RuntimeConnectionState,
    val active: Boolean,
    val errorMessage: String? = null,
) {
    val installed: Boolean?
        get() = when (availability) {
            AppAvailability.NOT_INSTALLED -> false
            AppAvailability.AVAILABLE,
            AppAvailability.NO_LAUNCHABLE_ACTIVITY -> true
            AppAvailability.ERROR -> null
        }

    val available: Boolean
        get() = availability == AppAvailability.AVAILABLE
}

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
    val primaryMessage: String,
    val secondaryMessage: String? = null,
    val transcript: String? = null,
    val turnId: String? = null,
    val actionDomain: String? = null,
    val actionName: String? = null,
    val blocked: Boolean = false,
    val speaking: Boolean = false,
    val showActivityProgress: Boolean = false,
    val evidenceCards: List<NovaEvidenceCard> = emptyList(),
    val artworkVisible: Boolean,
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
    val appState: IntegratedAppState,
    val runtimeState: NavigationRuntimeState,
    val hasActiveRoute: Boolean,
    val routeId: String,
    val routeVersion: Long,
    val destination: String,
    val routeName: String,
    val eta: String,
    val distance: String,
    val arrivalTime: String,
    val routePoints: List<NavigationPreviewPoint>,
    val currentPosition: NavigationPreviewPoint?,
    val currentBearingDegrees: Float?,
    val positionAvailable: Boolean,
    val mapAvailable: Boolean,
)

/**
 * State displayed by the Media card.
 *
 * The launcher receives these values from MediaSessionClient.
 * The launcher does not own media playback or metadata.
 */
data class MediaUiState(
    val appState: IntegratedAppState,
    val playbackState: MediaPlaybackState,
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

/** Safe Phone availability and non-privileged Bluetooth state. */
data class PhoneUiState(
    val appState: IntegratedAppState,
    val title: String,
    val statusMessage: String,
    val bluetoothEnabled: Boolean?,
)

/** Real Climate contract state, when the app exposes its read-only service. */
data class ClimateUiState(
    val appState: IntegratedAppState,
    val availability: ClimateAvailability,
    val temperature: String,
    val fan: String,
    val statusMessage: String,
    val autoModeEnabled: Boolean?,
)

/** Real Android framework values displayed by the Settings card. */
data class SettingsUiState(
    val appState: IntegratedAppState,
    val primaryText: String,
    val secondaryText: String,
)
