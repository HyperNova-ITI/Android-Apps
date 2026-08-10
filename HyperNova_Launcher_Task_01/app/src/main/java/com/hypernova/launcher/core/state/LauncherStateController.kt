package com.hypernova.launcher.core.state

import android.content.Context
import android.util.Log
import com.hypernova.launcher.R
import com.hypernova.launcher.core.assistant.NovaAssistantStateParser
import com.hypernova.launcher.core.assistant.NovaServiceConnection
import com.hypernova.launcher.core.assistant.NovaStatusSnapshot
import com.hypernova.launcher.core.climate.ClimateAvailability
import com.hypernova.launcher.core.climate.ClimateSnapshot
import com.hypernova.launcher.core.integration.AppAvailability
import com.hypernova.launcher.core.integration.AppDestination
import com.hypernova.launcher.core.integration.AppLauncher
import com.hypernova.launcher.core.integration.AppRegistry
import com.hypernova.launcher.core.media.MediaPlaybackState
import com.hypernova.launcher.core.media.MediaSessionSnapshot
import com.hypernova.launcher.core.navigation.NavigationRuntimeState
import com.hypernova.launcher.core.navigation.NavigationStatusSnapshot
import com.hypernova.launcher.core.phone.PhoneSnapshot
import com.hypernova.launcher.core.settings.SystemSettingsSnapshot
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Creates the complete state displayed by the launcher.
 *
 * Current data sources:
 *
 * - Package availability through AppLauncher.
 * - Media playback through MediaSessionClient.
 *
 * Future data sources:
 *
 * - NavigationClient.
     * - Future app contracts may provide additional authoritative state.
 */
