package com.hypernova.launcher

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hypernova.launcher.core.assistant.NovaStatusClient
import com.hypernova.launcher.core.integration.AppDestination
import com.hypernova.launcher.core.integration.AppLaunchResult
import com.hypernova.launcher.core.integration.AppLauncher
import com.hypernova.launcher.core.integration.AppRegistry
import com.hypernova.launcher.core.media.MediaSessionClient
import com.hypernova.launcher.core.media.MediaSessionSnapshot
import com.hypernova.launcher.core.state.AppConnectionState
import com.hypernova.launcher.core.state.AssistantRuntimeState
import com.hypernova.launcher.core.state.LauncherStateController
import com.hypernova.launcher.core.state.LauncherUiState
import com.hypernova.launcher.core.state.MediaUiState
import com.hypernova.launcher.core.state.SimpleAppUiState
import com.hypernova.launcher.core.state.WeatherUiState
import com.hypernova.launcher.core.theme.LauncherThemeController
import com.hypernova.launcher.databinding.ActivityMainBinding
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HyperNovaLauncher"
        private const val FEEDBACK_DURATION_MS = 4000L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var appLauncher: AppLauncher
    private lateinit var stateController: LauncherStateController
    private lateinit var mediaSessionClient: MediaSessionClient
    private lateinit var novaStatusClient: NovaStatusClient
    private lateinit var themeController: LauncherThemeController
    private lateinit var latestUiState: LauncherUiState
    private var novaOrbAnimator: ObjectAnimator? = null
    private var animatedNovaState: AssistantRuntimeState? = null

    private val resetFeedbackRunnable = Runnable {
        if (
            ::binding.isInitialized &&
            ::latestUiState.isInitialized
        ) {
            restoreAssistantSubtitle()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create ViewBinding from activity_main.xml.
        binding =
            ActivityMainBinding.inflate(layoutInflater)

        // Display the launcher interface.
        setContentView(binding.root)

        // Create the persistent launcher Light/Dark controller.
        themeController =
            LauncherThemeController(this)

        // Create application launching and package detection.
        appLauncher =
            AppLauncher(this)

        // Create the central launcher state controller.
        stateController =
            LauncherStateController(
                context = this,
                appLauncher = appLauncher
            )

        /*
         * Create the real MediaSession connection.
         *
         * Every update received from HyperNova Media is stored
         * inside LauncherStateController and rendered immediately.
         */
        mediaSessionClient =
            MediaSessionClient(
                context = this,
                appLauncher = appLauncher,
                onSnapshotChanged = { snapshot ->
                    handleMediaSnapshot(snapshot)
                }
            )

        novaStatusClient =
            NovaStatusClient(this) { snapshot ->
                runOnUiThread {
                    stateController.updateAssistantSnapshot(snapshot)
                    refreshAndRenderState()
                }
            }

        configureFullScreenMode()
        configureThemeToggle()
        configureNovaActions()
        configureNavigationCard()
        configureMediaCard()
        configurePhoneAndClimateActions()
        configureProfileActions()
        configureQuickCardActions()
        configureBottomNavigation()

        refreshAndRenderState()

        Log.d(
            TAG,
            "HyperNova Launcher started"
        )
    }

    override fun onStart() {
        super.onStart()

        if (::mediaSessionClient.isInitialized) {
            Log.d(
                TAG,
                "Connecting to HyperNova MediaSession"
            )

            mediaSessionClient.connect()
        }

        if (::novaStatusClient.isInitialized) {
            novaStatusClient.connect()
        }
    }

    override fun onResume() {
        super.onResume()

        if (::themeController.isInitialized) {
            renderThemeToggle()
        }

        if (
            ::appLauncher.isInitialized &&
            ::stateController.isInitialized
        ) {
            refreshAndRenderState()
        }
    }

    override fun onStop() {
        if (::novaStatusClient.isInitialized) {
            novaStatusClient.disconnect()
        }

        if (::mediaSessionClient.isInitialized) {
            Log.d(
                TAG,
                "Releasing HyperNova MediaController"
            )

            mediaSessionClient.disconnect()
        }

        super.onStop()
    }

    override fun onDestroy() {
        novaOrbAnimator?.cancel()

        if (::binding.isInitialized) {
            binding.textNovaQuestion.removeCallbacks(
                resetFeedbackRunnable
            )
        }

        super.onDestroy()
    }

    /**
     * Hide Android system bars for the full-screen cockpit UI.
     *
     * The launcher follows the system day/night configuration.
     * When transient system bars are revealed by a swipe, their
     * icon contrast is selected from the active theme.
     */
    private fun configureFullScreenMode() {
        WindowCompat.setDecorFitsSystemWindows(
            window,
            false
        )

        val controller =
            WindowInsetsControllerCompat(
                window,
                binding.root
            )

        val currentNightMode =
            resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK

        val isNightMode =
            currentNightMode ==
                Configuration.UI_MODE_NIGHT_YES

        controller.isAppearanceLightStatusBars =
            !isNightMode

        controller.isAppearanceLightNavigationBars =
            !isNightMode

        controller.hide(
            WindowInsetsCompat.Type.systemBars()
        )

        controller.systemBarsBehavior =
            WindowInsetsControllerCompat
                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /**
     * Configure the top status-bar Light/Dark mode button.
     */
    private fun configureThemeToggle() {
        renderThemeToggle()

        binding.buttonThemeToggle.setOnClickListener {
            themeController.toggleTheme()
        }
    }

    /**
     * Show the action that will happen after the next button press.
     *
     * Dark launcher  -> show Sun icon to switch to Light.
     * Light launcher -> show Moon icon to switch to Dark.
     */
    private fun renderThemeToggle() {
        val isNightMode =
            themeController.isNightModeActive()

        binding.buttonThemeToggle.setImageResource(
            if (isNightMode) {
                R.drawable.ic_theme_light
            } else {
                R.drawable.ic_theme_dark
            }
        )

        binding.buttonThemeToggle.contentDescription =
            getString(
                if (isNightMode) {
                    R.string.theme_switch_to_light
                } else {
                    R.string.theme_switch_to_dark
                }
            )

        binding.buttonThemeToggle.isEnabled = true
    }

    /**
     * Configure the four NOVA shortcut actions.
     */
    private fun configureNovaActions() {
        configureDestinationClick(
            view = binding.buttonNavigateHome,
            destination = AppDestination.NAVIGATION
        )

        configureDestinationClick(
            view = binding.buttonPlayMusic,
            destination = AppDestination.MEDIA
        )

        configureDestinationClick(
            view = binding.buttonSetClimate,
            destination = AppDestination.CLIMATE
        )

        configureDestinationClick(
            view = binding.buttonCallContact,
            destination = AppDestination.PHONE
        )

        configureDestinationClick(
            view = binding.imageNovaOrb,
            destination = AppDestination.NOVA_AI
        )
    }

    /**
     * Configure the Navigation dashboard card.
     */
    private fun configureNavigationCard() {
        configureDestinationClick(
            view = binding.navigationCard,
            destination = AppDestination.NAVIGATION
        )
    }

    /**
     * Configure the Media card and real MediaSession controls.
     */
    private fun configureMediaCard() {
        configureDestinationClick(
            view = binding.mediaCard,
            destination = AppDestination.MEDIA
        )

        binding.buttonMediaPrevious.setOnClickListener {
            val commandSent =
                mediaSessionClient.skipToPrevious()

            if (!commandSent) {
                openHyperNovaApp(
                    AppDestination.MEDIA
                )
            }
        }

        binding.buttonMediaPlayPause.setOnClickListener {
            val commandSent =
                mediaSessionClient.playOrPause()

            if (!commandSent) {
                openHyperNovaApp(
                    AppDestination.MEDIA
                )
            }
        }

        binding.buttonMediaNext.setOnClickListener {
            val commandSent =
                mediaSessionClient.skipToNext()

            if (!commandSent) {
                openHyperNovaApp(
                    AppDestination.MEDIA
                )
            }
        }
    }

    /**
     * Configure Phone and Climate cards.
     */
    private fun configurePhoneAndClimateActions() {
        configureDestinationClick(
            view = binding.phoneCard,
            destination = AppDestination.PHONE
        )

        configureDestinationClick(
            view = binding.buttonOpenPhone,
            destination = AppDestination.PHONE
        )

        configureDestinationClick(
            view = binding.buttonPhoneContacts,
            destination = AppDestination.PHONE
        )

        configureDestinationClick(
            view = binding.climateCard,
            destination = AppDestination.CLIMATE
        )

        configureDestinationClick(
            view = binding.buttonOpenClimate,
            destination = AppDestination.CLIMATE
        )
    }

    /**
     * Configure Driver Profile actions.
     */
    private fun configureProfileActions() {
        configureDestinationClick(
            view = binding.imageDriverAvatar,
            destination = AppDestination.DRIVER_PROFILE
        )

        configureDestinationClick(
            view = binding.profileGroup,
            destination = AppDestination.DRIVER_PROFILE
        )

        configureDestinationClick(
            view = binding.driverCard,
            destination = AppDestination.DRIVER_PROFILE
        )

        configureDestinationClick(
            view = binding.imageDriverQuickAvatar,
            destination = AppDestination.DRIVER_PROFILE
        )
    }

    /**
     * Configure Weather and Settings quick cards.
     */
    private fun configureQuickCardActions() {
        configureDestinationClick(
            view = binding.weatherCard,
            destination = AppDestination.WEATHER
        )

        configureDestinationClick(
            view = binding.settingsCard,
            destination = AppDestination.SETTINGS
        )
    }

    /**
     * Configure the fixed bottom navigation.
     */
    private fun configureBottomNavigation() {
        binding.navHome.isClickable = true
        binding.navHome.isFocusable = true

        binding.navHome.setOnClickListener {
            showLauncherHome()
        }

        configureDestinationClick(
            view = binding.navNavigation,
            destination = AppDestination.NAVIGATION
        )

        configureDestinationClick(
            view = binding.navMedia,
            destination = AppDestination.MEDIA
        )

        configureDestinationClick(
            view = binding.navClimate,
            destination = AppDestination.CLIMATE
        )

        configureDestinationClick(
            view = binding.navNovaAi,
            destination = AppDestination.NOVA_AI
        )

        configureDestinationClick(
            view = binding.navPhone,
            destination = AppDestination.PHONE
        )

        configureDestinationClick(
            view = binding.navSettings,
            destination = AppDestination.SETTINGS
        )
    }

    /**
     * Attach one standard application click listener.
     */
    private fun configureDestinationClick(
        view: View,
        destination: AppDestination
    ) {
        view.isClickable = true
        view.isFocusable = true

        view.setOnClickListener {
            openHyperNovaApp(destination)
        }
    }

    /**
     * Return the launcher to the top of the Home screen.
     */
    private fun showLauncherHome() {
        binding.textNovaQuestion.removeCallbacks(
            resetFeedbackRunnable
        )

        refreshAndRenderState()

        binding.launcherScrollView.post {
            binding.launcherScrollView.smoothScrollTo(
                0,
                0
            )
        }

        Log.d(
            TAG,
            "Launcher Home selected"
        )
    }

    /**
     * Receive real MediaSession updates.
     */
    private fun handleMediaSnapshot(
        snapshot: MediaSessionSnapshot
    ) {
        runOnUiThread {
            Log.d(
                TAG,
                "Media snapshot received: $snapshot"
            )

            stateController.updateMediaSnapshot(
                snapshot
            )

            refreshAndRenderState()
        }
    }

    /**
     * Request a fresh state and render the complete launcher.
     */
    private fun refreshAndRenderState() {
        latestUiState =
            stateController.refresh()

        renderLauncherState(
            latestUiState
        )

        Log.d(
            TAG,
            "Launcher state refreshed: $latestUiState"
        )
    }

    /**
     * Render every launcher section.
     */
    private fun renderLauncherState(
        state: LauncherUiState
    ) {
        renderSystemState(state)
        renderAssistantState(state)
        renderNavigationState(state)
        renderMediaState(state.media)
        renderPhoneState(state.phone)
        renderClimateState(state.climate)
        renderWeatherState(state.weather)
        renderDriverState(state)
        renderSettingsState(state.settings)
    }

    /**
     * Render top status information.
     */
    private fun renderSystemState(
        state: LauncherUiState
    ) {
        binding.textSystemState.text =
            state.system.statusText

        binding.textOutsideTemperature.text =
            state.system.outsideTemperature

        binding.textNetworkType.text =
            state.system.networkText
    }

    /**
     * Render NOVA AI state.
     */
    private fun renderAssistantState(
        state: LauncherUiState
    ) {
        binding.textNovaGreeting.text =
            state.assistant.headline

        binding.textNovaQuestion.text =
            state.assistant.subtitle

        binding.textNovaQuestion.setTextColor(
            ContextCompat.getColor(
                this,
                R.color.hypernova_text_secondary
            )
        )

        binding.imageNovaOrb.visibility =
            if (state.assistant.artworkVisible) {
                View.VISIBLE
            } else {
                View.INVISIBLE
            }

        binding.imageNovaOrb.alpha =
            alphaForConnectionState(
                state.assistant.connectionState
            )

        animateNovaOrb(state.assistant.runtimeState)
    }

    /** Keep the launcher widget visually synchronized with NOVA. */
    private fun animateNovaOrb(state: AssistantRuntimeState) {
        if (animatedNovaState == state) return
        animatedNovaState = state
        novaOrbAnimator?.cancel()
        binding.imageNovaOrb.rotation = 0f
        binding.imageNovaOrb.scaleX = 1f
        binding.imageNovaOrb.scaleY = 1f

        novaOrbAnimator = when (state) {
            AssistantRuntimeState.LISTENING,
            AssistantRuntimeState.SPEAKING -> ObjectAnimator.ofPropertyValuesHolder(
                binding.imageNovaOrb,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.96f, 1.05f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.96f, 1.05f),
            ).apply {
                duration = if (state == AssistantRuntimeState.SPEAKING) 620L else 920L
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                start()
            }
            AssistantRuntimeState.PROCESSING,
            AssistantRuntimeState.EXECUTING -> ObjectAnimator.ofFloat(
                binding.imageNovaOrb,
                View.ROTATION,
                0f,
                360f,
            ).apply {
                duration = if (state == AssistantRuntimeState.PROCESSING) 7_000L else 4_500L
                interpolator = LinearInterpolator()
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
            else -> null
        }
    }

    /**
     * Restore the real assistant subtitle after feedback.
     */
    private fun restoreAssistantSubtitle() {
        binding.textNovaQuestion.text =
            latestUiState.assistant.subtitle

        binding.textNovaQuestion.setTextColor(
            ContextCompat.getColor(
                this,
                R.color.hypernova_text_secondary
            )
        )
    }

    /**
     * Render Navigation summary state.
     */
    private fun renderNavigationState(
        state: LauncherUiState
    ) {
        val navigation =
            state.navigation

        binding.navigationCard.alpha =
            alphaForConnectionState(
                navigation.connectionState
            )

        binding.textRouteDestination.text =
            navigation.destination

        binding.textRouteName.text =
            navigation.routeName

        binding.textRouteEta.text =
            navigation.eta

        binding.textRouteDistance.text =
            navigation.distance

        binding.textRouteArrival.text =
            navigation.arrivalTime

        binding.imageNavigationMap.visibility =
            if (navigation.previewVisible) {
                View.VISIBLE
            } else {
                View.INVISIBLE
            }

        binding.imageVehicleMarker.visibility =
            if (navigation.vehicleMarkerVisible) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    /**
     * Render Media summary state.
     */
    private fun renderMediaState(
        media: MediaUiState
    ) {
        binding.mediaCard.alpha =
            alphaForConnectionState(
                media.connectionState
            )

        binding.textMediaTrackTitle.text =
            media.title

        binding.textMediaArtist.text =
            media.artist

        binding.textMediaElapsed.text =
            media.elapsedTime

        binding.textMediaDuration.text =
            media.duration

        renderMediaArtwork(
            artworkUri = media.artworkUri,
            artworkVisible = media.artworkVisible
        )

        binding.buttonMediaPlayPause.setImageResource(
            if (media.isPlaying) {
                R.drawable.ic_pause
            } else {
                R.drawable.ic_play
            }
        )

        binding.buttonMediaPrevious.alpha =
            if (media.canSkipPrevious) {
                1.0f
            } else {
                0.35f
            }

        binding.buttonMediaPlayPause.alpha =
            if (media.canPlayPause) {
                1.0f
            } else {
                0.45f
            }

        binding.buttonMediaNext.alpha =
            if (media.canSkipNext) {
                1.0f
            } else {
                0.35f
            }

        binding.imageMediaEqualizer.alpha =
            if (media.isPlaying) {
                1.0f
            } else {
                0.35f
            }

        renderMediaProgress(
            media.progressFraction
        )
    }

    /**
     * Render Phone application state.
     *
     * The launcher must not show a fake contact or fake call.
     */
    private fun renderPhoneState(
        phone: SimpleAppUiState
    ) {
        binding.phoneCard.alpha =
            alphaForConnectionState(
                phone.connectionState
            )

        binding.textPhoneTitle.text =
            phone.title

        binding.textPhoneStatus.text =
            phone.statusMessage

        binding.imagePhoneConnection.alpha =
            alphaForConnectionState(
                phone.connectionState
            )

        /*
         * A real contact avatar becomes visible only after
         * PhoneClient provides a real contact snapshot.
         */
        binding.imagePhoneContactAvatar.visibility =
            View.INVISIBLE

        binding.imagePhonePlaceholder.visibility =
            View.VISIBLE

        /*
         * Do not claim that a recent contact exists yet.
         */
        binding.textPhonePreviewLabel.visibility =
            View.GONE

        val actionAlpha =
            actionAlphaForConnectionState(
                phone.connectionState
            )

        binding.buttonOpenPhone.alpha =
            actionAlpha

        binding.buttonPhoneContacts.alpha =
            actionAlpha
    }

    /**
     * Render Climate application state.
     *
     * Temperature, fan speed and AUTO mode stay unavailable
     * until ClimateClient supplies real vehicle data.
     */
    private fun renderClimateState(
        climate: SimpleAppUiState
    ) {
        binding.climateCard.alpha =
            alphaForConnectionState(
                climate.connectionState
            )

        binding.textClimateTemperature.text =
            getString(
                R.string.climate_temperature_unavailable
            )

        binding.textClimateFan.text =
            getString(
                R.string.climate_fan_unavailable
            )

        binding.textClimateStatus.text =
            climate.statusMessage

        val controlAlpha =
            actionAlphaForConnectionState(
                climate.connectionState
            )

        binding.textClimateAuto.alpha =
            controlAlpha

        binding.buttonOpenClimate.alpha =
            controlAlpha
    }

    /**
     * Render Weather quick-card state.
     */
    private fun renderWeatherState(
        weather: WeatherUiState
    ) {
        binding.weatherCard.alpha =
            alphaForConnectionState(
                weather.connectionState
            )

        binding.textWeatherTemperature.text =
            weather.temperature

        binding.textWeatherLocation.text =
            weather.location
    }

    /**
     * Render Driver Profile information.
     */
    private fun renderDriverState(
        state: LauncherUiState
    ) {
        binding.textDriverName.text =
            state.driver.displayName

        binding.textDriverQuickName.text =
            state.driver.displayName

        val avatarVisibility =
            if (state.driver.avatarVisible) {
                View.VISIBLE
            } else {
                View.INVISIBLE
            }

        binding.imageDriverAvatar.visibility =
            avatarVisibility

        binding.imageDriverQuickAvatar.visibility =
            avatarVisibility
    }

    /**
     * Render Settings application state.
     */
    private fun renderSettingsState(
        settings: SimpleAppUiState
    ) {
        binding.settingsCard.alpha =
            alphaForConnectionState(
                settings.connectionState
            )

        binding.textSettingsPrimary.text =
            settings.title

        binding.textSettingsSecondary.text =
            settings.statusMessage

        binding.imageSettingsQuick.alpha =
            alphaForConnectionState(
                settings.connectionState
            )
    }

    /**
     * Render real album artwork received from MediaSession.
     */
    private fun renderMediaArtwork(
        artworkUri: Uri?,
        artworkVisible: Boolean
    ) {
        if (
            !artworkVisible ||
            artworkUri == null
        ) {
            binding.imageAlbumArtwork.setImageDrawable(
                null
            )

            binding.imageAlbumArtwork.visibility =
                View.INVISIBLE

            return
        }

        try {
            binding.imageAlbumArtwork.setImageURI(
                null
            )

            binding.imageAlbumArtwork.setImageURI(
                artworkUri
            )

            binding.imageAlbumArtwork.visibility =
                View.VISIBLE
        } catch (exception: SecurityException) {
            Log.e(
                TAG,
                "Media artwork URI cannot be accessed",
                exception
            )

            binding.imageAlbumArtwork.setImageDrawable(
                null
            )

            binding.imageAlbumArtwork.visibility =
                View.INVISIBLE
        } catch (exception: Exception) {
            Log.e(
                TAG,
                "Could not display media artwork",
                exception
            )

            binding.imageAlbumArtwork.setImageDrawable(
                null
            )

            binding.imageAlbumArtwork.visibility =
                View.INVISIBLE
        }
    }

    /**
     * Render the Media progress line.
     */
    private fun renderMediaProgress(
        progressFraction: Float
    ) {
        val safeFraction =
            progressFraction.coerceIn(
                0.0f,
                1.0f
            )

        binding.mediaProgressTrack.post {
            val progressWidth = (
                    binding.mediaProgressTrack.width *
                            safeFraction
                    ).roundToInt()

            binding.mediaProgressFill.layoutParams =
                binding.mediaProgressFill
                    .layoutParams
                    .apply {
                        width = progressWidth
                    }

            binding.mediaProgressFill.requestLayout()
        }
    }

    /**
     * Return visual opacity for one application state.
     */
    private fun alphaForConnectionState(
        connectionState: AppConnectionState
    ): Float {
        return when (connectionState) {
            AppConnectionState.READY ->
                1.0f

            AppConnectionState.CONNECTING ->
                0.85f

            AppConnectionState.DISCONNECTED ->
                0.75f

            AppConnectionState.NO_LAUNCHABLE_ACTIVITY ->
                0.65f

            AppConnectionState.NOT_INSTALLED ->
                0.60f

            AppConnectionState.ERROR ->
                0.60f
        }
    }

    /**
     * Return opacity for application action controls.
     *
     * DISCONNECTED means the package exists but its service
     * has not connected, so opening the application is valid.
     */
    private fun actionAlphaForConnectionState(
        connectionState: AppConnectionState
    ): Float {
        return when (connectionState) {
            AppConnectionState.READY,
            AppConnectionState.CONNECTING,
            AppConnectionState.DISCONNECTED ->
                1.0f

            AppConnectionState.NOT_INSTALLED,
            AppConnectionState.NO_LAUNCHABLE_ACTIVITY,
            AppConnectionState.ERROR ->
                0.45f
        }
    }

    /**
     * Open one registered HyperNova application safely.
     */
    private fun openHyperNovaApp(
        destination: AppDestination
    ) {
        Log.d(
            TAG,
            "Opening destination: $destination"
        )

        val appName =
            resolveDestinationDisplayName(
                destination
            )

        val result =
            try {
                appLauncher.open(destination)
            } catch (exception: Exception) {
                Log.e(
                    TAG,
                    "Unexpected application launch error: " +
                            appName,
                    exception
                )

                showFeedback(
                    message = getString(
                        R.string.app_launch_failed_message,
                        appName
                    ),
                    isError = true
                )

                return
            }

        when (result) {
            is AppLaunchResult.Launched -> {
                Log.d(
                    TAG,
                    "$appName launched successfully"
                )
            }

            is AppLaunchResult.NotInstalled -> {
                showFeedback(
                    message = getString(
                        R.string.app_not_installed_message,
                        appName
                    ),
                    isError = true
                )
            }

            is AppLaunchResult.NoLaunchableActivity -> {
                showFeedback(
                    message = getString(
                        R.string.app_no_launch_activity_message,
                        appName
                    ),
                    isError = true
                )
            }

            is AppLaunchResult.Failed -> {
                Log.e(
                    TAG,
                    "Could not open $appName",
                    result.cause
                )

                showFeedback(
                    message = getString(
                        R.string.app_launch_failed_message,
                        appName
                    ),
                    isError = true
                )
            }
        }
    }

    /**
     * Resolve the visible name stored inside AppRegistry.
     */
    private fun resolveDestinationDisplayName(
        destination: AppDestination
    ): String {
        return try {
            val appSpec =
                AppRegistry.get(destination)

            getString(
                appSpec.displayNameResourceId
            )
        } catch (exception: Exception) {
            Log.e(
                TAG,
                "Could not resolve destination name: " +
                        destination,
                exception
            )

            destination.name
                .replace(
                    oldChar = '_',
                    newChar = ' '
                )
                .lowercase()
                .replaceFirstChar { character ->
                    character.uppercase()
                }
        }
    }

    /**
     * Display temporary feedback in NOVA and in a Toast.
     */
    private fun showFeedback(
        message: String,
        isError: Boolean
    ) {
        binding.textNovaQuestion.removeCallbacks(
            resetFeedbackRunnable
        )

        binding.textNovaQuestion.text =
            message

        val colorResourceId =
            if (isError) {
                R.color.hypernova_error
            } else {
                R.color.hypernova_cyan
            }

        binding.textNovaQuestion.setTextColor(
            ContextCompat.getColor(
                this,
                colorResourceId
            )
        )

        binding.textNovaQuestion.postDelayed(
            resetFeedbackRunnable,
            FEEDBACK_DURATION_MS
        )

        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()

        Log.d(
            TAG,
            "User feedback: $message"
        )
    }
}
