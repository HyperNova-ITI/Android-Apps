package com.hypernova.navigation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMap.CameraPerspective
import com.google.android.libraries.navigation.SupportNavigationFragment
import com.google.android.libraries.navigation.ForceNightMode
import com.google.android.material.button.MaterialButton
import com.hypernova.navigation.databinding.ActivityMainBinding
import com.hypernova.navigation.model.NavigationInitializationState
import com.hypernova.navigation.model.NavigationPhase
import com.hypernova.navigation.model.NavigationSessionState
import com.hypernova.navigation.persistence.DestinationTokenEntry
import com.hypernova.navigation.ui.NavigationViewModel
import com.hypernova.visuals.CockpitNavigationController
import kotlin.math.ceil
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: NavigationViewModel by viewModels()
    private var navigationFragment: SupportNavigationFragment? = null
    private var googleMap: GoogleMap? = null
    private var lastOverviewVersion = Long.MIN_VALUE

    private val locationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.attach(this, true) else viewModel.locationDenied()
        }

    private val notificationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startGuidanceWithCamera()
            else viewModel.reportMessage(getString(R.string.notification_permission_required))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CockpitNavigationController.bind(
            binding.cockpitNavigation,
            CockpitNavigationController.Destination.NAVIGATION,
        )
        configureActions()
        configureBackBehavior()
        if (viewModel.session.value.initialization != NavigationInitializationState.CONFIGURATION_REQUIRED) {
            ensureNavigationFragment()
        }
        viewModel.attach(this, hasFineLocationPermission())
        observeState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.attach(this, hasFineLocationPermission())
    }

    private fun ensureNavigationFragment() {
        navigationFragment =
            supportFragmentManager.findFragmentByTag(NAVIGATION_FRAGMENT_TAG)
                as? SupportNavigationFragment
        if (navigationFragment == null) {
            navigationFragment = SupportNavigationFragment.newInstance()
            supportFragmentManager.commitNow {
                replace(
                    R.id.navigationFragmentContainer,
                    requireNotNull(navigationFragment),
                    NAVIGATION_FRAGMENT_TAG,
                )
            }
        }
        navigationFragment?.apply {
            setHeaderEnabled(true)
            // HyperNova's SDK-driven route panel occupies the bottom edge. Disabling
            // the built-in ETA card makes GoogleMap bottom padding effective.
            setEtaCardEnabled(false)
            setTripProgressBarEnabled(true)
            setRecenterButtonEnabled(true)
            setTrafficIncidentCardsEnabled(true)
            setTrafficPromptsEnabled(true)
            setSpeedLimitIconEnabled(true)
            setSpeedometerEnabled(true)
            setForceNightMode(ForceNightMode.AUTO)
            getMapAsync { map ->
                googleMap = map
                map.isTrafficEnabled = true
                binding.root.post(::updateMapInsets)
            }
        }
    }

    private fun configureActions() {
        binding.searchButton.setOnClickListener { submitSearch() }
        binding.searchInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch()
                true
            } else {
                false
            }
        }
        binding.startGuidanceButton.setOnClickListener {
            if (hasNotificationPermission()) startGuidanceWithCamera()
            else notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        binding.cancelButton.setOnClickListener { viewModel.cancelNavigation() }
        binding.simulateButton.setOnClickListener { viewModel.startSimulation() }
        binding.configurationAction.setOnClickListener {
            when (viewModel.session.value.initialization) {
                NavigationInitializationState.LOCATION_UNAVAILABLE ->
                    locationPermissionRequest.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                NavigationInitializationState.TERMS_REQUIRED,
                NavigationInitializationState.GOOGLE_SERVICES_UNAVAILABLE,
                NavigationInitializationState.ERROR,
                NavigationInitializationState.INITIALIZING,
                -> viewModel.attach(this, hasFineLocationPermission())
                NavigationInitializationState.CONFIGURATION_REQUIRED,
                NavigationInitializationState.READY_IDLE,
                -> Unit
            }
        }
    }

    private fun configureBackBehavior() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.results.value.isNotEmpty()) {
                        viewModel.clearResults()
                    } else {
                        returnHome()
                    }
                }
            },
        )
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.session.collect(::renderSession) }
                launch { viewModel.results.collect(::renderResults) }
                launch {
                    viewModel.message.collect { value ->
                        binding.searchMessage.text = value.orEmpty()
                        binding.searchMessage.isVisible = !value.isNullOrBlank()
                        binding.root.post(::updateMapInsets)
                    }
                }
                launch {
                    viewModel.busy.collect { busy ->
                        binding.searchButton.isEnabled = !busy
                        binding.searchInput.isEnabled = !busy
                    }
                }
            }
        }
    }

    private fun renderSession(state: NavigationSessionState) {
        binding.statusText.text =
            if (state.simulated) getString(R.string.simulated_status) else state.statusMessage

        val ready = state.initialization == NavigationInitializationState.READY_IDLE
        val driving = state.phase in setOf(NavigationPhase.GUIDING, NavigationPhase.REROUTING)
        binding.searchPanel.isVisible = ready && !driving

        val initializationBlocking = !ready
        binding.configurationPanel.isVisible = initializationBlocking
        if (initializationBlocking) renderInitialization(state)

        val hasRoute = state.routeId.isNotBlank()
        val canSimulate = ready && viewModel.simulationAvailable && !hasRoute
        binding.routePanel.isVisible = hasRoute || canSimulate
        if (canSimulate) {
            binding.routeTitle.text = getString(R.string.simulate_action)
            binding.routeMetrics.text = getString(R.string.simulated_status)
            binding.simulateButton.isVisible = true
            binding.cancelButton.isVisible = false
            binding.startGuidanceButton.isVisible = false
        } else if (hasRoute) {
            binding.routeTitle.text = state.selectedDestination?.title.orEmpty()
            binding.routeMetrics.text = formatMetrics(state)
            binding.simulateButton.isVisible = false
            binding.cancelButton.isVisible = true
            binding.startGuidanceButton.isVisible = state.phase == NavigationPhase.PREVIEW_READY
        }
        binding.root.post {
            updateMapInsets()
            if (
                state.phase == NavigationPhase.PREVIEW_READY &&
                state.routeVersion != lastOverviewVersion
            ) {
                lastOverviewVersion = state.routeVersion
                navigationFragment?.showRouteOverview()
            }
        }
    }

    private fun renderInitialization(state: NavigationSessionState) {
        binding.configurationMessage.text = state.statusMessage
        when (state.initialization) {
            NavigationInitializationState.CONFIGURATION_REQUIRED -> {
                binding.configurationTitle.setText(R.string.configuration_required_title)
                binding.configurationAction.isVisible = false
            }
            NavigationInitializationState.TERMS_REQUIRED -> {
                binding.configurationTitle.setText(R.string.terms_required_title)
                binding.configurationAction.setText(R.string.review_terms_action)
                binding.configurationAction.isVisible = true
            }
            NavigationInitializationState.LOCATION_UNAVAILABLE -> {
                binding.configurationTitle.setText(R.string.location_required_title)
                binding.configurationAction.setText(R.string.grant_location_action)
                binding.configurationAction.isVisible = true
            }
            NavigationInitializationState.INITIALIZING -> {
                binding.configurationTitle.setText(R.string.navigation_initializing)
                binding.configurationAction.isVisible = false
            }
            NavigationInitializationState.GOOGLE_SERVICES_UNAVAILABLE,
            NavigationInitializationState.ERROR,
            -> {
                binding.configurationTitle.setText(R.string.navigation_unavailable_title)
                binding.configurationAction.setText(R.string.retry_action)
                binding.configurationAction.isVisible = true
            }
            NavigationInitializationState.READY_IDLE -> Unit
        }
    }

    private fun renderResults(results: List<DestinationTokenEntry>) {
        binding.searchResults.removeAllViews()
        results.forEach { entry ->
            val button =
                MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = buildString {
                        append(entry.record.title)
                        if (entry.record.subtitle.isNotBlank()) append("\n${entry.record.subtitle}")
                    }
                    contentDescription =
                        getString(
                            R.string.destination_result_content_description,
                            entry.record.title,
                            entry.record.subtitle,
                        )
                    isAllCaps = false
                    gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    minHeight = resources.getDimensionPixelSize(R.dimen.hn_navigation_touch_target)
                    setOnClickListener { viewModel.select(entry) }
                }
            binding.searchResults.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 8.dp },
            )
        }
        binding.root.post(::updateMapInsets)
    }

    private fun submitSearch() {
        viewModel.search(binding.searchInput.text?.toString().orEmpty())
    }

    private fun formatMetrics(state: NavigationSessionState): String {
        val eta =
            if (state.etaSeconds >= 0) {
                getString(R.string.eta_minutes_format, ceil(state.etaSeconds / 60.0).toLong())
            } else {
                "—"
            }
        val distance =
            if (state.distanceMeters >= 0) {
                getString(R.string.distance_km_format, state.distanceMeters / 1000.0)
            } else {
                "—"
            }
        return getString(R.string.route_metrics_format, eta, distance)
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun startGuidanceWithCamera() {
        if (viewModel.startGuidance()) {
            navigationFragment?.getMapAsync { map ->
                if (hasFineLocationPermission()) {
                    try {
                        map.followMyLocation(CameraPerspective.TILTED)
                    } catch (_: SecurityException) {
                        viewModel.locationDenied()
                    }
                }
            }
        }
    }

    /**
     * Keeps Google controls, attribution, legal notices, and camera calculations
     * inside the map area that is not obscured by HyperNova overlays.
     */
    private fun updateMapInsets() {
        val top =
            if (binding.searchPanel.isVisible) {
                (binding.searchPanel.bottom + 8.dp).coerceAtMost(binding.navigationFragmentContainer.height)
            } else {
                0
            }
        val bottom =
            if (binding.routePanel.isVisible) {
                (binding.navigationFragmentContainer.height - binding.routePanel.top + 16.dp)
                    .coerceAtLeast(0)
            } else {
                0
            }
        googleMap?.setPadding(0, top, 0, bottom)
    }

    private fun returnHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            },
        )
        moveTaskToBack(true)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val NAVIGATION_FRAGMENT_TAG = "hypernova_google_navigation_surface"
    }
}