class LauncherStateController(
    context: Context,
    private val appLauncher: AppLauncher
) {

    companion object {
        private const val TAG = "LauncherStateController"
    }

    private val applicationContext =
        context.applicationContext

    /**
     * Latest real state received from MediaSessionClient.
     *
     * Null means MediaSessionClient has not reported a state yet.
     */
    private var latestMediaSnapshot: MediaSessionSnapshot? = null
    private var latestAssistantSnapshot: NovaStatusSnapshot? = null
    private var latestNavigationSnapshot: NavigationStatusSnapshot? = null
    private var latestClimateSnapshot: ClimateSnapshot? = null
    private var latestPhoneSnapshot: PhoneSnapshot? = null
    private var latestSettingsSnapshot: SystemSettingsSnapshot? = null
    private var navigationMapAvailable = false

    /**
     * Store the latest state received from HyperNova Media.
     */
    fun updateMediaSnapshot(snapshot: MediaSessionSnapshot) {
        latestMediaSnapshot = snapshot
    }

    /** Store the latest state published by NOVA AI. */
    fun updateAssistantSnapshot(snapshot: NovaStatusSnapshot) {
        latestAssistantSnapshot = snapshot
    }

    fun updateNavigationSnapshot(snapshot: NavigationStatusSnapshot) {
        latestNavigationSnapshot = snapshot
    }

    fun updateNavigationMapAvailability(available: Boolean) {
        navigationMapAvailable = available
    }

    fun updateClimateSnapshot(snapshot: ClimateSnapshot) {
        latestClimateSnapshot = snapshot
    }

    fun updatePhoneSnapshot(snapshot: PhoneSnapshot) {
        latestPhoneSnapshot = snapshot
    }

    fun updateSettingsSnapshot(snapshot: SystemSettingsSnapshot) {
        latestSettingsSnapshot = snapshot
    }

    /**
     * Create a fresh complete snapshot for the launcher UI.
     */
    fun refresh(): LauncherUiState {
        return LauncherUiState(
            system = createSystemState(),
            assistant = createAssistantState(),
            navigation = createNavigationState(),
            media = createMediaState(),
            phone = createPhoneState(),
            climate = createClimateState(),
            settings = createSettingsState()
        )
    }

    /**
     * Create the current system summary.
     *
     * Real vehicle and connectivity clients will replace
     * these unavailable values later.
     */
    private fun createSystemState(): SystemUiState {
        return SystemUiState(
            statusText = applicationContext.getString(
                R.string.system_status_unavailable
            ),
            outsideTemperature =
                applicationContext.getString(
                    R.string.temperature_value_unavailable
                ),
            networkText = applicationContext.getString(
                R.string.network_value_unavailable
            )
        )
    }

    /** Create NOVA AI state from package availability and its live status service. */
    private fun createAssistantState(): AssistantUiState {
        val packageState = getConnectionState(AppDestination.NOVA_AI)
        val appName = getAppName(AppDestination.NOVA_AI)

        if (packageState == AppConnectionState.NOT_INSTALLED) {
            return assistantState(
                AppConnectionState.NOT_INSTALLED,
                AssistantRuntimeState.UNAVAILABLE,
                R.string.assistant_unavailable_title,
                applicationContext.getString(R.string.state_app_not_installed, appName),
            )
        }
        if (packageState == AppConnectionState.NO_LAUNCHABLE_ACTIVITY) {
            return assistantState(
                AppConnectionState.NO_LAUNCHABLE_ACTIVITY,
                AssistantRuntimeState.UNAVAILABLE,
                R.string.assistant_unavailable_title,
                applicationContext.getString(R.string.state_no_launchable_activity, appName),
            )
        }

        val snapshot = latestAssistantSnapshot
        return when (snapshot?.connection) {
            NovaServiceConnection.CONNECTING -> assistantState(
                AppConnectionState.CONNECTING,
                AssistantRuntimeState.UNAVAILABLE,
                R.string.assistant_connecting_title,
                applicationContext.getString(R.string.state_connecting),
            )
            NovaServiceConnection.ERROR -> assistantState(
                AppConnectionState.ERROR,
                AssistantRuntimeState.ERROR,
                R.string.assistant_error_title,
                applicationContext.getString(R.string.state_service_error),
            )
            NovaServiceConnection.CONNECTED -> createLiveAssistantState(snapshot.state)
            NovaServiceConnection.DISCONNECTED, null -> assistantState(
                AppConnectionState.DISCONNECTED,
                AssistantRuntimeState.UNAVAILABLE,
                R.string.assistant_unavailable_title,
                applicationContext.getString(R.string.assistant_reconnect_subtitle),
            )
        }
    }

    private fun createLiveAssistantState(wireState: String?): AssistantUiState {
        val runtimeState = NovaAssistantStateParser.parse(wireState)

        val content = when (runtimeState) {
            AssistantRuntimeState.IDLE -> R.string.assistant_ready_title to R.string.assistant_idle_subtitle
            AssistantRuntimeState.LISTENING -> R.string.assistant_listening_title to R.string.assistant_listening_subtitle
            AssistantRuntimeState.PROCESSING -> R.string.assistant_processing_title to R.string.assistant_processing_subtitle
            AssistantRuntimeState.EXECUTING -> R.string.assistant_executing_title to R.string.assistant_executing_subtitle
            AssistantRuntimeState.SUCCESS -> R.string.assistant_success_title to R.string.assistant_success_subtitle
            AssistantRuntimeState.ERROR -> R.string.assistant_error_title to R.string.assistant_error_subtitle
            AssistantRuntimeState.SPEAKING -> R.string.assistant_speaking_title to R.string.assistant_speaking_subtitle
            AssistantRuntimeState.UNAVAILABLE -> R.string.assistant_unavailable_title to R.string.assistant_reconnect_subtitle
        }
        val connectionState = when (runtimeState) {
            AssistantRuntimeState.ERROR -> AppConnectionState.ERROR
            AssistantRuntimeState.UNAVAILABLE -> AppConnectionState.DISCONNECTED
            else -> AppConnectionState.READY
        }
        return assistantState(
            connectionState,
            runtimeState,
            content.first,
            applicationContext.getString(content.second),
        )
    }

    private fun assistantState(
        connectionState: AppConnectionState,
        runtimeState: AssistantRuntimeState,
        headlineResource: Int,
        subtitle: String,
    ) = AssistantUiState(
        connectionState = connectionState,
        runtimeState = runtimeState,
        headline = applicationContext.getString(headlineResource),
        subtitle = subtitle,
        artworkVisible = true,
    )

    /** Create Navigation state from package state and the frozen AIDL result. */
    private fun createNavigationState(): NavigationUiState {
        val destination = AppDestination.NAVIGATION
        val availability = getAvailability(destination)
        val snapshot = latestNavigationSnapshot
        val runtimeState = snapshot?.runtimeState ?: NavigationRuntimeState.UNAVAILABLE
        val isActive = runtimeState == NavigationRuntimeState.ACTIVE
        val appState = createIntegratedAppState(
            availability = availability,
            reportedConnection = snapshot?.connectionState,
            active = isActive,
            errorMessage = snapshot?.errorMessage,
        )

        val unavailableStatus = createAvailabilityStatus(availability, getAppName(destination))
        val status = when {
            unavailableStatus != null -> unavailableStatus
            appState.connectionState == RuntimeConnectionState.CONNECTING ->
                applicationContext.getString(R.string.state_connecting)
            appState.connectionState == RuntimeConnectionState.DISCONNECTED ->
                applicationContext.getString(R.string.navigation_service_not_connected)
            appState.connectionState == RuntimeConnectionState.ERROR ->
                snapshot?.errorMessage?.takeIf { it.isNotBlank() }
                    ?: applicationContext.getString(R.string.state_service_error)
            runtimeState == NavigationRuntimeState.CALCULATING ->
                applicationContext.getString(R.string.navigation_calculating)
            runtimeState == NavigationRuntimeState.ACTIVE -> {
                val activeLabel =
                    applicationContext.getString(R.string.navigation_route_active)
                if (snapshot?.destinationTitle != null) {
                    listOfNotNull(activeLabel, snapshot.destinationSubtitle)
                        .joinToString(applicationContext.getString(R.string.state_separator))
                } else {
                    applicationContext.getString(R.string.navigation_route_details_unavailable)
                }
            }
            runtimeState == NavigationRuntimeState.ARRIVED ->
                applicationContext.getString(R.string.navigation_arrived)
            runtimeState == NavigationRuntimeState.ERROR ->
                snapshot?.errorMessage
                    ?: applicationContext.getString(R.string.navigation_route_error)
            else -> applicationContext.getString(R.string.navigation_no_active_route_hint)
        }

        val destinationText = when {
            isActive -> snapshot?.destinationTitle
                ?: applicationContext.getString(R.string.navigation_route_active)
            runtimeState == NavigationRuntimeState.CALCULATING ->
                applicationContext.getString(R.string.navigation_calculating)
            runtimeState == NavigationRuntimeState.ARRIVED ->
                snapshot?.destinationTitle
                    ?: applicationContext.getString(R.string.navigation_arrived)
            else -> applicationContext.getString(R.string.navigation_no_active_route)
        }
        val previewAllowed =
            runtimeState == NavigationRuntimeState.CALCULATING ||
                runtimeState == NavigationRuntimeState.ACTIVE ||
                runtimeState == NavigationRuntimeState.ARRIVED
        val routePoints =
            snapshot?.routePoints
                ?.takeIf { previewAllowed }
                .orEmpty()

        return NavigationUiState(
            appState = appState,
            runtimeState = runtimeState,
            hasActiveRoute = isActive,
            routeId = snapshot?.routeId.orEmpty(),
            routeVersion = snapshot?.routeVersion ?: 0L,
            destination = destinationText,
            routeName = status,
            eta = snapshot?.etaSeconds?.let(::formatNavigationEta)
                ?: applicationContext.getString(R.string.navigation_eta_unavailable),
            distance = snapshot?.distanceMeters?.let(::formatNavigationDistance)
                ?: applicationContext.getString(R.string.navigation_distance_unavailable),
            arrivalTime = snapshot?.etaSeconds?.let(::formatArrivalTime)
                ?: applicationContext.getString(R.string.navigation_arrival_unavailable),
            routePoints = routePoints,
            currentPosition = snapshot?.currentPosition?.takeIf { routePoints.isNotEmpty() },
            currentBearingDegrees =
                snapshot?.currentBearingDegrees?.takeIf { routePoints.isNotEmpty() },
            positionAvailable =
                snapshot?.positionAvailable == true && routePoints.isNotEmpty(),
            mapAvailable = navigationMapAvailable,
        )
    }

    /**
     * Build MediaUiState using the real MediaSession snapshot
     * when available.
     */
    private fun createMediaState(): MediaUiState {
        val mediaSnapshot =
            latestMediaSnapshot

        return if (mediaSnapshot != null) {
            createMediaStateFromSnapshot(
                mediaSnapshot
            )
        } else {
            createMediaStateFromApplicationAvailability()
        }
    }

    /**
     * Create the initial Media state before MediaSessionClient
     * reports anything.
     */
    private fun createMediaStateFromApplicationAvailability():
            MediaUiState {

        val destination =
            AppDestination.MEDIA

        val availability = getAvailability(destination)
        val appState = createIntegratedAppState(
            availability = availability,
            reportedConnection = null,
            active = false,
        )

        val appName =
            getAppName(destination)

        return MediaUiState(
            appState = appState,
            playbackState = MediaPlaybackState.NO_SESSION,
            hasActiveSession = false,
            hasActiveMediaItem = false,
            title = applicationContext.getString(
                R.string.media_no_active_playback
            ),
            artist = createMediaStatusText(
                availability = availability,
                connectionState = AppConnectionState.DISCONNECTED,
                appName = appName,
                errorMessage = null
            ),
            artworkUri = null,
            elapsedTime = applicationContext.getString(
                R.string.media_time_unavailable
            ),
            duration = applicationContext.getString(
                R.string.media_time_unavailable
            ),
            progressFraction = 0.0f,
            isPlaying = false,
            canPlayPause = false,
            canSkipPrevious = false,
            canSkipNext = false,
            artworkVisible = false
        )
    }

    /**
     * Convert MediaSessionSnapshot into the state displayed
     * by the Media card.
     */
    private fun createMediaStateFromSnapshot(
        snapshot: MediaSessionSnapshot
    ): MediaUiState {
        val appName =
            getAppName(AppDestination.MEDIA)

        val availability = getAvailability(AppDestination.MEDIA)

        val hasRealMediaItem =
            snapshot.connectionState ==
                    AppConnectionState.READY &&
                    snapshot.hasActiveMediaItem

        val isActive = hasRealMediaItem && snapshot.playbackState in setOf(
            MediaPlaybackState.PLAYING,
            MediaPlaybackState.PAUSED,
            MediaPlaybackState.BUFFERING,
        )

        val appState = createIntegratedAppState(
            availability = availability,
            reportedConnection = snapshot.connectionState,
            active = isActive,
            errorMessage = snapshot.errorMessage,
        )

        val title =
            if (hasRealMediaItem) {
                snapshot.title
                    ?: applicationContext.getString(
                        R.string.media_unknown_title
                    )
            } else {
                applicationContext.getString(
                    R.string.media_no_active_playback
                )
            }

        val artistOrStatus =
            if (hasRealMediaItem) {
                val artist = snapshot.artist
                    ?: applicationContext.getString(
                        R.string.media_unknown_artist
                    )
                val playback = when (snapshot.playbackState) {
                    MediaPlaybackState.PLAYING -> R.string.media_state_playing
                    MediaPlaybackState.PAUSED -> R.string.media_state_paused
                    MediaPlaybackState.BUFFERING -> R.string.media_state_buffering
                    MediaPlaybackState.STOPPED -> R.string.media_state_stopped
                    MediaPlaybackState.ENDED -> R.string.media_state_ended
                    MediaPlaybackState.ERROR -> R.string.media_connection_error
                    MediaPlaybackState.IDLE,
                    MediaPlaybackState.NO_SESSION -> null
                }
                playback?.let {
                    "$artist${applicationContext.getString(R.string.state_separator)}" +
                        applicationContext.getString(it)
                } ?: artist
            } else {
                createMediaStatusText(
                    availability = availability,
                    connectionState =
                        snapshot.connectionState,
                    appName = appName,
                    errorMessage =
                        snapshot.errorMessage
                )
            }

        val elapsedTime =
            if (hasRealMediaItem) {
                formatMediaTime(
                    snapshot.positionMs
                )
            } else {
                applicationContext.getString(
                    R.string.media_time_unavailable
                )
            }

        val duration =
            if (
                hasRealMediaItem &&
                snapshot.durationMs > 0L
            ) {
                formatMediaTime(
                    snapshot.durationMs
                )
            } else {
                applicationContext.getString(
                    R.string.media_time_unavailable
                )
            }

        val progressFraction =
            if (
                hasRealMediaItem &&
                snapshot.durationMs > 0L
            ) {
                (
                        snapshot.positionMs.toDouble() /
                                snapshot.durationMs.toDouble()
                        )
                    .toFloat()
                    .coerceIn(
                        0.0f,
                        1.0f
                    )
            } else {
                0.0f
            }

        return MediaUiState(
            appState = appState,
            playbackState = snapshot.playbackState,
            hasActiveSession =
                snapshot.hasActiveSession,
            hasActiveMediaItem =
                hasRealMediaItem,
            title = title,
            artist = artistOrStatus,
            artworkUri =
                if (hasRealMediaItem) {
                    snapshot.artworkUri
                } else {
                    null
                },
            elapsedTime = elapsedTime,
            duration = duration,
            progressFraction = progressFraction,
            isPlaying =
                hasRealMediaItem &&
                        snapshot.isPlaying,
            canPlayPause =
                hasRealMediaItem &&
                        snapshot.canPlayPause,
            canSkipPrevious =
                hasRealMediaItem &&
                        snapshot.canSkipPrevious,
            canSkipNext =
                hasRealMediaItem &&
                        snapshot.canSkipNext,
            artworkVisible =
                hasRealMediaItem &&
                        snapshot.artworkUri != null
        )
    }

    /**
     * Create the status text displayed under the Media title.
     */
    private fun createMediaStatusText(
        availability: AppAvailability,
        connectionState: AppConnectionState,
        appName: String,
        errorMessage: String?
    ): String {
        createAvailabilityStatus(availability, appName)?.let { return it }
        return when (connectionState) {
            AppConnectionState.NOT_INSTALLED -> {
                applicationContext.getString(
                    R.string.state_app_not_installed,
                    appName
                )
            }

            AppConnectionState.NO_LAUNCHABLE_ACTIVITY -> {
                applicationContext.getString(
                    R.string.state_no_launchable_activity,
                    appName
                )
            }

            AppConnectionState.DISCONNECTED -> {
                applicationContext.getString(
                    R.string.media_session_not_connected
                )
            }

            AppConnectionState.CONNECTING -> {
                applicationContext.getString(
                    R.string.media_connecting
                )
            }

            AppConnectionState.READY -> {
                applicationContext.getString(
                    R.string.media_no_active_playback_hint
                )
            }

            AppConnectionState.ERROR -> {
                errorMessage
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: applicationContext.getString(
                        R.string.media_connection_error
                    )
            }
        }
    }

    private fun createPhoneState(): PhoneUiState {
        val destination = AppDestination.PHONE
        val availability = getAvailability(destination)
        val snapshot = latestPhoneSnapshot
        val appState = createIntegratedAppState(
            availability = availability,
            reportedConnection = snapshot?.connectionState,
            active = snapshot?.phoneConnected == true,
        )
        val status = createAvailabilityStatus(availability, getAppName(destination))
            ?: when {
                snapshot?.bluetoothEnabled == false -> applicationContext.getString(R.string.phone_bluetooth_off)
                else -> applicationContext.getString(R.string.phone_available)
            }

        return PhoneUiState(
            appState = appState,
            title = getAppName(destination),
            statusMessage = status,
            bluetoothEnabled = snapshot?.bluetoothEnabled,
        )
    }

    private fun createClimateState(): ClimateUiState {
        val destination = AppDestination.CLIMATE
        val availability = getAvailability(destination)
        val snapshot = latestClimateSnapshot
        val climateActive =
            snapshot?.availability == ClimateAvailability.AVAILABLE &&
                snapshot.powerEnabled == true
        val appState = createIntegratedAppState(
            availability = availability,
            reportedConnection = snapshot?.connectionState,
            active = climateActive,
            errorMessage = snapshot?.errorMessage,
        )
        val status = createAvailabilityStatus(availability, getAppName(destination))
            ?: when {
                appState.connectionState == RuntimeConnectionState.CONNECTING ->
                    applicationContext.getString(R.string.state_connecting)
                appState.connectionState == RuntimeConnectionState.DISCONNECTED ->
                    applicationContext.getString(R.string.climate_available)
                appState.connectionState == RuntimeConnectionState.ERROR ->
                    snapshot?.errorMessage
                        ?: applicationContext.getString(R.string.climate_hvac_unavailable)
                snapshot?.availability == ClimateAvailability.STALE ->
                    applicationContext.getString(R.string.climate_state_stale)
                snapshot?.availability != ClimateAvailability.AVAILABLE ->
                    applicationContext.getString(R.string.climate_hvac_unavailable)
                snapshot.powerEnabled == false -> applicationContext.getString(R.string.climate_power_off)
                snapshot.autoModeEnabled == true -> applicationContext.getString(R.string.climate_auto_active)
                else -> applicationContext.getString(R.string.climate_hvac_available)
            }

        return ClimateUiState(
            appState = appState,
            availability = snapshot?.availability ?: ClimateAvailability.UNAVAILABLE,
            temperature = snapshot?.driverTargetTemperatureC?.let(::formatTemperature)
                ?: applicationContext.getString(R.string.climate_temperature_unavailable),
            fan = snapshot?.fanLevel?.let {
                applicationContext.getString(R.string.climate_fan_level, it)
            } ?: applicationContext.getString(R.string.climate_fan_unavailable),
            statusMessage = status,
            autoModeEnabled = snapshot?.autoModeEnabled,
        )
    }

    private fun createSettingsState(): SettingsUiState {
        val destination = AppDestination.SETTINGS
        val availability = getAvailability(destination)
        val snapshot = latestSettingsSnapshot
        val appState = createIntegratedAppState(
            availability = availability,
            reportedConnection = snapshot?.connectionState,
            active = false,
            errorMessage = snapshot?.errorMessage,
        )
        val unavailable = createAvailabilityStatus(availability, getAppName(destination))

        val connectivity = if (unavailable != null) {
            getAppName(destination)
        } else {
            listOf(
                formatToggle(R.string.settings_wifi, snapshot?.wifiEnabled),
                formatToggle(R.string.settings_bluetooth, snapshot?.bluetoothEnabled),
            ).joinToString(applicationContext.getString(R.string.state_separator))
        }
        val levels = unavailable ?: listOfNotNull(
            snapshot?.brightnessPercent?.let {
                applicationContext.getString(R.string.settings_brightness_percent, it)
            },
            snapshot?.mediaVolumePercent?.let {
                applicationContext.getString(R.string.settings_volume_percent, it)
            },
        ).takeIf { it.isNotEmpty() }
            ?.joinToString(applicationContext.getString(R.string.state_separator))
            ?: snapshot?.errorMessage
            ?: applicationContext.getString(R.string.settings_state_unavailable)

        return SettingsUiState(
            appState = appState,
            primaryText = connectivity,
            secondaryText = levels,
        )
    }

    private fun createIntegratedAppState(
        availability: AppAvailability,
        reportedConnection: AppConnectionState?,
        active: Boolean,
        errorMessage: String? = null,
    ): IntegratedAppState {
        val connection = if (availability != AppAvailability.AVAILABLE) {
            RuntimeConnectionState.DISCONNECTED
        } else {
            when (reportedConnection) {
                AppConnectionState.CONNECTING -> RuntimeConnectionState.CONNECTING
                AppConnectionState.READY -> RuntimeConnectionState.CONNECTED
                AppConnectionState.ERROR -> RuntimeConnectionState.ERROR
                else -> RuntimeConnectionState.DISCONNECTED
            }
        }
        return IntegratedAppState(
            availability = availability,
            connectionState = connection,
            active = active &&
                availability == AppAvailability.AVAILABLE &&
                connection == RuntimeConnectionState.CONNECTED,
            errorMessage = errorMessage,
        )
    }

    private fun createAvailabilityStatus(
        availability: AppAvailability,
        appName: String,
    ): String? = when (availability) {
        AppAvailability.NOT_INSTALLED -> applicationContext.getString(
            R.string.state_app_not_installed,
            appName,
        )
        AppAvailability.NO_LAUNCHABLE_ACTIVITY -> applicationContext.getString(
            R.string.state_no_launchable_activity,
            appName,
        )
        AppAvailability.ERROR -> applicationContext.getString(
            R.string.state_availability_error,
            appName,
        )
        AppAvailability.AVAILABLE -> null
    }

    private fun formatToggle(labelResource: Int, enabled: Boolean?): String {
        val value = when (enabled) {
            true -> applicationContext.getString(R.string.state_on)
            false -> applicationContext.getString(R.string.state_off)
            null -> applicationContext.getString(R.string.state_value_unavailable)
        }
        return applicationContext.getString(
            R.string.settings_toggle_state,
            applicationContext.getString(labelResource),
            value,
        )
    }

    private fun formatTemperature(valueC: Float): String =
        if (!valueC.isFinite()) {
            applicationContext.getString(R.string.climate_temperature_unavailable)
        } else if (valueC % 1f == 0f) {
            applicationContext.getString(R.string.climate_temperature_whole, valueC.toInt())
        } else {
            applicationContext.getString(R.string.climate_temperature_decimal, valueC)
        }

    private fun formatNavigationEta(seconds: Long): String {
        val minutes = ((seconds + 59L) / 60L).coerceAtLeast(0L)
        return applicationContext.getString(R.string.navigation_eta_minutes, minutes)
    }

    private fun formatNavigationDistance(meters: Long): String =
        if (meters >= 1_000L) {
            applicationContext.getString(
                R.string.navigation_distance_kilometers,
                meters / 1_000.0,
            )
        } else {
            applicationContext.getString(R.string.navigation_distance_meters, meters)
        }

    private fun formatArrivalTime(etaSeconds: Long): String {
        val arrival = Date(System.currentTimeMillis() + etaSeconds.coerceAtLeast(0L) * 1_000L)
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(arrival)
        return applicationContext.getString(R.string.navigation_arrival_time, time)
    }

    private fun getAvailability(destination: AppDestination): AppAvailability =
        try {
            appLauncher.getAvailability(destination)
        } catch (exception: Exception) {
            Log.e(TAG, "Could not check availability for $destination", exception)
            AppAvailability.ERROR
        }

    /**
     * Convert package availability into launcher connection state.
     */
    private fun getConnectionState(
        destination: AppDestination
    ): AppConnectionState {
        return try {
            when (
                appLauncher.getAvailability(
                    destination
                )
            ) {
                AppAvailability.NOT_INSTALLED -> {
                    AppConnectionState.NOT_INSTALLED
                }

                AppAvailability.NO_LAUNCHABLE_ACTIVITY -> {
                    AppConnectionState
                        .NO_LAUNCHABLE_ACTIVITY
                }

                AppAvailability.AVAILABLE -> {
                    /*
                     * The package exists and has a launchable
                     * application screen.
                     *
                     * It remains DISCONNECTED until its real
                     * integration client reports READY.
                     */
                    AppConnectionState.DISCONNECTED
                }

                AppAvailability.ERROR -> AppConnectionState.ERROR
            }
        } catch (exception: Exception) {
            Log.e(
                TAG,
                "Could not check availability for $destination",
                exception
            )

            AppConnectionState.ERROR
        }
    }

    /**
     * Return the registered application display name.
     */
    private fun getAppName(
        destination: AppDestination
    ): String {
        val appSpec =
            AppRegistry.get(destination)

        return applicationContext.getString(
            appSpec.displayNameResourceId
        )
    }

    /**
     * Format MediaSession milliseconds as m:ss or h:mm:ss.
     */
    private fun formatMediaTime(
        timeMs: Long
    ): String {
        if (timeMs < 0L) {
            return applicationContext.getString(
                R.string.media_time_unavailable
            )
        }

        val totalSeconds =
            timeMs / 1000L

        val hours =
            totalSeconds / 3600L

        val minutes =
            (totalSeconds % 3600L) / 60L

        val seconds =
            totalSeconds % 60L

        return if (hours > 0L) {
            String.format(
                Locale.US,
                "%d:%02d:%02d",
                hours,
                minutes,
                seconds
            )
        } else {
            String.format(
                Locale.US,
                "%d:%02d",
                minutes,
                seconds
            )
        }
    }
}
