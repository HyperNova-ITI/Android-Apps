package com.hypernova.launcher.core.state

import android.content.Context
import android.util.Log
import com.hypernova.launcher.R
import com.hypernova.launcher.core.integration.AppAvailability
import com.hypernova.launcher.core.integration.AppDestination
import com.hypernova.launcher.core.integration.AppLauncher
import com.hypernova.launcher.core.integration.AppRegistry
import com.hypernova.launcher.core.media.MediaSessionSnapshot
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
 * - PhoneClient.
 * - ClimateClient.
 * - WeatherClient.
 * - AssistantClient.
 * - DriverProfileClient.
 * - SettingsClient.
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

    /**
     * Store the latest state received from HyperNova Media.
     */
    fun updateMediaSnapshot(snapshot: MediaSessionSnapshot) {
        latestMediaSnapshot = snapshot
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
            phone = createSimpleAppState(
                AppDestination.PHONE
            ),
            climate = createSimpleAppState(
                AppDestination.CLIMATE
            ),
            weather = createWeatherState(),
            driver = createDriverState(),
            settings = createSimpleAppState(
                AppDestination.SETTINGS
            )
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

    /**
     * Create NOVA AI state from application availability.
     */
    private fun createAssistantState(): AssistantUiState {
        val destination =
            AppDestination.NOVA_AI

        val connectionState =
            getConnectionState(destination)

        val appName =
            getAppName(destination)

        val headline: String
        val subtitle: String

        when (connectionState) {
            AppConnectionState.NOT_INSTALLED -> {
                headline = applicationContext.getString(
                    R.string.assistant_unavailable_title
                )

                subtitle = applicationContext.getString(
                    R.string.state_app_not_installed,
                    appName
                )
            }

            AppConnectionState.NO_LAUNCHABLE_ACTIVITY -> {
                headline = applicationContext.getString(
                    R.string.assistant_unavailable_title
                )

                subtitle = applicationContext.getString(
                    R.string.state_no_launchable_activity,
                    appName
                )
            }

            AppConnectionState.DISCONNECTED -> {
                headline = applicationContext.getString(
                    R.string.assistant_disconnected_title
                )

                subtitle = applicationContext.getString(
                    R.string.assistant_service_not_connected
                )
            }

            AppConnectionState.CONNECTING -> {
                headline = applicationContext.getString(
                    R.string.assistant_connecting_title
                )

                subtitle = applicationContext.getString(
                    R.string.state_connecting
                )
            }

            AppConnectionState.READY -> {
                headline = applicationContext.getString(
                    R.string.assistant_ready_title
                )

                subtitle = applicationContext.getString(
                    R.string.assistant_ready_subtitle
                )
            }

            AppConnectionState.ERROR -> {
                headline = applicationContext.getString(
                    R.string.assistant_error_title
                )

                subtitle = applicationContext.getString(
                    R.string.state_service_error
                )
            }
        }

        return AssistantUiState(
            connectionState = connectionState,
            headline = headline,
            subtitle = subtitle,

            // NOVA artwork is branding, not fake service data.
            artworkVisible = true
        )
    }

    /**
     * Create Navigation state from application availability.
     */
    private fun createNavigationState(): NavigationUiState {
        val destination =
            AppDestination.NAVIGATION

        val connectionState =
            getConnectionState(destination)

        val appName =
            getAppName(destination)

        val routeName =
            createApplicationStatusMessage(
                connectionState = connectionState,
                appName = appName,
                disconnectedMessage =
                    applicationContext.getString(
                        R.string.navigation_service_not_connected
                    ),
                readyMessage =
                    applicationContext.getString(
                        R.string.navigation_no_active_route_hint
                    )
            )

        return NavigationUiState(
            connectionState = connectionState,
            hasActiveRoute = false,
            destination = applicationContext.getString(
                R.string.navigation_no_active_route
            ),
            routeName = routeName,
            eta = applicationContext.getString(
                R.string.navigation_eta_unavailable
            ),
            distance = applicationContext.getString(
                R.string.navigation_distance_unavailable
            ),
            arrivalTime = applicationContext.getString(
                R.string.navigation_arrival_unavailable
            ),
            previewVisible = false,
            vehicleMarkerVisible = false
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

        val connectionState =
            getConnectionState(destination)

        val appName =
            getAppName(destination)

        return MediaUiState(
            connectionState = connectionState,
            hasActiveSession = false,
            hasActiveMediaItem = false,
            title = applicationContext.getString(
                R.string.media_no_active_playback
            ),
            artist = createMediaStatusText(
                connectionState = connectionState,
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

        val hasRealMediaItem =
            snapshot.connectionState ==
                    AppConnectionState.READY &&
                    snapshot.hasActiveMediaItem

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
                snapshot.artist
                    ?: applicationContext.getString(
                        R.string.media_unknown_artist
                    )
            } else {
                createMediaStatusText(
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
            connectionState =
                snapshot.connectionState,
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
        connectionState: AppConnectionState,
        appName: String,
        errorMessage: String?
    ): String {
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

    /**
     * Create the Weather quick-card state.
     */
    private fun createWeatherState(): WeatherUiState {
        val destination =
            AppDestination.WEATHER

        val connectionState =
            getConnectionState(destination)

        val appName =
            getAppName(destination)

        val statusMessage =
            createApplicationStatusMessage(
                connectionState = connectionState,
                appName = appName
            )

        return WeatherUiState(
            connectionState = connectionState,

            // Real temperature comes from WeatherClient later.
            temperature =
                applicationContext.getString(
                    R.string.temperature_value_unavailable
                ),

            // Display the real app state until a weather
            // location snapshot becomes available.
            location = statusMessage,

            // Real weather condition comes from WeatherClient.
            condition = ""
        )
    }

    /**
     * Create the Driver Profile state.
     */
    private fun createDriverState(): DriverUiState {
        val destination =
            AppDestination.DRIVER_PROFILE

        val connectionState =
            getConnectionState(destination)

        return DriverUiState(
            connectionState = connectionState,
            displayName = applicationContext.getString(
                R.string.driver_default_name
            ),

            // Do not show a profile avatar before real
            // Driver Profile data becomes available.
            avatarVisible =
                connectionState ==
                        AppConnectionState.READY
        )
    }

    /**
     * Create basic state for Phone, Climate and Settings.
     */
    private fun createSimpleAppState(
        destination: AppDestination
    ): SimpleAppUiState {
        val connectionState =
            getConnectionState(destination)

        val appName =
            getAppName(destination)

        return SimpleAppUiState(
            destination = destination,
            connectionState = connectionState,
            title = appName,
            statusMessage =
                createApplicationStatusMessage(
                    connectionState = connectionState,
                    appName = appName
                )
        )
    }

    /**
     * Create a shared application status message.
     */
    private fun createApplicationStatusMessage(
        connectionState: AppConnectionState,
        appName: String,
        disconnectedMessage: String =
            applicationContext.getString(
                R.string.state_service_disconnected
            ),
        readyMessage: String =
            applicationContext.getString(
                R.string.state_ready
            )
    ): String {
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
                disconnectedMessage
            }

            AppConnectionState.CONNECTING -> {
                applicationContext.getString(
                    R.string.state_connecting
                )
            }

            AppConnectionState.READY -> {
                readyMessage
            }

            AppConnectionState.ERROR -> {
                applicationContext.getString(
                    R.string.state_service_error
                )
            }
        }
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