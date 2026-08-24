package com.hypernova.launcher

import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hypernova.launcher.core.assistant.NovaAssistantStateParser
import com.hypernova.launcher.core.assistant.NovaContextCardFactory
import com.hypernova.launcher.core.assistant.NovaEvidenceCard
import com.hypernova.launcher.core.assistant.NovaStatusClient
import com.hypernova.launcher.core.climate.ClimateStatusClient
import com.hypernova.launcher.core.dashboard.DashboardCard
import com.hypernova.launcher.core.dashboard.DashboardLayoutOrder
import com.hypernova.launcher.core.integration.AppAvailability
import com.hypernova.launcher.core.integration.AppAvailabilityMonitor
import com.hypernova.launcher.core.integration.AppDestination
import com.hypernova.launcher.core.integration.AppLaunchResult
import com.hypernova.launcher.core.integration.AppLauncher
import com.hypernova.launcher.core.integration.AppRegistry
import com.hypernova.launcher.core.media.MediaSessionClient
import com.hypernova.launcher.core.media.MediaSessionSnapshot
import com.hypernova.launcher.core.navigation.NavigationStatusClient
import com.hypernova.launcher.core.phone.PhoneStatusClient
import com.hypernova.launcher.core.settings.SystemSettingsClient
import com.hypernova.launcher.core.state.AppConnectionState
import com.hypernova.launcher.core.state.AssistantRuntimeState
import com.hypernova.launcher.core.state.ClimateUiState
import com.hypernova.launcher.core.state.IntegratedAppState
import com.hypernova.launcher.core.state.LauncherStateController
import com.hypernova.launcher.core.state.LauncherUiState
import com.hypernova.launcher.core.state.MediaUiState
import com.hypernova.launcher.core.state.PhoneUiState
import com.hypernova.launcher.core.state.RuntimeConnectionState
import com.hypernova.launcher.core.state.SettingsUiState
import com.hypernova.launcher.core.theme.LauncherThemeController
import com.hypernova.launcher.core.vehicle.VehicleStatusClient
import com.hypernova.launcher.databinding.ActivityMainBinding
import com.hypernova.launcher.ui.LauncherNavigationMapController
import com.hypernova.launcher.ui.LauncherSoftwareRouteOverlay
import com.hypernova.visuals.CockpitAppearance
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HyperNovaLauncher"
        private const val FEEDBACK_DURATION_MS = 4000L
        private const val OPTIONAL_INTEGRATION_DELAY_MS = 250L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var appLauncher: AppLauncher
    private lateinit var stateController: LauncherStateController
    private lateinit var mediaSessionClient: MediaSessionClient
    private lateinit var novaStatusClient: NovaStatusClient
    private lateinit var navigationStatusClient: NavigationStatusClient
    private lateinit var climateStatusClient: ClimateStatusClient
    private lateinit var phoneStatusClient: PhoneStatusClient
    private lateinit var systemSettingsClient: SystemSettingsClient
    private lateinit var vehicleStatusClient: VehicleStatusClient
    private lateinit var availabilityMonitor: AppAvailabilityMonitor
    private lateinit var themeController: LauncherThemeController
    private lateinit var latestUiState: LauncherUiState
    private var previousAssistantRuntimeState: AssistantRuntimeState? = null
    private var navigationMapView: MapView? = null
    private var navigationMapController: LauncherNavigationMapController? = null

    /*
     * Shown only when the real MapLibre renderer is unavailable.
     * Keeps the HOME widget useful and clickable on the RPi.
     */
    private var navigationIdleFallbackOverlay: android.widget.TextView? = null
    private var cockpitIntegrationsReady = false
    private var activityStarted = false
    private var activityResumed = false
    private var novaTypingAnimator: ValueAnimator? = null
    private var novaTextTarget = ""
    private var visibleNovaContextDomain: String? = null
    private var activeNovaContextDestination: AppDestination? = null

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

        // Arrange the existing approved cards in the production driving order.
        configureResponsiveDashboardLayout()

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

        navigationStatusClient =
            NavigationStatusClient(this, appLauncher) { snapshot ->
                runOnUiThread {
                    stateController.updateNavigationSnapshot(snapshot)
                    refreshAndRenderState()
                }
            }

        climateStatusClient =
            ClimateStatusClient(this, appLauncher) { snapshot ->
                runOnUiThread {
                    stateController.updateClimateSnapshot(snapshot)
                    refreshAndRenderState()
                }
            }

        phoneStatusClient =
            PhoneStatusClient(this) { snapshot ->
                runOnUiThread {
                    stateController.updatePhoneSnapshot(snapshot)
                    refreshAndRenderState()
                }
            }

        systemSettingsClient =
            SystemSettingsClient(this) { snapshot ->
                runOnUiThread {
                    stateController.updateSettingsSnapshot(snapshot)
                    refreshAndRenderState()
                }
            }

        // Live TC397 telemetry for the status bar. Read-only: HOME never actuates the vehicle.
        vehicleStatusClient =
            VehicleStatusClient(this) { snapshot ->
                runOnUiThread {
                    stateController.updateVehicleSnapshot(snapshot)
                    refreshAndRenderState()
                }
            }

        availabilityMonitor =
            AppAvailabilityMonitor(this, ::handlePackageChanged)

        novaStatusClient =
            NovaStatusClient(this) { snapshot ->
                runOnUiThread {
                    val runtimeState = NovaAssistantStateParser.parse(snapshot.state)
                    stateController.updateAssistantSnapshot(snapshot)
                    refreshAndRenderState()
                    // Climate exposes a frozen one-shot status query rather than a status
                    // observer. Refresh after a completed NOVA action so the Launcher card and
                    // the spoken confirmation always describe the same cockpit state.
                    if (
                        runtimeState == AssistantRuntimeState.SUCCESS &&
                        previousAssistantRuntimeState != AssistantRuntimeState.SUCCESS
                    ) {
                        climateStatusClient.refresh()
                    }
                    previousAssistantRuntimeState = runtimeState
                }
            }

        configureFullScreenMode()
        configureThemeToggle()
        configureNovaActions()
        configureNavigationCard()
        configureMediaCard()
        configureClimateActions()
        configureBottomNavigation()

        refreshAndRenderState()

        /*
         * Commit a usable HyperNova frame before loading MapLibre or touching
         * optional cockpit services. Android dispatches onStart/onResume before
         * the first draw, so those external connections are gated as well.
         */
        binding.root.postDelayed(
            {
                if (isFinishing || isDestroyed) return@postDelayed
                initializeNavigationMap(savedInstanceState)
                cockpitIntegrationsReady = true
                if (activityStarted) connectCockpitIntegrations()
            },
            OPTIONAL_INTEGRATION_DELAY_MS,
        )

        Log.d(
            TAG,
            "HyperNova Launcher started"
        )
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true

        /*
         * MapView lifecycle must mirror Activity lifecycle.
         * onStop() already stops it, so returning to HOME must call onStart()
         * before onResume(). Missing this transition is what leaves the
         * Launcher MapLibre surface black after returning from Navigation.
         */
        runOptionalIntegration("start Navigation map") {
            navigationMapView?.onStart()
        }
        if (cockpitIntegrationsReady) connectCockpitIntegrations()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        runOptionalIntegration("resume Navigation map") {
            navigationMapView?.onResume()
            navigationMapView?.post {
                navigationMapController?.refreshScene()
            }
        }

        if (::themeController.isInitialized) {
            renderThemeToggle()
        }

        if (
            cockpitIntegrationsReady &&
            ::appLauncher.isInitialized &&
            ::stateController.isInitialized
        ) {
            runOptionalIntegration("refresh Navigation") { navigationStatusClient.refresh() }
            runOptionalIntegration("refresh Climate") { climateStatusClient.refresh() }
            runOptionalIntegration("refresh Phone") { phoneStatusClient.refresh() }
            runOptionalIntegration("refresh Settings") { systemSettingsClient.refresh() }
            refreshAndRenderState()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::binding.isInitialized) {
            runOptionalIntegration("restore immersive cockpit mode") {
                configureFullScreenMode()
            }
        }
    }

    override fun onStop() {
        activityStarted = false
        if (::availabilityMonitor.isInitialized) {
            runOptionalIntegration("stop package monitor") { availabilityMonitor.stop() }
        }

        if (::vehicleStatusClient.isInitialized) {
            runOptionalIntegration("disconnect Vehicle Gateway") { vehicleStatusClient.stop() }
        }

        if (::systemSettingsClient.isInitialized) {
            runOptionalIntegration("disconnect Settings") { systemSettingsClient.disconnect() }
        }

        if (::phoneStatusClient.isInitialized) {
            runOptionalIntegration("disconnect Phone") { phoneStatusClient.disconnect() }
        }

        if (::climateStatusClient.isInitialized) {
            runOptionalIntegration("disconnect Climate") { climateStatusClient.disconnect() }
        }

        if (::navigationStatusClient.isInitialized) {
            runOptionalIntegration("disconnect Navigation") { navigationStatusClient.disconnect() }
        }

        if (::novaStatusClient.isInitialized) {
            runOptionalIntegration("disconnect NOVA") { novaStatusClient.disconnect() }
        }

        if (::mediaSessionClient.isInitialized) {
            Log.d(
                TAG,
                "Releasing HyperNova MediaController"
            )

            runOptionalIntegration("disconnect Media") { mediaSessionClient.disconnect() }
        }

        runOptionalIntegration("stop Navigation map") { navigationMapView?.onStop() }

        super.onStop()
    }

    override fun onPause() {
        activityResumed = false
        runOptionalIntegration("pause Navigation map") { navigationMapView?.onPause() }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        runOptionalIntegration("save Navigation map state") {
            navigationMapView?.onSaveInstanceState(outState)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runOptionalIntegration("trim Navigation map") { navigationMapView?.onLowMemory() }
    }

    override fun onDestroy() {
        novaTypingAnimator?.cancel()
        if (::binding.isInitialized) {
            binding.textNovaQuestion.removeCallbacks(
                resetFeedbackRunnable
            )
        }


        runOptionalIntegration("destroy Navigation map controller") {
            navigationMapController?.destroy()
        }
        navigationMapController = null
        runOptionalIntegration("destroy Navigation map") { navigationMapView?.onDestroy() }
        navigationMapView = null

        super.onDestroy()
    }

    /**
     * Keep only driving-priority widgets on HOME:
     *
     *   Climate | Media
     *   Navigation
     *
     * Phone and Settings remain available from the fixed bottom navigation bar.
     * Their previous dashboard row is completely collapsed so Navigation can
     * consume the released vertical space.
     */
    private fun configureResponsiveDashboardLayout() {
        val cards = mapOf(
            DashboardCard.CLIMATE to binding.climateCard,
            DashboardCard.MEDIA to binding.mediaCard,
            DashboardCard.NAVIGATION to binding.navigationCard,
        )

        binding.climateMediaRow.removeAllViews()

        // Phone and Settings are controlled from the bottom bar only.
        binding.settingsPhoneRow.removeAllViews()
        binding.settingsPhoneRow.visibility = View.GONE

        binding.navigationDashboardRow.removeAllViews()

        addHalfWidthRow(
            binding.climateMediaRow,
            DashboardLayoutOrder.firstRow,
            cards,
        )

        binding.navigationDashboardRow.addView(
            requireNotNull(cards[DashboardLayoutOrder.dominantRow.single()]),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun addHalfWidthRow(
        row: LinearLayout,
        order: List<DashboardCard>,
        cards: Map<DashboardCard, View>,
    ) {
        order.forEachIndexed { index, card ->
            row.addView(
                requireNotNull(cards[card]),
                halfWidthCardParams(isLeft = index == 0),
            )
        }
    }

    private fun halfWidthCardParams(isLeft: Boolean): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.MATCH_PARENT,
            1f,
        ).apply {
            if (isLeft) rightMargin = dp(4) else leftMargin = dp(4)
        }

    private fun initializeNavigationMap(savedInstanceState: Bundle?) {
        /*
         * RPi5:
         *
         * Do not require Vulkan advertisement before creating MapLibre.
         * Let MapLibre/native graphics choose the available Android graphics
         * path. If creation still fails, the existing Canvas fallback remains.
         */
        Log.i(TAG, "RPi Navigation: attempting MapLibre without Vulkan gate")

        runCatching {
            MapLibre.getInstance(this)
            val mapView = MapView(this).also { view ->
                view.contentDescription = getString(R.string.navigation_map_description)
                view.isClickable = true
                view.isFocusable = true
                view.setOnClickListener {
                    openHyperNovaApp(AppDestination.NAVIGATION)
                }
                binding.navigationMapContainer.addView(
                    view,
                    0,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                view.onCreate(savedInstanceState)
            }
            navigationMapView = mapView
            val routeOverlay = LauncherSoftwareRouteOverlay(this)
            binding.navigationMapContainer.addView(
                routeOverlay,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )

            /*
             * Real MapLibre is available.
             * Remove the temporary fixed-point overlay completely so it can
             * never obscure roads, labels, buildings or map touch events.
             */
            navigationIdleFallbackOverlay?.let { oldFallback ->
                (oldFallback.parent as? android.view.ViewGroup)
                    ?.removeView(oldFallback)
            }
            navigationIdleFallbackOverlay = null

            /*
             * MapView is now real and attached.
             * Bind Navigation click HERE rather than relying on the earlier
             * nullable navigationMapView reference.
             */
            mapView.isClickable = true
            mapView.isFocusable = true
            mapView.setOnClickListener {
                openNavigationFromHomeWidget()
            }
            navigationMapController =
                LauncherNavigationMapController(this, mapView, routeOverlay).also { controller ->
                    controller.initialize(
                        isNightMode = themeController.isNightModeActive(),
                    ) { available ->
                        runOnUiThread {
                            stateController.updateNavigationMapAvailability(available)
                            if (::latestUiState.isInitialized) refreshAndRenderState()
                        }
                    }
                }
            /*
             * Transparent touch target above MapLibre.
             * MapLibre owns an internal renderer hierarchy, so relying only
             * on parent/card clicks is not deterministic.
             */
            binding.navigationMapContainer
                .findViewWithTag<View>("hypernova_navigation_map_click_overlay")
                ?.let { oldOverlay ->
                    binding.navigationMapContainer.removeView(oldOverlay)
                }

            val navigationClickOverlay =
                View(this).apply {
                    tag = "hypernova_navigation_map_click_overlay"
                    isClickable = true
                    isFocusable = true
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setOnClickListener {
                        openHyperNovaApp(AppDestination.NAVIGATION)
                    }
                }

            binding.navigationMapContainer.addView(
                navigationClickOverlay,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )

            if (activityStarted) mapView.onStart()
            if (activityResumed) mapView.onResume()
        }.onFailure { failure ->
            Log.w(TAG, "Read-only Navigation map unavailable; retaining Canvas fallback", failure)
            stateController.updateNavigationMapAvailability(false)
        }
    }

    /** Connect each optional integration independently so one failure cannot take down HOME. */
    private fun connectCockpitIntegrations() {
        runOptionalIntegration("start package monitor") { availabilityMonitor.start() }
        runOptionalIntegration("connect Navigation") { navigationStatusClient.connect() }
        runOptionalIntegration("connect Climate") { climateStatusClient.connect() }
        runOptionalIntegration("connect Phone") { phoneStatusClient.connect() }
        runOptionalIntegration("connect Settings") { systemSettingsClient.connect() }
        runOptionalIntegration("connect Vehicle Gateway") { vehicleStatusClient.start() }
        runOptionalIntegration("connect Media") {
            Log.d(TAG, "Connecting to HyperNova MediaSession")
            mediaSessionClient.connect()
        }
        runOptionalIntegration("connect NOVA") { novaStatusClient.connect() }
    }

    /** Catch runtime and optional native renderer failures at the integration boundary. */
    private inline fun runOptionalIntegration(operation: String, block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            Log.w(TAG, "Could not $operation; keeping Launcher available", failure)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

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
            // Read the target before toggling: afterwards this activity is being recreated and
            // isNightModeActive() no longer describes what the driver asked for.
            val target = if (themeController.isNightModeActive()) {
                CockpitAppearance.MODE_LIGHT
            } else {
                CockpitAppearance.MODE_DARK
            }
            themeController.toggleTheme()
            // Remember it for this process too, so a launcher restart comes back the same way.
            CockpitAppearance.apply(this, target)
            CockpitAppearance.broadcast(this, target)
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
            view = binding.novaFace,
            destination = AppDestination.NOVA_AI
        )

        val openContext = View.OnClickListener {
            activeNovaContextDestination?.let(::openHyperNovaApp)
        }
        binding.novaContextCard.setOnClickListener(openContext)
        binding.buttonNovaContextOpen.setOnClickListener(openContext)
    }

    /**
     * Configure the Navigation dashboard card.
     */
    private fun configureNavigationCard() {
        /*
         * The complete Navigation map area opens HyperNova Navigation.
         *
         * This listener belongs to the container, not only MapView, so it
         * also works when the RPi is using the Canvas fallback.
         */
        binding.navigationMapContainer.isClickable = true
        binding.navigationMapContainer.isFocusable = true
        binding.navigationMapContainer.setOnClickListener {
            openNavigationFromHomeWidget()
        }

        configureDestinationClick(
            view = binding.navigationCard,
            destination = AppDestination.NAVIGATION
        )

        navigationMapView?.setOnClickListener {
            openHyperNovaApp(AppDestination.NAVIGATION)
        }
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
     * Configure the Climate HOME widget.
     *
     * Phone and Settings are intentionally bottom-bar-only destinations.
     */
    private fun configureClimateActions() {
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
     * Configure the fixed bottom navigation.
     */
    /**
     * Open HyperNova Navigation directly from the HOME Navigation widget.
     */
    private fun openNavigationFromHomeWidget() {
        val openIntent =
            android.content.Intent(
                "com.hypernova.navigation.action.OPEN"
            ).apply {
                setPackage("com.hypernova.navigation")
            }

        runCatching {
            startActivity(openIntent)
        }.onFailure { primaryFailure ->
            Log.w(
                TAG,
                "Navigation OPEN action failed; trying launcher activity",
                primaryFailure,
            )

            val fallbackIntent =
                packageManager.getLaunchIntentForPackage(
                    "com.hypernova.navigation"
                )

            if (fallbackIntent != null) {
                runCatching {
                    startActivity(fallbackIntent)
                }.onFailure { fallbackFailure ->
                    Log.e(
                        TAG,
                        "Could not open HyperNova Navigation",
                        fallbackFailure,
                    )
                }
            } else {
                Log.e(
                    TAG,
                    "HyperNova Navigation has no launch intent",
                )
            }
        }
    }

    /**
     * Deterministic idle fallback for RPi graphics configurations where the
     * MapLibre surface cannot become available.
     *
     * It is not used when MapLibre is healthy.
     */
    private fun ensureNavigationIdleFallbackOverlay() {
        if (navigationIdleFallbackOverlay != null) return

        val nightMask =
            resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK

        val isNight =
            nightMask ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        val overlay =
            android.widget.TextView(this).apply {
                text =
                    "●\nITI DEMO LOCATION\n30.07112, 31.02075"

                gravity = android.view.Gravity.CENTER

                textSize = 16f

                setTextColor(
                    if (isNight) {
                        android.graphics.Color.rgb(
                            67,
                            242,
                            244,
                        )
                    } else {
                        android.graphics.Color.rgb(
                            11,
                            132,
                            147,
                        )
                    }
                )

                setBackgroundColor(
                    if (isNight) {
                        android.graphics.Color.rgb(
                            7,
                            21,
                            33,
                        )
                    } else {
                        android.graphics.Color.rgb(
                            238,
                            243,
                            247,
                        )
                    }
                )

                setPadding(
                    24,
                    24,
                    24,
                    24,
                )

                isClickable = true
                isFocusable = true

                setOnClickListener {
                    openNavigationFromHomeWidget()
                }

                elevation = 12f
            }

        binding.navigationMapContainer.addView(
            overlay,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        navigationIdleFallbackOverlay = overlay
    }

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

    /** Refresh availability immediately after an adb/package-manager change. */
    private fun handlePackageChanged(packageName: String) {
        runOnUiThread {
            val destination = AppRegistry.getAll()
                .firstOrNull { it.packageName == packageName }
                ?.destination

            when (destination) {
                AppDestination.NAVIGATION -> {
                    navigationStatusClient.disconnect()
                    navigationStatusClient.connect()
                }
                AppDestination.MEDIA -> mediaSessionClient.connect()
                AppDestination.CLIMATE -> {
                    climateStatusClient.disconnect()
                    climateStatusClient.connect()
                }
                AppDestination.PHONE -> phoneStatusClient.refresh()
                AppDestination.SETTINGS -> systemSettingsClient.refresh()
                AppDestination.NOVA_AI -> {
                    novaStatusClient.disconnect()
                    novaStatusClient.connect()
                }
                else -> Unit
            }

            refreshAndRenderState()
            Log.d(TAG, "Package state changed: $packageName")
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
        renderSettingsState(state.settings)
    }

    /**
     * Render top status information.
     */
    private fun renderSystemState(
        state: LauncherUiState
    ) {
        binding.textOutsideTemperature.text =
            state.system.outsideTemperature
    }

    /**
     * Render NOVA AI state.
     */
    private fun renderAssistantState(
        state: LauncherUiState
    ) {
        // The status chip is the single state indicator for this card. The all-caps eyebrow that
        // used to sit under the face said the same thing in different words.
        binding.textNovaStatusChip.text = when (state.assistant.runtimeState) {
            AssistantRuntimeState.IDLE -> "READY"
            AssistantRuntimeState.LISTENING -> "LISTENING"
            AssistantRuntimeState.PROCESSING -> "THINKING"
            AssistantRuntimeState.EXECUTING -> "ACTING"
            AssistantRuntimeState.SUCCESS -> "DONE"
            AssistantRuntimeState.ERROR -> "ATTENTION"
            AssistantRuntimeState.SPEAKING -> "SPEAKING"
            AssistantRuntimeState.UNAVAILABLE -> "OFFLINE"
        }

        renderNovaPrimaryMessage(
            text = state.assistant.primaryMessage,
            animate = state.assistant.speaking ||
                state.assistant.runtimeState == AssistantRuntimeState.SUCCESS,
        )

        binding.textNovaTranscript.text = state.assistant.transcript?.let {
            getString(R.string.nova_transcript_format, it)
        }.orEmpty()
        binding.textNovaTranscript.visibility =
            if (state.assistant.transcript.isNullOrBlank()) View.GONE else View.VISIBLE

        binding.textNovaSecondary.text = state.assistant.secondaryMessage.orEmpty()
        binding.textNovaSecondary.visibility =
            if (state.assistant.secondaryMessage.isNullOrBlank()) View.GONE else View.VISIBLE

        binding.novaActivityProgress.visibility =
            if (state.assistant.showActivityProgress) View.VISIBLE else View.GONE
        renderNovaEvidence(state.assistant.evidenceCards)

        binding.textNovaQuestion.setTextColor(
            ContextCompat.getColor(
                this,
                R.color.hypernova_text_secondary
            )
        )

        binding.novaFace.visibility =
            if (state.assistant.artworkVisible) {
                View.VISIBLE
            } else {
                View.INVISIBLE
            }

        binding.novaFace.alpha =
            alphaForConnectionState(
                state.assistant.connectionState
            )

        binding.novaFace.setPalette(
            accent = ContextCompat.getColor(this, R.color.hypernova_cyan),
            secondaryAccent = ContextCompat.getColor(this, R.color.hypernova_purple),
            success = ContextCompat.getColor(this, R.color.hypernova_success),
            warning = ContextCompat.getColor(this, R.color.hypernova_warning),
            error = ContextCompat.getColor(this, R.color.hypernova_error),
        )
        binding.novaFace.setStateName(state.assistant.runtimeState.name)
        renderNovaContext(state)
    }

    private fun renderNovaEvidence(cards: List<NovaEvidenceCard>) = with(binding) {
        novaEvidenceContainer.removeAllViews()
        novaEvidenceScroller.visibility = if (cards.isEmpty()) View.GONE else View.VISIBLE
        cards.take(4).forEach { card ->
            val view = layoutInflater.inflate(
                R.layout.item_nova_evidence,
                novaEvidenceContainer,
                false,
            )
            view.findViewById<TextView>(R.id.textEvidenceTitle).text =
                "${card.index}. ${card.title}"
            view.findViewById<TextView>(R.id.textEvidenceDetail).apply {
                text = card.detail.orEmpty()
                visibility = if (card.detail.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            view.findViewById<TextView>(R.id.textEvidenceSource).text = card.source
            card.sourceUri?.let { uri ->
                view.isClickable = true
                view.isFocusable = true
                view.setOnClickListener {
                    runCatching {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(uri)))
                    }
                }
            }
            novaEvidenceContainer.addView(view)
        }
    }

    private fun renderNovaPrimaryMessage(text: String, animate: Boolean) {
        if (text == novaTextTarget) return
        novaTextTarget = text
        novaTypingAnimator?.cancel()
        binding.textNovaQuestion.contentDescription = text

        if (!animate || text.length < 12) {
            binding.textNovaQuestion.text = text
            return
        }

        binding.textNovaQuestion.text = ""
        novaTypingAnimator = ValueAnimator.ofInt(0, text.length).apply {
            duration = (text.length * 22L).coerceIn(450L, 2_800L)
            addUpdateListener { animation ->
                val end = (animation.animatedValue as Int).coerceIn(0, text.length)
                binding.textNovaQuestion.text = text.substring(0, end)
            }
            start()
        }
    }

    private fun renderNovaContext(state: LauncherUiState) {
        val card = NovaContextCardFactory.create(state)
        if (card == null) {
            activeNovaContextDestination = null
            visibleNovaContextDomain = null
            binding.novaContextCard.animate().cancel()
            binding.novaContextCard.visibility = View.GONE
            return
        }

        activeNovaContextDestination = card.destination
        binding.textNovaContextLabel.text = card.label
        binding.textNovaContextTitle.text = card.title
        binding.textNovaContextDetail.text = card.detail
        binding.textNovaContextMetadata.text = card.metadata.orEmpty()
        binding.textNovaContextMetadata.visibility =
            if (card.metadata.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.buttonNovaContextOpen.visibility =
            if (card.destination == null) View.GONE else View.VISIBLE
        binding.novaContextCard.isClickable = card.destination != null
        binding.imageNovaContextIcon.setImageResource(
            when (card.domain) {
                "navigation" -> R.drawable.ic_nav_navigation
                "media" -> R.drawable.ic_nav_media
                "phone" -> R.drawable.ic_nav_phone
                "climate" -> R.drawable.ic_nav_climate
                else -> R.drawable.ic_nav_nova
            },
        )

        if (binding.novaContextCard.visibility != View.VISIBLE) {
            binding.novaContextCard.visibility = View.VISIBLE
        }
        if (visibleNovaContextDomain != card.domain) {
            visibleNovaContextDomain = card.domain
            binding.novaContextCard.alpha = 0f
            binding.novaContextCard.translationY = dp(10).toFloat()
            binding.novaContextCard.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .start()
        }
    }

    /**
     * Restore the real assistant subtitle after feedback.
     */
    private fun restoreAssistantSubtitle() {
        novaTextTarget = ""
        renderNovaPrimaryMessage(
            latestUiState.assistant.primaryMessage,
            animate = false,
        )

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
            alphaForIntegratedApp(
                navigation.appState
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

        if (navigation.routePoints.size >= 2) {
            binding.navigationRoutePreview.setRoute(
                navigation.routePoints,
                null,
                null,
            )
            navigationMapController?.setNavigation(
                navigation.routeId,
                navigation.routeVersion,
                navigation.routePoints,
                null,
                null,
            )
        } else {
            binding.navigationRoutePreview.clearRoute()
            navigationMapController?.clearNavigation()
        }


        val showMap = navigation.mapAvailable && navigation.routePoints.size >= 2
        /*
         * HOME always displays the real MapLibre map.
         * Route presence only changes the geometry drawn on top.
         */
        navigationMapView?.visibility = View.VISIBLE

        /*
         * Actual MapLibre renderer wins whenever it is available.
         * Otherwise show the fixed ITI fallback only in idle/no-route state.
         */
        /*
         * MapLibre is the HOME Navigation renderer in both idle and
         * active-route states.
         *
         * The temporary fixed-point TextView fallback must never cover
         * the actual OpenFreeMap surface.
         */
        navigationIdleFallbackOverlay?.visibility = View.GONE
        binding.navigationRoutePreview.visibility =
            if (navigationMapView != null) {
                View.INVISIBLE
            } else {
                View.VISIBLE
            }
    }

    /**
     * Render Media summary state.
     */
    private fun renderMediaState(
        media: MediaUiState
    ) {
        binding.mediaCard.alpha =
            alphaForIntegratedApp(
                media.appState
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
        phone: PhoneUiState
    ) {
        binding.phoneCard.alpha =
            alphaForIntegratedApp(
                phone.appState
            )

        binding.textPhoneTitle.text =
            phone.title

        binding.textPhoneStatus.text =
            phone.statusMessage

        binding.imagePhoneConnection.alpha =
            if (phone.bluetoothEnabled == true) 1.0f else 0.35f

        binding.imagePhonePlaceholder.visibility =
            View.VISIBLE

        val actionAlpha =
            actionAlphaForIntegratedApp(
                phone.appState
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
        climate: ClimateUiState
    ) {
        binding.climateCard.alpha =
            alphaForIntegratedApp(
                climate.appState
            )

        binding.textClimateTemperature.text =
            climate.temperature

        binding.textClimateFan.text =
            climate.fan

        binding.textClimateStatus.text =
            climate.statusMessage

        val controlAlpha =
            actionAlphaForIntegratedApp(
                climate.appState
            )

        binding.textClimateAuto.alpha =
            if (climate.autoModeEnabled == true) 1.0f else 0.45f

        binding.buttonOpenClimate.alpha =
            controlAlpha
    }

    /**
     * Render Settings application state.
     */
    private fun renderSettingsState(
        settings: SettingsUiState
    ) {
        binding.settingsCard.alpha =
            alphaForIntegratedApp(
                settings.appState
            )

        binding.textSettingsPrimary.text =
            settings.primaryText

        binding.textSettingsSecondary.text =
            settings.secondaryText

        binding.imageSettingsQuick.alpha =
            alphaForIntegratedApp(
                settings.appState
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

    private fun alphaForIntegratedApp(appState: IntegratedAppState): Float {
        return when (appState.availability) {
            AppAvailability.NOT_INSTALLED -> 0.60f
            AppAvailability.NO_LAUNCHABLE_ACTIVITY -> 0.65f
            AppAvailability.ERROR -> 0.60f
            AppAvailability.AVAILABLE -> when (appState.connectionState) {
                RuntimeConnectionState.CONNECTED -> 1.0f
                RuntimeConnectionState.CONNECTING -> 0.85f
                RuntimeConnectionState.DISCONNECTED -> 0.75f
                RuntimeConnectionState.ERROR -> 0.60f
            }
        }
    }

    private fun actionAlphaForIntegratedApp(appState: IntegratedAppState): Float {
        return if (appState.availability == AppAvailability.AVAILABLE) {
            1.0f
        } else {
            0.45f
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
        novaTextTarget = message
        novaTypingAnimator?.cancel()

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
