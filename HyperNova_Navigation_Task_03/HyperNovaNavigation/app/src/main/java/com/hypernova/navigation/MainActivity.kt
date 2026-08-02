package com.hypernova.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hypernova.navigation.data.persistence.NavigationPreferences
import com.hypernova.navigation.databinding.ActivityMainBinding
import com.hypernova.navigation.databinding.ItemManeuverBinding
import com.hypernova.navigation.databinding.ItemPlaceResultBinding
import com.hypernova.navigation.databinding.ItemRecentDestinationBinding
import com.hypernova.navigation.databinding.PanelActiveRouteBinding
import com.hypernova.navigation.databinding.PanelCalculatingBinding
import com.hypernova.navigation.databinding.PanelHomeBinding
import com.hypernova.navigation.databinding.PanelResultsBinding
import com.hypernova.navigation.databinding.PanelRouteOverviewBinding
import com.hypernova.navigation.databinding.PanelRoutePreviewBinding
import com.hypernova.navigation.databinding.PanelSearchBinding
import com.hypernova.navigation.databinding.PanelSpecialStateBinding
import com.hypernova.navigation.databinding.ViewRouteMetricBinding
import com.hypernova.navigation.domain.model.FailureKind
import com.hypernova.navigation.domain.model.GeoPoint
import com.hypernova.navigation.domain.model.NavigationDataException
import com.hypernova.navigation.domain.model.NavigationJson
import com.hypernova.navigation.domain.model.NavigationScreen
import com.hypernova.navigation.domain.model.NavigationSessionState
import com.hypernova.navigation.domain.model.NavigationSessionStatus
import com.hypernova.navigation.domain.model.NavigationUiState
import com.hypernova.navigation.domain.model.NearbyCategory
import com.hypernova.navigation.domain.model.Place
import com.hypernova.navigation.domain.model.RoutePlan
import com.hypernova.navigation.domain.model.RouteStep
import com.hypernova.navigation.domain.model.SavedDestinationTarget
import com.hypernova.navigation.domain.repository.NavigationRepository
import com.hypernova.navigation.ui.NavigationFormatters
import com.hypernova.navigation.ui.SystemThemeResolver
import com.hypernova.navigation.ui.map.NavigationMapController
import com.hypernova.navigation.ui.state.NavigationStateMachine
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: NavigationPreferences
    private lateinit var repository: NavigationRepository
    private lateinit var mapController: NavigationMapController
    private lateinit var mapView: MapView
    private lateinit var connectivityManager: ConnectivityManager

    private var uiState = NavigationUiState()
    private var stateMachine = NavigationStateMachine()
    private var networkAvailable = false
    private var mapReady = false
    private var mapLoadFailed = false
    private var debugForcedState = false
    private var offlineTriggeredAutomatically = false
    private var screenBeforeOffline = NavigationScreen.HOME
    private var overviewReturnScreen = NavigationScreen.ROUTE_PREVIEW

    private val mainHandler = Handler(Looper.getMainLooper())

    private val repositoryStateListener:
        (NavigationSessionState) -> Unit = { state ->
        mainHandler.post {
            if (!isFinishing && !isDestroyed) {
                applyRepositoryState(state)
            }
        }
    }

    private val timeUpdater =
        object : Runnable {
            override fun run() {
                updateClock()
                mainHandler.postDelayed(this, CLOCK_UPDATE_INTERVAL_MS)
            }
        }

    private val mapLoadTimeout =
        Runnable {
            if (!mapReady) {
                showMapLoadError(
                    getString(R.string.map_error_message)
                )
            }
        }

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    handleConnectivityChanged(true)
                }
            }

            override fun onLost(network: Network) {
                mainHandler.post {
                    handleConnectivityChanged(
                        isNetworkCurrentlyAvailable()
                    )
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                mainHandler.post {
                    handleConnectivityChanged(
                        networkCapabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_INTERNET
                        )
                    )
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        )
        super.onCreate(savedInstanceState)

        val navigationApplication =
            application as HyperNovaNavigationApplication
        preferences =
            navigationApplication.navigationPreferences
        repository =
            navigationApplication.navigationRepository

        enableEdgeToEdge()
        MapLibre.getInstance(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        mapView = MapView(this).also { runtimeMapView ->
            runtimeMapView.contentDescription =
                getString(R.string.map_content_description)
            runtimeMapView.importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_YES
            binding.mapContainer.addView(
                runtimeMapView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        mapView.onCreate(savedInstanceState)

        connectivityManager =
            getSystemService(ConnectivityManager::class.java)
        networkAvailable = isNetworkCurrentlyAvailable()

        restoreUiState(savedInstanceState)
        synchronizeRestoredNavigationState()
        stateMachine = NavigationStateMachine(uiState.screen)
        repository.addNavigationStateListener(
            repositoryStateListener
        )

        applySystemBarInsets()
        configurePersistentControls()
        configureBackNavigation()
        registerConnectivityMonitoring()

        mapController =
            NavigationMapController(
                context = this,
                mapView = mapView
            )

        requestMap()
        render()

        if (
            !networkAvailable &&
            uiState.screen != NavigationScreen.OFFLINE
        ) {
            showOfflineState()
        }

        handleDebugIntent(intent)

        mainHandler.post(timeUpdater)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent(intent)
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.main
        ) { view, insets ->
            val systemBars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                )

            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )

            insets
        }
    }

    private fun configurePersistentControls() {
        binding.topBar.backButton.setOnClickListener {
            handleBack()
        }

        if (isDebuggable()) {
            binding.topBar.titleText.setOnLongClickListener {
                showDebugStateSelector()
                true
            }
        }

        binding.zoomInButton.setOnClickListener {
            mapController.zoomIn()
        }

        binding.zoomOutButton.setOnClickListener {
            mapController.zoomOut()
        }

        binding.centerOriginButton.setOnClickListener {
            mapController.centerOnOrigin()
        }

        binding.mapRetryButton.setOnClickListener {
            requestMap()
        }
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBack()
                }
            }
        )
    }

    private fun handleBack() {
        debugForcedState = false

        when (uiState.screen) {
            NavigationScreen.HOME -> finish()

            NavigationScreen.SEARCH,
            NavigationScreen.SEARCHING -> {
                repository.cancelSearch()
                hideKeyboard()
                goHome(clearRoute = false)
            }

            NavigationScreen.RESULTS -> {
                showSearch(uiState.query)
            }

            NavigationScreen.CALCULATING_ROUTE -> {
                cancelRouteCalculation()
            }

            NavigationScreen.ROUTE_PREVIEW -> {
                showSearch(uiState.query)
            }

            NavigationScreen.ROUTE_ACTIVE -> {
                confirmEndRoute()
            }

            NavigationScreen.ROUTE_OVERVIEW -> {
                showRouteReturnState()
            }

            NavigationScreen.REROUTING -> {
                if (uiState.routePlan != null) {
                    setScreen(NavigationScreen.ROUTE_ACTIVE)
                } else {
                    goHome(clearRoute = false)
                }
            }

            NavigationScreen.ARRIVED,
            NavigationScreen.LOCATION_UNAVAILABLE,
            NavigationScreen.OFFLINE,
            NavigationScreen.ROUTE_ERROR -> {
                goHome(clearRoute = false)
            }
        }
    }

    // ============================================================
    // State rendering
    // ============================================================

    private fun render() {
        binding.stateHost.removeAllViews()
        updateHeader()

        mapView.importantForAccessibility =
            if (
                uiState.screen == NavigationScreen.SEARCH ||
                uiState.screen == NavigationScreen.SEARCHING
            ) {
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            } else {
                View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }

        binding.mapScrim.visibility =
            if (
                uiState.screen in setOf(
                    NavigationScreen.REROUTING,
                    NavigationScreen.ARRIVED,
                    NavigationScreen.LOCATION_UNAVAILABLE,
                    NavigationScreen.OFFLINE,
                    NavigationScreen.ROUTE_ERROR
                )
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        binding.mapControls.visibility =
            if (
                uiState.screen == NavigationScreen.HOME &&
                mapReady
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        when (uiState.screen) {
            NavigationScreen.HOME -> renderHome()
            NavigationScreen.SEARCH -> renderSearch(searching = false)
            NavigationScreen.SEARCHING -> renderSearch(searching = true)
            NavigationScreen.RESULTS -> renderResults()
            NavigationScreen.CALCULATING_ROUTE ->
                renderCalculatingRoute()
            NavigationScreen.ROUTE_PREVIEW -> renderRoutePreview()
            NavigationScreen.ROUTE_ACTIVE -> renderActiveRoute()
            NavigationScreen.ROUTE_OVERVIEW -> renderRouteOverview()
            NavigationScreen.REROUTING,
            NavigationScreen.ARRIVED,
            NavigationScreen.LOCATION_UNAVAILABLE,
            NavigationScreen.OFFLINE,
            NavigationScreen.ROUTE_ERROR -> renderSpecialState()
        }

        renderMapScene()
        updateMapStateCardVisibility()

        preferences.saveSafeScreen(uiState.screen)
        Log.i(
            TAG_NAVIGATION,
            "Rendered state ${uiState.screen}"
        )
    }

    private fun updateHeader() {
        binding.topBar.titleText.setText(
            when (uiState.screen) {
                NavigationScreen.HOME ->
                    R.string.home_screen_title
                NavigationScreen.SEARCH,
                NavigationScreen.SEARCHING ->
                    R.string.search_screen_title
                NavigationScreen.RESULTS ->
                    R.string.results_screen_title
                NavigationScreen.CALCULATING_ROUTE ->
                    R.string.calculating_route
                NavigationScreen.ROUTE_PREVIEW ->
                    R.string.route_screen_title
                NavigationScreen.ROUTE_ACTIVE ->
                    R.string.active_screen_title
                NavigationScreen.ROUTE_OVERVIEW ->
                    R.string.overview_screen_title
                NavigationScreen.REROUTING ->
                    R.string.rerouting_status
                NavigationScreen.ARRIVED ->
                    R.string.arrived_status
                NavigationScreen.LOCATION_UNAVAILABLE ->
                    R.string.location_status
                NavigationScreen.OFFLINE ->
                    R.string.offline_status
                NavigationScreen.ROUTE_ERROR ->
                    R.string.route_error_status
            }
        )

        binding.topBar.originStatusText.setText(
            when (uiState.screen) {
                NavigationScreen.ROUTE_ACTIVE,
                NavigationScreen.ROUTE_OVERVIEW ->
                    R.string.route_active
                else -> R.string.iti_start_status
            }
        )

        binding.topBar.originStatusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (
                    uiState.screen == NavigationScreen.ROUTE_ACTIVE ||
                    uiState.screen == NavigationScreen.ROUTE_OVERVIEW
                ) {
                    R.color.hypernova_success
                } else {
                    R.color.hypernova_cyan
                }
            )
        )

        updateNetworkHeader()
        updateClock()
    }

    private fun renderHome() {
        val panel =
            PanelHomeBinding.inflate(
                layoutInflater,
                binding.stateHost,
                true
            )

        val openSearch = {
            showSearch("")
        }

        panel.searchDestinationButton.setOnClickListener {
            openSearch()
        }
        panel.primarySearchButton.setOnClickListener {
            openSearch()
        }

        val home = preferences.home
        val work = preferences.work

        panel.homeButton.text =
            if (home == null) {
                getString(
                    R.string.home
                ) + "\n" + getString(R.string.set_location)
            } else {
                getString(R.string.home) + "\n" + home.name
            }

        panel.workButton.text =
            if (work == null) {
                getString(
                    R.string.work
                ) + "\n" + getString(R.string.set_location)
            } else {
                getString(R.string.work) + "\n" + work.name
            }

        panel.homeButton.setOnClickListener {
            if (home == null) {
                showSearch(
                    query = "",
                    target = SavedDestinationTarget.HOME
                )
            } else {
                beginRouteCalculation(home)
            }
        }

        panel.workButton.setOnClickListener {
            if (work == null) {
                showSearch(
                    query = "",
                    target = SavedDestinationTarget.WORK
                )
            } else {
                beginRouteCalculation(work)
            }
        }

        panel.homeButton.setOnLongClickListener {
            showSearch(
                query = "",
                target = SavedDestinationTarget.HOME
            )
            true
        }

        panel.workButton.setOnLongClickListener {
            showSearch(
                query = "",
                target = SavedDestinationTarget.WORK
            )
            true
        }

        panel.fuelButton.setOnClickListener {
            showSearch("")
            performNearbyCategory(NearbyCategory.FUEL)
        }

        panel.recentButton.setOnClickListener {
            showSearch("")
        }

        renderRecentDestinations(
            container = panel.recentList,
            emptyView = panel.noRecentsText,
            limit = 3
        ) { place ->
            beginRouteCalculation(place)
        }

        val currentRoute = uiState.routePlan?.selected
        val destination = uiState.destination

        if (currentRoute != null && destination != null) {
            panel.routeStatusTitle.text = destination.name
            panel.routeStatusMessage.text =
                getString(
                    R.string.route_summary_format,
                    NavigationFormatters.duration(currentRoute.durationSeconds),
                    NavigationFormatters.routeDistance(currentRoute.distanceMeters)
                )
        }
    }

    private fun showSearch(
        query: String,
        target: SavedDestinationTarget? =
            uiState.savedDestinationTarget
    ) {
        debugForcedState = false
        repository.cancelNavigation()

        setState(
            uiState.copy(
                screen = NavigationScreen.SEARCH,
                query = query,
                nearbyCategory = null,
                searchRadiusMeters = null,
                selectedResultId = null,
                savedDestinationTarget = target,
                message = null
            )
        )
    }

    private fun renderSearch(searching: Boolean) {
        val panel =
            PanelSearchBinding.inflate(
                layoutInflater,
                binding.stateHost,
                true
            )

        panel.searchInput.setText(uiState.query)
        panel.searchInput.setSelection(
            panel.searchInput.text?.length ?: 0
        )
        panel.loadingCard.visibility =
            if (searching) View.VISIBLE else View.INVISIBLE
        panel.searchButton.isEnabled = !searching
        panel.searchInput.isEnabled = !searching
        panel.loadingMessage.text =
            searchLoadingMessage()

        val message = uiState.message
        panel.errorMessage.visibility =
            if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        panel.errorMessage.text = message.orEmpty()
        panel.retrySearchButton.visibility =
            if (
                !searching &&
                !message.isNullOrBlank()
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        val submitSearch = {
            val query =
                panel.searchInput.text
                    ?.toString()
                    ?.trim()
                    .orEmpty()

            if (query.isBlank()) {
                panel.searchInputLayout.error =
                    getString(R.string.empty_query_error)
            } else {
                panel.searchInputLayout.error = null
                performTextSearch(query)
            }
        }

        panel.searchButton.setOnClickListener {
            submitSearch()
        }

        panel.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch()
                true
            } else {
                false
            }
        }

        panel.cancelButton.setOnClickListener {
            val cancelled = repository.cancelSearch()
            hideKeyboard()
            if (searching && cancelled) {
                setState(
                    uiState.copy(
                        screen = NavigationScreen.SEARCH,
                        message =
                            getString(
                                R.string.category_search_cancelled
                            )
                    )
                )
            } else {
                goHome(clearRoute = false)
            }
        }

        val categories =
            listOf(
                panel.parkingButton to NearbyCategory.PARKING,
                panel.fuelButton to NearbyCategory.FUEL,
                panel.foodButton to NearbyCategory.FOOD,
                panel.hospitalButton to NearbyCategory.HOSPITAL,
                panel.shoppingButton to NearbyCategory.SHOPPING
            )

        categories.forEach { (button, category) ->
            /*
             * Category controls intentionally remain enabled while a request
             * is running. The repository deduplicates an identical tap and
             * supersedes a different category deterministically.
             */
            button.isEnabled = true
            button.setOnClickListener {
                panel.searchInput.setText("")
                performNearbyCategory(category)
            }
        }

        panel.retrySearchButton.setOnClickListener {
            uiState.nearbyCategory?.let(::performNearbyCategory)
                ?: uiState.query
                    .takeIf { it.isNotBlank() }
                    ?.let(::performTextSearch)
        }

        renderRecentDestinations(
            container = panel.recentList,
            emptyView = panel.noRecentsText,
            limit = 6
        ) { place ->
            hideKeyboard()
            if (uiState.savedDestinationTarget == null) {
                beginRouteCalculation(place)
            } else {
                saveConfiguredDestination(place)
            }
        }

        if (!searching && uiState.nearbyCategory == null) {
            panel.searchInput.requestFocus()
            panel.searchInput.postDelayed(
                {
                    val inputMethodManager =
                        getSystemService(
                            Context.INPUT_METHOD_SERVICE
                        ) as InputMethodManager

                    inputMethodManager.showSoftInput(
                        panel.searchInput,
                        InputMethodManager.SHOW_IMPLICIT
                    )
                },
                KEYBOARD_SHOW_DELAY_MS
            )
        }
    }

    private fun searchLoadingMessage(): String {
        val category = uiState.nearbyCategory
        val radiusMeters = uiState.searchRadiusMeters

        return if (category != null && radiusMeters != null) {
            getString(
                if (radiusMeters == FIRST_NEARBY_RADIUS_METERS) {
                    R.string.searching_nearby_radius_format
                } else {
                    R.string.expanding_nearby_radius_format
                },
                category.displayName,
                radiusMeters / METERS_PER_KILOMETER
            )
        } else {
            getString(
                R.string.searching_for_format,
                uiState.query
            )
        }
    }

    private fun performTextSearch(query: String) {
        if (!networkAvailable) {
            showOfflineState()
            return
        }

        hideKeyboard()

        setState(
            uiState.copy(
                screen = NavigationScreen.SEARCHING,
                query = query.trim(),
                nearbyCategory = null,
                searchRadiusMeters = null,
                searchResults = emptyList(),
                selectedResultId = null,
                message = null
            )
        )

        Log.i(TAG_SEARCH, "Starting Nominatim search")

        repository.searchTextPlace(query.trim()) { result ->
            result
                .onSuccess { places ->
                    Log.i(
                        TAG_SEARCH,
                        "Nominatim returned ${places.size} places"
                    )

                    if (places.isEmpty()) {
                        setState(
                            uiState.copy(
                                screen = NavigationScreen.SEARCH,
                                nearbyCategory = null,
                                searchRadiusMeters = null,
                                message =
                                    getString(
                                        R.string.no_results_message_format,
                                        query
                                    )
                            )
                        )
                    } else {
                        setState(
                            uiState.copy(
                                screen = NavigationScreen.RESULTS,
                                query = query,
                                nearbyCategory = null,
                                searchRadiusMeters = null,
                                searchResults = places,
                                selectedResultId = places.first().id,
                                message = null
                            )
                        )
                    }
                }
                .onFailure { throwable ->
                    Log.w(
                        TAG_SEARCH,
                        "Nominatim search failed",
                        throwable
                    )

                    if (
                        isNetworkFailure(throwable) &&
                        !isNetworkCurrentlyAvailable()
                    ) {
                        showOfflineState()
                    } else {
                        setState(
                            uiState.copy(
                                screen = NavigationScreen.SEARCH,
                                nearbyCategory = null,
                                searchRadiusMeters = null,
                                message =
                                    throwable.message
                                        ?: getString(
                                            R.string.search_error_default
                                        )
                            )
                        )
                    }
                }
        }
    }

    private fun performNearbyCategory(
        category: NearbyCategory
    ) {
        if (!networkAvailable) {
            showOfflineState()
            return
        }

        if (
            uiState.screen == NavigationScreen.SEARCHING &&
            uiState.nearbyCategory == category &&
            repository.isNearbySearchRunning(category, ITI_ORIGIN)
        ) {
            Toast.makeText(
                this,
                getString(
                    R.string.category_search_already_running_format,
                    category.displayName
                ),
                Toast.LENGTH_SHORT
            ).show()
            Log.i(
                TAG_SEARCH,
                "Overpass ${category.name} duplicate tap ignored"
            )
            return
        }

        hideKeyboard()
        setState(
            uiState.copy(
                screen = NavigationScreen.SEARCHING,
                query = "",
                nearbyCategory = category,
                searchRadiusMeters =
                    FIRST_NEARBY_RADIUS_METERS,
                searchResults = emptyList(),
                selectedResultId = null,
                message = null
            )
        )

        Log.i(
            TAG_SEARCH,
            "Starting Overpass ${category.name} search"
        )

        repository.searchNearbyCategory(
            category = category,
            origin = ITI_ORIGIN,
            onProgress = { progress ->
                Log.i(
                    TAG_SEARCH,
                    "Overpass ${progress.category.name} radius " +
                        "${progress.radiusMeters}m"
                )

                if (
                    uiState.screen ==
                    NavigationScreen.SEARCHING
                ) {
                    setState(
                        uiState.copy(
                            searchRadiusMeters =
                                progress.radiusMeters
                        )
                    )
                }
            }
        ) { result ->
            result
                .onSuccess { nearbyResult ->
                    val places = nearbyResult.places
                    Log.i(
                        TAG_SEARCH,
                        "Overpass returned ${places.size} " +
                            "${category.name} places within " +
                            "${nearbyResult.finalRadiusMeters}m"
                    )

                    if (places.isEmpty()) {
                        setState(
                            uiState.copy(
                                screen = NavigationScreen.SEARCH,
                                nearbyCategory = category,
                                searchRadiusMeters =
                                    nearbyResult.finalRadiusMeters,
                                message =
                                    getString(
                                        R.string.no_nearby_results_format,
                                        category.displayName,
                                        nearbyResult.finalRadiusMeters /
                                            METERS_PER_KILOMETER
                                    )
                            )
                        )
                    } else {
                        setState(
                            uiState.copy(
                                screen = NavigationScreen.RESULTS,
                                query = "",
                                nearbyCategory = category,
                                searchRadiusMeters =
                                    nearbyResult.finalRadiusMeters,
                                searchResults = places,
                                selectedResultId =
                                    places.first().id,
                                message =
                                    if (
                                        nearbyResult
                                            .widerSearchUnavailable
                                    ) {
                                        getString(
                                            R.string
                                                .category_partial_results_format,
                                            places.size
                                        )
                                    } else {
                                        null
                                    }
                            )
                        )
                    }
                }
                .onFailure { throwable ->
                    val dataFailure =
                        throwable as? NavigationDataException
                    Log.w(
                        TAG_SEARCH,
                        "Overpass ${category.name} search failed: " +
                            "kind=${dataFailure?.kind ?: "UNKNOWN"} " +
                            "http=${dataFailure?.httpStatusCode ?: "-"}"
                    )

                    if (
                        isNetworkFailure(throwable) &&
                        !isNetworkCurrentlyAvailable()
                    ) {
                        showOfflineState()
                    } else if (
                        throwable is NavigationDataException &&
                        throwable.kind == FailureKind.CANCELLED
                    ) {
                        setState(
                            uiState.copy(
                                screen = NavigationScreen.SEARCH,
                                nearbyCategory = category,
                                message =
                                    getString(
                                        R.string
                                            .category_search_cancelled
                                    )
                            )
                        )
                    } else {
                        setState(
                            uiState.copy(
                                screen = NavigationScreen.SEARCH,
                                nearbyCategory = category,
                                message =
                                    getString(
                                        R.string
                                            .category_provider_error_format,
                                        category.displayName
                                    )
                            )
                        )
                    }
                }
        }
    }

    private fun renderResults() {
        val panel =
            PanelResultsBinding.inflate(
                layoutInflater,
                binding.stateHost,
                true
            )

        panel.queryButton.text =
            uiState.nearbyCategory?.let {
                getString(
                    R.string.nearby_results_title_format,
                    it.displayName
                )
            } ?: uiState.query
        panel.resultsCountText.text =
            resources.getQuantityString(
                R.plurals.results_count_format,
                uiState.searchResults.size,
                uiState.searchResults.size
            )
        panel.resultsStatusText.visibility =
            if (uiState.message.isNullOrBlank()) {
                View.GONE
            } else {
                View.VISIBLE
            }
        panel.resultsStatusText.text =
            uiState.message.orEmpty()

        panel.queryButton.setOnClickListener {
            showSearch(uiState.query)
        }

        panel.searchAgainButton.setOnClickListener {
            val category = uiState.nearbyCategory
            if (category == null) {
                showSearch(uiState.query)
            } else {
                showSearch("")
                performNearbyCategory(category)
            }
        }

        uiState.searchResults.forEach { place ->
            val item =
                ItemPlaceResultBinding.inflate(
                    layoutInflater,
                    panel.resultsList,
                    true
                )

            val selected =
                place.id == uiState.selectedResultId
            val directDistance =
                place.straightLineDistanceMeters
                    ?: NavigationFormatters.straightLineDistance(
                        ITI_ORIGIN,
                        GeoPoint(
                            place.latitude,
                            place.longitude
                        )
                    )

            item.nameText.text = place.name
            item.categoryText.text =
                place.categoryDescription
            item.categoryText.visibility =
                if (place.categoryDescription.isBlank()) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
            item.addressText.text =
                place.address.ifBlank {
                    getString(R.string.address_unavailable)
                }
            item.distanceText.text =
                getString(
                    R.string.straight_line_format,
                    NavigationFormatters.routeDistance(
                        directDistance
                    )
                )
            item.selectionIcon.visibility =
                if (selected) View.VISIBLE else View.INVISIBLE
            styleResultCard(item.resultCard, selected)
            item.resultCard.contentDescription =
                "${place.name}. ${item.distanceText.text}"

            item.resultCard.setOnClickListener {
                if (selected) {
                    confirmSelectedResult(place)
                } else {
                    setState(
                        uiState.copy(
                            selectedResultId = place.id
                        )
                    )
                }
            }
        }

        val selected =
            uiState.searchResults.firstOrNull {
                it.id == uiState.selectedResultId
            }

        panel.selectButton.isEnabled = selected != null
        panel.selectButton.text =
            when (uiState.savedDestinationTarget) {
                SavedDestinationTarget.HOME ->
                    getString(R.string.save_as_home)
                SavedDestinationTarget.WORK ->
                    getString(R.string.save_as_work)
                null -> getString(R.string.select_destination)
            }

        if (selected == null) {
            panel.selectionHintText.setText(
                R.string.destination_not_selected
            )
        } else {
            val distance =
                NavigationFormatters.straightLineDistance(
                    ITI_ORIGIN,
                    GeoPoint(
                        selected.latitude,
                        selected.longitude
                    )
                )

            panel.selectionHintText.text =
                getString(
                    R.string.place_distance_summary_format,
                    selected.name,
                    getString(
                        R.string.straight_line_format,
                        NavigationFormatters.routeDistance(distance)
                    )
                )
        }

        panel.selectButton.setOnClickListener {
            selected?.let(::confirmSelectedResult)
        }
    }

    private fun styleResultCard(
        card: MaterialCardView,
        selected: Boolean
    ) {
        card.strokeWidth =
            if (selected) dp(2) else dp(1)
        card.setStrokeColor(
            ContextCompat.getColor(
                this,
                if (selected) {
                    R.color.hypernova_cyan
                } else {
                    R.color.hypernova_border
                }
            )
        )
        card.setCardBackgroundColor(
            ContextCompat.getColor(
                this,
                if (selected) {
                    R.color.hypernova_selected
                } else {
                    R.color.hypernova_card_elevated
                }
            )
        )
    }

    private fun confirmSelectedResult(place: Place) {
        if (uiState.savedDestinationTarget != null) {
            saveConfiguredDestination(place)
        } else {
            beginRouteCalculation(place)
        }
    }

    private fun saveConfiguredDestination(place: Place) {
        when (uiState.savedDestinationTarget) {
            SavedDestinationTarget.HOME -> preferences.home = place
            SavedDestinationTarget.WORK -> preferences.work = place
            null -> return
        }

        Toast.makeText(
            this,
            getString(
                R.string.saved_destination_format,
                uiState.savedDestinationTarget
                    ?.name
                    ?.lowercase()
                    ?.replaceFirstChar { it.titlecase() }
                    .orEmpty()
            ),
            Toast.LENGTH_SHORT
        ).show()

        goHome(clearRoute = false)
    }

    // ============================================================
    // Real OSRM route flow
    // ============================================================

    private fun beginRouteCalculation(destination: Place) {
        debugForcedState = false

        if (!networkAvailable) {
            setState(
                uiState.copy(destination = destination)
            )
            showOfflineState()
            return
        }

        setState(
            uiState.copy(
                screen = NavigationScreen.CALCULATING_ROUTE,
                destination = destination,
                routePlan = null,
                savedDestinationTarget = null,
                message = null
            )
        )

        Log.i(TAG_ROUTE, "Starting OSRM route calculation")

        repository.calculateRoute(
            origin = ITI_ORIGIN,
            destination = destination
        ) { result ->
            result
                .onSuccess { routePlan ->
                    Log.i(
                        TAG_ROUTE,
                        "OSRM returned ${routePlan.alternatives.size} routes"
                    )

                    setState(
                        uiState.copy(
                            screen = NavigationScreen.ROUTE_PREVIEW,
                            routePlan = routePlan,
                            message = null
                        )
                    )
                }
                .onFailure { throwable ->
                    Log.w(
                        TAG_ROUTE,
                        "OSRM route calculation failed",
                        throwable
                    )

                    if (isNetworkFailure(throwable)) {
                        showOfflineState()
                    } else {
                        setState(
                            uiState.copy(
                                screen = NavigationScreen.ROUTE_ERROR,
                                routePlan = null,
                                message = throwable.message
                            )
                        )
                    }
                }
        }
    }

    private fun renderCalculatingRoute() {
        val panel =
            PanelCalculatingBinding.inflate(
                layoutInflater,
                binding.stateHost,
                true
            )

        panel.destinationText.text =
            uiState.destination?.displayName.orEmpty()
        panel.cancelButton.setOnClickListener {
            cancelRouteCalculation()
        }
    }

    private fun cancelRouteCalculation() {
        repository.cancelNavigation()
        val returnScreen =
            if (uiState.searchResults.isNotEmpty()) {
                NavigationScreen.RESULTS
            } else {
                NavigationScreen.SEARCH
            }

        setState(
            uiState.copy(
                screen = returnScreen,
                routePlan = null,
                message = null
            )
        )

        Toast.makeText(
            this,
            R.string.calculation_cancelled,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun renderRoutePreview() {
        val destination = uiState.destination
        val routePlan = uiState.routePlan

        if (destination == null || routePlan == null) {
            goHome(clearRoute = true)
            return
        }

        val panel =
            PanelRoutePreviewBinding.inflate(
                layoutInflater,
                binding.stateHost,
                true
            )
        val route = routePlan.selected

        panel.destinationNameText.text = destination.name
        panel.destinationAddressText.text =
            destination.address.ifBlank { destination.displayName }

        val fastest = routePlan.alternatives.first()
        panel.fastestSummaryText.text =
            getString(
                R.string.route_summary_format,
                NavigationFormatters.duration(fastest.durationSeconds),
                NavigationFormatters.routeDistance(fastest.distanceMeters)
            )

        val alternative =
            routePlan.alternatives.getOrNull(1)
        panel.alternativeRouteCard.visibility =
            if (alternative == null) View.GONE else View.VISIBLE

        alternative?.let {
            panel.alternativeSummaryText.text =
                getString(
                    R.string.route_summary_format,
                    NavigationFormatters.duration(it.durationSeconds),
                    NavigationFormatters.routeDistance(it.distanceMeters)
                )
        }

        styleRouteChoice(
            panel.fastestRouteCard,
            routePlan.selectedIndex == 0
        )
        styleRouteChoice(
            panel.alternativeRouteCard,
            routePlan.selectedIndex == 1
        )

        panel.fastestRouteCard.setOnClickListener {
            selectRouteAlternative(0)
        }

        panel.alternativeRouteCard.setOnClickListener {
            if (alternative != null) {
                selectRouteAlternative(1)
            }
        }

        bindMetrics(
            distance = panel.distanceMetric,
            duration = panel.durationMetric,
            arrival = panel.arrivalMetric,
            routePlan = routePlan
        )

        panel.startRouteButton.setOnClickListener {
            if (!repository.activateCurrentRoute()) {
                setState(
                    uiState.copy(
                        screen = NavigationScreen.ROUTE_ERROR,
                        message =
                            getString(
                                R.string.route_error_default
                            )
                    ),
                    debugOverride = true
                )
            }
        }

        panel.overviewButton.setOnClickListener {
            overviewReturnScreen = NavigationScreen.ROUTE_PREVIEW
            setScreen(NavigationScreen.ROUTE_OVERVIEW)
        }

        panel.changeButton.setOnClickListener {
            showSearch(uiState.query)
        }
    }

    private fun styleRouteChoice(
        card: MaterialCardView,
        selected: Boolean
    ) {
        card.strokeWidth =
            if (selected) dp(2) else dp(1)
        card.setStrokeColor(
            ContextCompat.getColor(
                this,
                if (selected) {
                    R.color.hypernova_cyan
                } else {
                    R.color.hypernova_border
                }
            )
        )
        card.setCardBackgroundColor(
            ContextCompat.getColor(
                this,
                if (selected) {
                    R.color.hypernova_selected
                } else {
                    R.color.hypernova_card_elevated
                }
            )
        )
    }

    private fun selectRouteAlternative(index: Int) {
        val routePlan = uiState.routePlan ?: return
        if (index !in routePlan.alternatives.indices) return

        val selected =
            routePlan.copy(selectedIndex = index)
        repository.selectRouteAlternative(selected)
        setState(
            uiState.copy(
                routePlan = selected
            )
        )
    }

    private fun renderActiveRoute() {
        val destination = uiState.destination
        val routePlan = uiState.routePlan

        if (destination == null || routePlan == null) {
            goHome(clearRoute = true)
            return
        }

        val panel =
            PanelActiveRouteBinding.inflate(
                layoutInflater,
                binding.stateHost,
                true
            )
        val route = routePlan.selected
        val step = route.steps.firstOrNull()

        panel.destinationText.text = destination.name
        panel.instructionText.text =
            step?.instruction
                ?: getString(R.string.route_preview_mode)
        panel.instructionDetailText.text =
            step?.let {
                buildString {
                    append(
                        NavigationFormatters.stepDistance(
                            it.distanceMeters
                        )
                    )
                    if (it.roadName.isNotBlank()) {
                        append(" • ")
                        append(it.roadName)
                    }
                }
            }.orEmpty()

        panel.maneuverIcon.setImageResource(
            maneuverIcon(step)
        )

        bindMetrics(
            distance = panel.distanceMetric,
            duration = panel.durationMetric,
            arrival = panel.arrivalMetric,
            routePlan = routePlan
        )

        panel.endRouteButton.setOnClickListener {
            confirmEndRoute()
        }

        panel.overviewButton.setOnClickListener {
            overviewReturnScreen = NavigationScreen.ROUTE_ACTIVE
            setScreen(NavigationScreen.ROUTE_OVERVIEW)
        }

        updateMuteButton(panel)
        panel.muteButton.setOnClickListener {
            preferences.guidanceMuted =
                !preferences.guidanceMuted
            Toast.makeText(
                this,
                R.string.mute_disclaimer,
                Toast.LENGTH_LONG
            ).show()
            render()
        }
    }

    private fun updateMuteButton(panel: PanelActiveRouteBinding) {
        val muted = preferences.guidanceMuted
        panel.muteButton.setText(
            if (muted) {
                R.string.unmute_guidance_ui
            } else {
                R.string.mute_guidance_ui
            }
        )
        panel.muteButton.icon =
            ContextCompat.getDrawable(
                this,
                if (muted) {
                    R.drawable.ic_volume
                } else {
                    R.drawable.ic_volume_off
                }
            )
        panel.muteButton.iconTint =
            ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    R.color.hypernova_text_primary
                )
            )
    }

    private fun renderRouteOverview() {
        val destination = uiState.destination
        val routePlan = uiState.routePlan

        if (destination == null || routePlan == null) {
            goHome(clearRoute = true)
            return
        }

        val panel =
            PanelRouteOverviewBinding.inflate(
                layoutInflater,
                binding.stateHost,
                true
            )
        val route = routePlan.selected

        panel.destinationText.text = destination.name
        bindMetrics(
            distance = panel.distanceMetric,
            duration = panel.durationMetric,
            arrival = panel.arrivalMetric,
            routePlan = routePlan
        )

        panel.noManeuversText.visibility =
            if (route.steps.isEmpty()) View.VISIBLE else View.GONE

        route.steps.forEach { step ->
            val item =
                ItemManeuverBinding.inflate(
                    layoutInflater,
                    panel.maneuverList,
                    true
                )

            item.icon.setImageResource(maneuverIcon(step))
            item.instructionText.text = step.instruction
            item.roadText.text =
                step.roadName.ifBlank {
                    getString(R.string.road_name_unavailable)
                }
            item.distanceText.text =
                NavigationFormatters.stepDistance(
                    step.distanceMeters
                )
        }

        panel.returnButton.setOnClickListener {
            showRouteReturnState()
        }

        panel.endRouteButton.setOnClickListener {
            confirmEndRoute()
        }
    }

    private fun showRouteReturnState() {
        val target =
            if (
                overviewReturnScreen ==
                NavigationScreen.ROUTE_ACTIVE
            ) {
                NavigationScreen.ROUTE_ACTIVE
            } else {
                NavigationScreen.ROUTE_PREVIEW
            }

        setScreen(target)
    }

    private fun bindMetrics(
        distance: ViewRouteMetricBinding,
        duration: ViewRouteMetricBinding,
        arrival: ViewRouteMetricBinding,
        routePlan: RoutePlan
    ) {
        val route = routePlan.selected

        distance.labelText.setText(R.string.distance)
        distance.valueText.text =
            NavigationFormatters.routeDistance(
                route.distanceMeters
            )
        duration.labelText.setText(R.string.duration)
        duration.valueText.text =
            NavigationFormatters.duration(
                route.durationSeconds
            )
        arrival.labelText.setText(R.string.arrival)
        arrival.valueText.text =
            NavigationFormatters.arrivalTime(
                route.durationSeconds,
                use24HourFormat =
                    android.text.format.DateFormat.is24HourFormat(this)
            )
    }

    private fun maneuverIcon(step: RouteStep?): Int =
        when (step?.let(NavigationFormatters::maneuverGlyph)) {
            "left" -> R.drawable.ic_maneuver_left
            "right" -> R.drawable.ic_maneuver_right
            else -> R.drawable.ic_maneuver_straight
        }

    private fun confirmEndRoute() {
        val destinationName =
            uiState.destination?.name
                ?: getString(R.string.navigation_title)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.end_route_title)
            .setMessage(
                getString(
                    R.string.end_route_message_format,
                    destinationName
                )
            )
            .setPositiveButton(
                R.string.continue_route,
                null
            )
            .setNegativeButton(
                R.string.end_route
            ) { _, _ ->
                clearRouteAndReturnHome()
            }
            .show()
    }

    private fun clearRouteAndReturnHome() {
        repository.cancelNavigation()
        setState(
            NavigationUiState(
                screen = NavigationScreen.HOME
            ),
            debugOverride = true
        )
    }

    // ============================================================
    // Special and debug-only states
    // ============================================================

    private fun renderSpecialState() {
        val panel =
            PanelSpecialStateBinding.inflate(
                layoutInflater,
                binding.stateHost,
                true
            )

        when (uiState.screen) {
            NavigationScreen.REROUTING ->
                configureReroutingState(panel)
            NavigationScreen.ARRIVED ->
                configureArrivedState(panel)
            NavigationScreen.LOCATION_UNAVAILABLE ->
                configureLocationUnavailableState(panel)
            NavigationScreen.OFFLINE ->
                configureOfflineState(panel)
            NavigationScreen.ROUTE_ERROR ->
                configureRouteErrorState(panel)
            else -> Unit
        }
    }

    private fun configureReroutingState(
        panel: PanelSpecialStateBinding
    ) {
        setSpecialVisuals(
            panel = panel,
            status = getString(R.string.rerouting_status),
            title = getString(R.string.rerouting_title),
            message = getString(R.string.rerouting_message),
            icon = R.drawable.ic_refresh,
            color = R.color.hypernova_warning
        )

        panel.primaryButton.setText(
            R.string.return_active_route
        )
        panel.primaryButton.setOnClickListener {
            debugForcedState = false
            if (uiState.routePlan != null) {
                setScreen(
                    NavigationScreen.ROUTE_ACTIVE,
                    debugOverride = true
                )
            } else {
                goHome(clearRoute = false)
            }
        }
        panel.secondaryButton.setText(R.string.end_route)
        panel.secondaryButton.setOnClickListener {
            clearRouteAndReturnHome()
        }
    }

    private fun configureArrivedState(
        panel: PanelSpecialStateBinding
    ) {
        setSpecialVisuals(
            panel = panel,
            status = getString(R.string.arrived_status),
            title = getString(R.string.arrived_title),
            message =
                buildString {
                    uiState.destination?.let {
                        append(it.name)
                        append("\n")
                    }
                    append(getString(R.string.arrived_message))
                },
            icon = R.drawable.ic_success,
            color = R.color.hypernova_success
        )

        uiState.routePlan?.let { plan ->
            panel.metricsContainer.visibility = View.VISIBLE
            panel.firstMetric.labelText.setText(R.string.duration)
            panel.firstMetric.valueText.text =
                NavigationFormatters.duration(
                    plan.selected.durationSeconds
                )
            panel.secondMetric.labelText.setText(R.string.distance)
            panel.secondMetric.valueText.text =
                NavigationFormatters.routeDistance(
                    plan.selected.distanceMeters
                )
        }

        panel.primaryButton.setText(R.string.finish_navigation)
        panel.primaryButton.setOnClickListener {
            clearRouteAndReturnHome()
        }
        panel.secondaryButton.setText(R.string.save_location)
        panel.secondaryButton.setOnClickListener {
            uiState.destination?.let(preferences::addRecent)
            Toast.makeText(
                this,
                R.string.save_location,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun configureLocationUnavailableState(
        panel: PanelSpecialStateBinding
    ) {
        setSpecialVisuals(
            panel = panel,
            status = getString(R.string.location_status),
            title =
                getString(R.string.location_unavailable_title),
            message =
                getString(R.string.location_unavailable_message),
            icon = R.drawable.ic_location_off,
            color = R.color.hypernova_warning
        )

        panel.primaryButton.setText(R.string.retry)
        panel.primaryButton.setOnClickListener {
            debugForcedState = false
            goHome(clearRoute = false)
        }
        panel.secondaryButton.setText(
            R.string.open_location_settings
        )
        panel.secondaryButton.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(
                    this,
                    R.string.location_unavailable_title,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun configureOfflineState(
        panel: PanelSpecialStateBinding
    ) {
        setSpecialVisuals(
            panel = panel,
            status = getString(R.string.offline_status),
            title = getString(R.string.offline_title),
            message = getString(R.string.offline_message),
            icon = R.drawable.ic_warning,
            color = R.color.hypernova_warning
        )
        panel.detailText.visibility = View.VISIBLE
        panel.detailText.setText(R.string.offline_limitations)
        panel.primaryButton.setText(R.string.retry)
        panel.primaryButton.setOnClickListener {
            networkAvailable = isNetworkCurrentlyAvailable()
            updateNetworkHeader()

            if (networkAvailable) {
                debugForcedState = false
                offlineTriggeredAutomatically = false
                goHome(clearRoute = false)
            } else {
                Toast.makeText(
                    this,
                    R.string.offline_title,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        panel.secondaryButton.setText(
            R.string.continue_to_home
        )
        panel.secondaryButton.setOnClickListener {
            debugForcedState = false
            goHome(clearRoute = false)
        }
    }

    private fun configureRouteErrorState(
        panel: PanelSpecialStateBinding
    ) {
        setSpecialVisuals(
            panel = panel,
            status = getString(R.string.route_error_status),
            title = getString(R.string.route_error_title),
            message =
                uiState.message
                    ?: getString(R.string.route_error_default),
            icon = R.drawable.ic_error,
            color = R.color.hypernova_error
        )

        panel.primaryButton.setText(R.string.retry)
        panel.primaryButton.isEnabled =
            uiState.destination != null
        panel.primaryButton.setOnClickListener {
            uiState.destination?.let(::beginRouteCalculation)
        }
        panel.secondaryButton.setText(
            R.string.change_destination
        )
        panel.secondaryButton.setOnClickListener {
            showSearch(uiState.query)
        }
        panel.tertiaryButton.visibility = View.VISIBLE
        panel.tertiaryButton.setText(R.string.clear_route)
        panel.tertiaryButton.setOnClickListener {
            clearRouteAndReturnHome()
        }
    }

    private fun setSpecialVisuals(
        panel: PanelSpecialStateBinding,
        status: String,
        title: String,
        message: String,
        icon: Int,
        color: Int
    ) {
        val resolvedColor =
            ContextCompat.getColor(this, color)

        panel.statusText.text = status
        panel.statusText.setTextColor(resolvedColor)
        panel.titleText.text = title
        panel.messageText.text = message
        panel.stateIcon.setImageResource(icon)
        panel.stateIcon.imageTintList =
            ColorStateList.valueOf(resolvedColor)
    }

    private fun showDebugStateSelector() {
        if (!isDebuggable()) return

        val labels =
            intArrayOf(
                R.string.debug_home,
                R.string.debug_search,
                R.string.debug_results,
                R.string.debug_calculating,
                R.string.debug_preview,
                R.string.debug_active,
                R.string.debug_overview,
                R.string.debug_rerouting,
                R.string.debug_arrived,
                R.string.debug_location,
                R.string.debug_offline,
                R.string.debug_error
            ).map(::getString).toTypedArray()

        val screens =
            arrayOf(
                NavigationScreen.HOME,
                NavigationScreen.SEARCH,
                NavigationScreen.RESULTS,
                NavigationScreen.CALCULATING_ROUTE,
                NavigationScreen.ROUTE_PREVIEW,
                NavigationScreen.ROUTE_ACTIVE,
                NavigationScreen.ROUTE_OVERVIEW,
                NavigationScreen.REROUTING,
                NavigationScreen.ARRIVED,
                NavigationScreen.LOCATION_UNAVAILABLE,
                NavigationScreen.OFFLINE,
                NavigationScreen.ROUTE_ERROR
            )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.debug_states_title)
            .setItems(labels) { _, index ->
                forceDebugState(screens[index])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun forceDebugState(screen: NavigationScreen) {
        if (!isDebuggable()) return

        val needsResults =
            screen == NavigationScreen.RESULTS
        val needsDestination =
            screen == NavigationScreen.CALCULATING_ROUTE
        val needsRoute =
            screen in setOf(
                NavigationScreen.ROUTE_PREVIEW,
                NavigationScreen.ROUTE_ACTIVE,
                NavigationScreen.ROUTE_OVERVIEW
            )

        if (
            (needsResults && uiState.searchResults.isEmpty()) ||
            (needsDestination && uiState.destination == null) ||
            (needsRoute && uiState.routePlan == null)
        ) {
            Toast.makeText(
                this,
                R.string.debug_requires_real_data,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        repository.cancelSearch()
        repository.cancelRoute()
        debugForcedState = true

        setScreen(
            screen,
            debugOverride = true
        )
    }

    private fun handleDebugIntent(intent: Intent) {
        if (!isDebuggable()) return

        val requested =
            intent.getStringExtra(DEBUG_STATE_EXTRA)
                ?.uppercase(Locale.ROOT)
                ?.let {
                    runCatching {
                        NavigationScreen.valueOf(it)
                    }.getOrNull()
                }

        requested?.let(::forceDebugState)
    }

    private fun isDebuggable(): Boolean =
        applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    // ============================================================
    // Recents, Home, and Work
    // ============================================================

    private fun renderRecentDestinations(
        container: LinearLayout,
        emptyView: TextView,
        limit: Int,
        onClick: (Place) -> Unit
    ) {
        val recents = preferences.recents.take(limit)
        emptyView.visibility =
            if (recents.isEmpty()) View.VISIBLE else View.GONE

        recents.forEach { place ->
            val item =
                ItemRecentDestinationBinding.inflate(
                    layoutInflater,
                    container,
                    true
                )

            item.nameText.text = place.name
            item.addressText.text =
                place.address.ifBlank { place.displayName }
            item.root.contentDescription =
                "${place.name}. ${place.address}"
            item.root.setOnClickListener {
                onClick(place)
            }
        }
    }

    private fun goHome(clearRoute: Boolean) {
        debugForcedState = false
        offlineTriggeredAutomatically = false
        hideKeyboard()

        if (clearRoute) {
            repository.cancelNavigation()
        }
        setState(
            if (clearRoute) {
                NavigationUiState(
                    screen = NavigationScreen.HOME
                )
            } else {
                uiState.copy(
                    screen = NavigationScreen.HOME,
                    message = null,
                    savedDestinationTarget = null
                )
            },
            debugOverride = true
        )
    }

    // ============================================================
    // Connectivity
    // ============================================================

    private fun registerConnectivityMonitoring() {
        try {
            connectivityManager.registerDefaultNetworkCallback(
                networkCallback
            )
        } catch (exception: RuntimeException) {
            Log.w(
                TAG_NAVIGATION,
                "Connectivity callback unavailable",
                exception
            )
        }
    }

    private fun isNetworkCurrentlyAvailable(): Boolean {
        val network =
            connectivityManager.activeNetwork ?: return false
        val capabilities =
            connectivityManager.getNetworkCapabilities(network)
                ?: return false

        return capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET
        )
    }

    private fun handleConnectivityChanged(available: Boolean) {
        val changed = networkAvailable != available
        networkAvailable = available
        updateNetworkHeader()

        if (!changed || debugForcedState) return

        Log.i(
            TAG_NAVIGATION,
            "Connectivity changed: " +
                if (available) "online" else "offline"
        )

        if (!available) {
            showOfflineState()
        } else if (
            uiState.screen == NavigationScreen.OFFLINE &&
            offlineTriggeredAutomatically
        ) {
            offlineTriggeredAutomatically = false
            val returnScreen =
                when (screenBeforeOffline) {
                    NavigationScreen.SEARCHING ->
                        NavigationScreen.SEARCH
                    NavigationScreen.CALCULATING_ROUTE ->
                        NavigationScreen.ROUTE_ERROR
                    NavigationScreen.OFFLINE ->
                        NavigationScreen.HOME
                    else -> screenBeforeOffline
                }

            setScreen(
                returnScreen,
                debugOverride = true
            )
        }
    }

    private fun showOfflineState() {
        if (debugForcedState) return

        if (uiState.screen != NavigationScreen.OFFLINE) {
            screenBeforeOffline = uiState.screen
        }
        repository.cancelSearch()
        repository.cancelRoute()
        offlineTriggeredAutomatically = true

        setScreen(
            NavigationScreen.OFFLINE,
            debugOverride = true
        )
    }

    private fun updateNetworkHeader() {
        if (!::binding.isInitialized) return

        binding.topBar.networkStatusText.setText(
            if (networkAvailable) {
                R.string.online_label
            } else {
                R.string.offline_label
            }
        )
        binding.topBar.networkStatusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (networkAvailable) {
                    R.color.hypernova_success
                } else {
                    R.color.hypernova_warning
                }
            )
        )
    }

    private fun isNetworkFailure(throwable: Throwable): Boolean =
        throwable is NavigationDataException &&
            throwable.kind == FailureKind.NETWORK

    // ============================================================
    // Map
    // ============================================================

    private fun requestMap() {
        mapReady = false
        mapLoadFailed = false
        binding.mapLoadingIndicator.visibility = View.VISIBLE
        binding.mapErrorIcon.visibility = View.GONE
        binding.mapRetryButton.visibility = View.GONE
        binding.mapStateTitle.setText(R.string.map_loading_title)
        binding.mapStateMessage.setText(R.string.map_loading_message)
        updateMapStateCardVisibility()

        mainHandler.removeCallbacks(mapLoadTimeout)
        mainHandler.postDelayed(
            mapLoadTimeout,
            MAP_LOAD_TIMEOUT_MS
        )

        mapController.initialize(
            isNightMode =
                SystemThemeResolver.isNightMode(
                    resources.configuration.uiMode
                ),
            onReady = {
                mapReady = true
                mapLoadFailed = false
                mainHandler.removeCallbacks(mapLoadTimeout)
                updateMapStateCardVisibility()
                renderMapScene()
            },
            onError = { message ->
                showMapLoadError(message)
            }
        )
    }

    private fun showMapLoadError(message: String) {
        mapReady = false
        mapLoadFailed = true
        binding.mapLoadingIndicator.visibility = View.GONE
        binding.mapErrorIcon.visibility = View.VISIBLE
        binding.mapRetryButton.visibility = View.VISIBLE
        binding.mapStateTitle.setText(R.string.map_error_title)
        binding.mapStateMessage.text = message
        updateMapStateCardVisibility()
    }

    private fun updateMapStateCardVisibility() {
        val screenUsesMap =
            uiState.screen !in setOf(
                NavigationScreen.SEARCH,
                NavigationScreen.SEARCHING
            )

        binding.mapStateCard.visibility =
            if (screenUsesMap && (!mapReady || mapLoadFailed)) {
                View.VISIBLE
            } else {
                View.GONE
            }

        binding.mapControls.visibility =
            if (
                screenUsesMap &&
                mapReady &&
                uiState.screen == NavigationScreen.HOME
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun renderMapScene() {
        if (!::mapController.isInitialized) return

        val results =
            if (uiState.screen == NavigationScreen.RESULTS) {
                uiState.searchResults
            } else {
                emptyList()
            }

        val destination =
            if (
                uiState.screen in setOf(
                    NavigationScreen.CALCULATING_ROUTE,
                    NavigationScreen.ROUTE_PREVIEW,
                    NavigationScreen.ROUTE_ACTIVE,
                    NavigationScreen.ROUTE_OVERVIEW,
                    NavigationScreen.REROUTING,
                    NavigationScreen.ARRIVED,
                    NavigationScreen.ROUTE_ERROR
                )
            ) {
                uiState.destination
            } else {
                null
            }

        val routePlan =
            if (
                uiState.screen in setOf(
                    NavigationScreen.ROUTE_PREVIEW,
                    NavigationScreen.ROUTE_ACTIVE,
                    NavigationScreen.ROUTE_OVERVIEW,
                    NavigationScreen.ARRIVED
                )
            ) {
                uiState.routePlan
            } else {
                null
            }

        mapController.setScene(
            origin = ITI_ORIGIN,
            results = results,
            selectedResultId = uiState.selectedResultId,
            destination = destination,
            routePlan = routePlan,
            vehiclePosition =
                if (
                    uiState.screen in setOf(
                        NavigationScreen.ROUTE_ACTIVE,
                        NavigationScreen.ROUTE_OVERVIEW,
                        NavigationScreen.ARRIVED
                    )
                ) {
                    uiState.vehiclePosition
                } else {
                    null
                },
            followVehicle =
                uiState.screen == NavigationScreen.ROUTE_ACTIVE,
            calculating =
                uiState.screen ==
                    NavigationScreen.CALCULATING_ROUTE ||
                    uiState.screen ==
                    NavigationScreen.REROUTING
        )

        if (!mapReady) return

        binding.stateHost.post {
            when (uiState.screen) {
                NavigationScreen.HOME ->
                    mapController.centerOnOrigin()
                NavigationScreen.RESULTS ->
                    mapController.fitSearchResults()
                NavigationScreen.CALCULATING_ROUTE,
                NavigationScreen.ROUTE_PREVIEW ->
                    mapController.fitRoute()
                NavigationScreen.ROUTE_ACTIVE,
                NavigationScreen.ARRIVED -> Unit
                NavigationScreen.ROUTE_OVERVIEW ->
                    mapController.fitRoute(overview = true)
                NavigationScreen.REROUTING,
                NavigationScreen.ROUTE_ERROR ->
                    if (uiState.routePlan != null) {
                        mapController.fitRoute()
                    } else {
                        mapController.centerOnDestination()
                    }
                else -> Unit
            }
        }
    }

    // ============================================================
    // State, restore, and utility
    // ============================================================

    private fun setScreen(
        screen: NavigationScreen,
        debugOverride: Boolean = false
    ) {
        setState(
            uiState.copy(screen = screen),
            debugOverride = debugOverride
        )
    }

    private fun setState(
        newState: NavigationUiState,
        debugOverride: Boolean = false
    ) {
        val transitioned =
            stateMachine.transitionTo(
                target = newState.screen,
                debugOverride = debugOverride
            )

        if (!transitioned) {
            Log.w(
                TAG_NAVIGATION,
                "Rejected transition " +
                    "${stateMachine.current} -> ${newState.screen}"
            )
            return
        }

        Log.i(
            TAG_NAVIGATION,
            "State transition ${uiState.screen} -> ${newState.screen}"
        )
        uiState = newState
        render()
    }

    private fun synchronizeRestoredNavigationState() {
        val backendState =
            repository.currentNavigationState()

        if (
            backendState.status !=
            NavigationSessionStatus.IDLE
        ) {
            uiState =
                uiStateForRepositoryState(
                    backendState,
                    uiState
                )
            return
        }

        val destination = uiState.destination
        val routePlan = uiState.routePlan
        if (
            destination != null &&
            routePlan != null &&
            uiState.screen in ROUTE_SESSION_SCREENS
        ) {
            repository.restoreNavigationState(
                destination = destination,
                routePlan = routePlan,
                active =
                    uiState.screen in
                        ACTIVE_ROUTE_SESSION_SCREENS
            )
        }
    }

    private fun applyRepositoryState(
        state: NavigationSessionState
    ) {
        val updated =
            uiStateForRepositoryState(state, uiState)
        if (updated != uiState) {
            val positionOnlyUpdate =
                updated.copy(
                    vehiclePosition = uiState.vehiclePosition
                ) == uiState
            if (positionOnlyUpdate) {
                uiState = updated
                if (::mapController.isInitialized) {
                    mapController.updateVehiclePosition(
                        position = updated.vehiclePosition,
                        followCamera =
                            updated.screen ==
                                NavigationScreen.ROUTE_ACTIVE
                    )
                }
                return
            }

            setState(
                updated,
                debugOverride = true
            )
        }
    }

    private fun uiStateForRepositoryState(
        state: NavigationSessionState,
        current: NavigationUiState
    ): NavigationUiState =
        when (state.status) {
            NavigationSessionStatus.IDLE ->
                if (current.screen in ROUTE_SESSION_SCREENS) {
                    NavigationUiState(
                        screen = NavigationScreen.HOME
                    )
                } else {
                    current
                }
            NavigationSessionStatus.CALCULATING ->
                current.copy(
                    screen =
                        NavigationScreen.CALCULATING_ROUTE,
                    destination =
                        state.destination?.place,
                    routePlan = null,
                    vehiclePosition = null,
                    savedDestinationTarget = null,
                    message = null
                )
            NavigationSessionStatus.ROUTE_PREVIEW ->
                current.copy(
                    screen = NavigationScreen.ROUTE_PREVIEW,
                    destination =
                        state.destination?.place,
                    routePlan = state.routePlan,
                    vehiclePosition = null,
                    savedDestinationTarget = null,
                    message = null
                )
            NavigationSessionStatus.ACTIVE ->
                current.copy(
                    screen =
                        if (
                            current.screen ==
                            NavigationScreen.ROUTE_OVERVIEW
                        ) {
                            NavigationScreen.ROUTE_OVERVIEW
                        } else {
                            NavigationScreen.ROUTE_ACTIVE
                        },
                    destination =
                        state.destination?.place,
                    routePlan = state.routePlan,
                    vehiclePosition = state.vehiclePosition,
                    savedDestinationTarget = null,
                    message = null
                )
            NavigationSessionStatus.ARRIVED ->
                current.copy(
                    screen = NavigationScreen.ARRIVED,
                    destination = state.destination?.place,
                    routePlan = state.routePlan,
                    vehiclePosition = state.vehiclePosition,
                    savedDestinationTarget = null,
                    message = null
                )
            NavigationSessionStatus.ERROR ->
                current.copy(
                    screen = NavigationScreen.ROUTE_ERROR,
                    destination =
                        state.destination?.place
                            ?: current.destination,
                    routePlan = null,
                    vehiclePosition = null,
                    message = state.message
                )
        }

    private fun updateClock() {
        if (!::binding.isInitialized) return

        binding.topBar.timeText.text =
            android.text.format.DateFormat
                .getTimeFormat(this)
                .format(Date())
    }

    private fun hideKeyboard() {
        val inputMethodManager =
            getSystemService(
                Context.INPUT_METHOD_SERVICE
            ) as InputMethodManager

        inputMethodManager.hideSoftInputFromWindow(
            currentFocus?.windowToken ?: binding.root.windowToken,
            0
        )
        currentFocus?.clearFocus()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(
            STATE_SNAPSHOT_KEY,
            createStateSnapshot().toString()
        )
        mapView.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun createStateSnapshot(): JSONObject =
        JSONObject()
            .put("screen", uiState.screen.name)
            .put("query", uiState.query)
            .put(
                "nearbyCategory",
                uiState.nearbyCategory?.name
            )
            .put(
                "searchRadiusMeters",
                uiState.searchRadiusMeters
            )
            .put(
                "searchResults",
                JSONArray().apply {
                    uiState.searchResults.forEach {
                        put(NavigationJson.placeToJson(it))
                    }
                }
            )
            .put("selectedResultId", uiState.selectedResultId)
            .put(
                "destination",
                uiState.destination?.let(
                    NavigationJson::placeToJson
                )
            )
            .put(
                "routePlan",
                uiState.routePlan?.let(
                    NavigationJson::routePlanToJson
                )
            )
            .put(
                "savedDestinationTarget",
                uiState.savedDestinationTarget?.name
            )
            .put("message", uiState.message)
            .put(
                "overviewReturnScreen",
                overviewReturnScreen.name
            )
            .put("debugForcedState", debugForcedState)

    private fun restoreUiState(savedInstanceState: Bundle?) {
        val stored =
            savedInstanceState
                ?.getString(STATE_SNAPSHOT_KEY)
                ?: run {
                    uiState = NavigationUiState()
                    return
                }

        uiState =
            runCatching {
                val json = JSONObject(stored)
                val storedScreen =
                    runCatching {
                        NavigationScreen.valueOf(
                            json.optString("screen")
                        )
                    }.getOrDefault(NavigationScreen.HOME)
                val restoredScreen =
                    when (storedScreen) {
                        NavigationScreen.SEARCHING ->
                            NavigationScreen.SEARCH
                        NavigationScreen.CALCULATING_ROUTE ->
                            NavigationScreen.RESULTS
                        else -> storedScreen
                    }
                val resultsJson =
                    json.optJSONArray("searchResults") ?: JSONArray()
                val results = buildList {
                    for (index in 0 until resultsJson.length()) {
                        resultsJson.optJSONObject(index)
                            ?.let(NavigationJson::placeFromJson)
                            ?.let(::add)
                    }
                }
                val destination =
                    json.optJSONObject("destination")
                        ?.let(NavigationJson::placeFromJson)
                val routePlan =
                    json.optJSONObject("routePlan")
                        ?.let(NavigationJson::routePlanFromJson)
                val target =
                    json.optString("savedDestinationTarget")
                        .takeIf { it.isNotBlank() }
                        ?.let {
                            runCatching {
                                SavedDestinationTarget.valueOf(it)
                            }.getOrNull()
                        }
                val nearbyCategory =
                    json.optString("nearbyCategory")
                        .takeIf { it.isNotBlank() }
                        ?.let {
                            runCatching {
                                NearbyCategory.valueOf(it)
                            }.getOrNull()
                        }
                val searchRadiusMeters =
                    if (json.isNull("searchRadiusMeters")) {
                        null
                    } else {
                        json.optInt("searchRadiusMeters")
                            .takeIf { it > 0 }
                    }

                overviewReturnScreen =
                    runCatching {
                        NavigationScreen.valueOf(
                            json.optString(
                                "overviewReturnScreen",
                                NavigationScreen.ROUTE_PREVIEW.name
                            )
                        )
                    }.getOrDefault(
                        NavigationScreen.ROUTE_PREVIEW
                    )
                debugForcedState =
                    json.optBoolean("debugForcedState", false) &&
                        isDebuggable()

                NavigationUiState(
                    screen =
                        if (
                            restoredScreen ==
                            NavigationScreen.RESULTS &&
                            results.isEmpty()
                        ) {
                            NavigationScreen.SEARCH
                        } else {
                            restoredScreen
                        },
                    query = json.optString("query"),
                    nearbyCategory = nearbyCategory,
                    searchRadiusMeters = searchRadiusMeters,
                    searchResults = results,
                    selectedResultId =
                        json.optString("selectedResultId")
                            .takeIf { it.isNotBlank() },
                    destination = destination,
                    routePlan = routePlan,
                    savedDestinationTarget = target,
                    message =
                        if (
                            storedScreen ==
                            NavigationScreen.SEARCHING
                        ) {
                            getString(
                                R.string.category_search_interrupted
                            )
                        } else {
                            json.optString("message")
                                .takeIf { it.isNotBlank() }
                        }
                )
            }.getOrElse {
                Log.w(
                    TAG_NAVIGATION,
                    "State restoration failed",
                    it
                )
                NavigationUiState()
            }
    }

    // ============================================================
    // MapView lifecycle
    // ============================================================

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        repository.removeNavigationStateListener(
            repositoryStateListener
        )
        mainHandler.removeCallbacksAndMessages(null)

        try {
            connectivityManager.unregisterNetworkCallback(
                networkCallback
            )
        } catch (_: RuntimeException) {
            // The callback may not have registered on a restricted build.
        }

        mapController.destroy()
        mapView.onDestroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG_NAVIGATION = "HN-Navigation"
        private const val TAG_SEARCH = "HN-Search"
        private const val TAG_ROUTE = "HN-Route"
        private const val STATE_SNAPSHOT_KEY =
            "hypernova_navigation_state"
        private const val DEBUG_STATE_EXTRA = "debug_state"
        private const val MAP_LOAD_TIMEOUT_MS = 15_000L
        private const val CLOCK_UPDATE_INTERVAL_MS = 30_000L
        private const val KEYBOARD_SHOW_DELAY_MS = 180L
        private const val FIRST_NEARBY_RADIUS_METERS = 5_000
        private const val METERS_PER_KILOMETER = 1_000

        val ITI_ORIGIN =
            NavigationRepository.DEFAULT_ORIGIN

        private val ROUTE_SESSION_SCREENS =
            setOf(
                NavigationScreen.CALCULATING_ROUTE,
                NavigationScreen.ROUTE_PREVIEW,
                NavigationScreen.ROUTE_ACTIVE,
                NavigationScreen.ROUTE_OVERVIEW,
                NavigationScreen.REROUTING,
                NavigationScreen.ARRIVED,
                NavigationScreen.ROUTE_ERROR
            )

        private val ACTIVE_ROUTE_SESSION_SCREENS =
            setOf(
                NavigationScreen.ROUTE_ACTIVE,
                NavigationScreen.ROUTE_OVERVIEW,
                NavigationScreen.REROUTING,
                NavigationScreen.ARRIVED
            )

    }
}
