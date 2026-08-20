package com.hypernova.navigation

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.hypernova.navigation.model.FailureKind
import com.hypernova.navigation.model.NavigationInitializationState
import com.hypernova.navigation.model.NavigationPhase
import com.hypernova.navigation.model.NavigationSessionState
import com.hypernova.navigation.model.RouteData
import com.hypernova.navigation.model.RoutePreparationResult
import com.hypernova.navigation.model.VehiclePosition
import com.hypernova.navigation.navigation.GoogleNavigationGateway
import com.hypernova.navigation.navigation.GoogleRouteResult
import com.hypernova.navigation.navigation.NavigationGatewayListener
import com.hypernova.navigation.navigation.NavigatorReadinessGate
import com.hypernova.navigation.navigation.NavigatorInitializationFailure
import com.hypernova.navigation.persistence.DestinationResolution
import com.hypernova.navigation.persistence.DestinationTokenEntry
import com.hypernova.navigation.persistence.DestinationTokenStore
import com.hypernova.navigation.persistence.SharedPreferencesDestinationTokenPersistence
import com.hypernova.navigation.places.ConfigurationRequiredSearchGateway
import com.hypernova.navigation.places.DestinationSearchGateway
import com.hypernova.navigation.places.GooglePlacesSearchGateway
import com.hypernova.navigation.session.NavigationSessionStore
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NavigationRuntime private constructor(
    private val application: Application,
    val isGoogleConfigured: Boolean,
    private val destinationSearchGateway: DestinationSearchGateway,
    private val sessionStore: NavigationSessionStore,
    val destinationTokenStore: DestinationTokenStore,
) : NavigationGatewayListener {
    private val routeMutex = Mutex()
    private val routeCommandGeneration = AtomicLong(0L)
    private val readinessGate =
        NavigatorReadinessGate(
            if (isGoogleConfigured) NavigatorReadinessGate.State.Waiting
            else NavigatorReadinessGate.State.TerminalFailure(
                NavigatorInitializationFailure.NOT_AUTHORIZED,
            ),
        )
    private val navigationGateway: GoogleNavigationGateway? =
        if (isGoogleConfigured) GoogleNavigationGateway(application, this) else null

    val state: StateFlow<NavigationSessionState> = sessionStore.state

    init {
        if (isGoogleConfigured) initializeWithoutActivityWhenPossible()
    }

    fun attachActivity(activity: Activity, hasFineLocation: Boolean) {
        if (!isGoogleConfigured) return
        if (!googlePlayServicesAvailable()) {
            sessionStore.initialization(
                NavigationInitializationState.GOOGLE_SERVICES_UNAVAILABLE,
                "Google Play services are unavailable on this system.",
                "GOOGLE_PLAY_SERVICES_UNAVAILABLE",
            )
            return
        }
        if (!hasFineLocation) {
            markLocationUnavailable()
            return
        }
        sessionStore.initialization(
            NavigationInitializationState.INITIALIZING,
            "Initializing Google Navigation…",
        )
        navigationGateway?.initialize(activity)
    }

    fun markLocationUnavailable() {
        if (!isGoogleConfigured) return
        sessionStore.initialization(
            NavigationInitializationState.LOCATION_UNAVAILABLE,
            "Precise location permission is required for navigation.",
            "LOCATION_PERMISSION_MISSING",
        )
    }

    suspend fun search(query: String): List<DestinationTokenEntry> {
        val normalized = query.trim().replace(Regex("\\s+"), " ")
        require(normalized.isNotBlank())
        sessionStore.searching(true)
        return try {
            destinationTokenStore.createSearchTokens(destinationSearchGateway.search(normalized))
        } finally {
            sessionStore.searching(false)
        }
    }

    fun savedDestinations(): List<DestinationTokenEntry> = destinationTokenStore.saved()

    fun resolveDestination(token: String): DestinationResolution =
        destinationTokenStore.resolve(token.trim())

    suspend fun prepareDestination(entry: DestinationTokenEntry): RoutePreparationResult {
        val generation = routeCommandGeneration.incrementAndGet()
        return routeMutex.withLock {
            if (generation != routeCommandGeneration.get()) return@withLock superseded()
            sessionStore.calculating(entry.token, entry.record, entry.source)
            val gateway = navigationGateway
                ?: return@withLock fail(
                    FailureKind.CONFIGURATION,
                    "Google Maps configuration is required.",
                )

            if (!gateway.isReady) {
                when (val readiness = readinessGate.await()) {
                    NavigatorReadinessGate.State.Ready -> Unit
                    NavigatorReadinessGate.State.Waiting -> error("Readiness gate returned a waiting state")
                    is NavigatorReadinessGate.State.TerminalFailure -> {
                        return@withLock when (readiness.failure) {
                            NavigatorInitializationFailure.NETWORK ->
                                fail(FailureKind.NETWORK, "Google Play services are unavailable.")
                            NavigatorInitializationFailure.LOCATION_PERMISSION_MISSING ->
                                fail(FailureKind.LOCATION, "Precise location is unavailable.")
                            NavigatorInitializationFailure.TERMS_NOT_ACCEPTED ->
                                fail(FailureKind.TERMS, "Google Navigation terms are required.")
                            else ->
                                fail(FailureKind.AUTHORIZATION, "Google Navigation initialization failed.")
                        }
                    }
                }
            }
            if (generation != routeCommandGeneration.get()) return@withLock superseded()

            val result = gateway.setDestination(entry.record)
            if (generation != routeCommandGeneration.get()) return@withLock superseded()
            when (result) {
                is GoogleRouteResult.Ready ->
                    RoutePreparationResult.Ready(sessionStore.routeReady(result.route))
                GoogleRouteResult.NoRoute -> fail(FailureKind.NO_ROUTE, "Google could not find a route.")
                GoogleRouteResult.NetworkError -> fail(FailureKind.NETWORK, "Google route calculation needs a network connection.")
                GoogleRouteResult.LocationUnavailable -> fail(FailureKind.LOCATION, "A current location is unavailable.")
                GoogleRouteResult.Cancelled -> fail(FailureKind.CANCELLED, "Route calculation was cancelled.")
                GoogleRouteResult.AuthorizationError -> fail(FailureKind.AUTHORIZATION, "Google Navigation authorization failed.")
                is GoogleRouteResult.InternalError -> fail(FailureKind.INTERNAL, result.message)
            }
        }
    }

    fun startGuidance(): Boolean {
        val started = navigationGateway?.startGuidance() == true
        if (started) sessionStore.guidanceStarted()
        return started
    }

    fun cancelNavigation() {
        routeCommandGeneration.incrementAndGet()
        navigationGateway?.cancelNavigation()
        sessionStore.cancelled()
    }

    internal fun googleGatewayForDebug(): GoogleNavigationGateway? = navigationGateway

    internal fun beginDebugSimulation(destination: com.hypernova.navigation.model.GoogleDestinationRecord) {
        sessionStore.calculating(
            "debug_google_simulation",
            destination,
            com.hypernova.contracts.navigation.NavigationContract.SOURCE_SEARCH,
            simulated = true,
        )
    }

    internal fun debugSimulationRouteReady(route: RouteData) {
        sessionStore.routeReady(route, simulated = true)
        sessionStore.guidanceStarted()
    }

    internal fun debugSimulationFailed(message: String) {
        sessionStore.routeFailure(FailureKind.INTERNAL, message)
    }

    override fun onNavigatorReady() {
        readinessGate.ready()
        sessionStore.initialization(
            NavigationInitializationState.READY_IDLE,
            "Google Navigation ready",
        )
    }

    override fun onNavigatorInitializationFailed(failure: NavigatorInitializationFailure) {
        if (
            failure in
            setOf(
                NavigatorInitializationFailure.NOT_AUTHORIZED,
                NavigatorInitializationFailure.NETWORK,
                NavigatorInitializationFailure.INTERNAL,
            )
        ) {
            readinessGate.terminal(failure)
        } else {
            readinessGate.waiting()
        }
        when (failure) {
            NavigatorInitializationFailure.TERMS_NOT_ACCEPTED ->
                sessionStore.initialization(
                    NavigationInitializationState.TERMS_REQUIRED,
                    "Google Navigation terms must be accepted.",
                    "TERMS_NOT_ACCEPTED",
                )
            NavigatorInitializationFailure.LOCATION_PERMISSION_MISSING -> markLocationUnavailable()
            NavigatorInitializationFailure.NETWORK ->
                sessionStore.initialization(
                    NavigationInitializationState.GOOGLE_SERVICES_UNAVAILABLE,
                    "Google Navigation could not reach Google services.",
                    "GOOGLE_NETWORK_ERROR",
                )
            NavigatorInitializationFailure.NOT_AUTHORIZED ->
                sessionStore.initialization(
                    NavigationInitializationState.ERROR,
                    "The Google API key is not authorized for this app and signing certificate.",
                    "GOOGLE_NOT_AUTHORIZED",
                )
            NavigatorInitializationFailure.INTERNAL ->
                sessionStore.initialization(
                    NavigationInitializationState.ERROR,
                    "Google Navigation initialization failed.",
                    "GOOGLE_INITIALIZATION_ERROR",
                )
        }
    }

    override fun onRouteChanged(route: RouteData) {
        if (sessionStore.current().phase != NavigationPhase.CALCULATING) {
            sessionStore.routeChanged(route)
        }
    }
    override fun onRerouting() = sessionStore.rerouting()
    override fun onProgress(etaSeconds: Long, distanceMeters: Long) =
        sessionStore.progress(etaSeconds, distanceMeters)
    override fun onPosition(position: VehiclePosition) = sessionStore.position(position)
    override fun onArrival() = sessionStore.arrived()

    private fun initializeWithoutActivityWhenPossible() {
        if (!googlePlayServicesAvailable()) {
            onNavigatorInitializationFailed(NavigatorInitializationFailure.NETWORK)
            return
        }
        val permission =
            ContextCompat.checkSelfPermission(application, Manifest.permission.ACCESS_FINE_LOCATION)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            markLocationUnavailable()
            return
        }
        navigationGateway?.initialize()
    }

    private fun googlePlayServicesAvailable(): Boolean =
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(application) == ConnectionResult.SUCCESS

    private fun fail(kind: FailureKind, message: String): RoutePreparationResult.Failed {
        sessionStore.routeFailure(kind, message)
        return RoutePreparationResult.Failed(kind, message)
    }

    private fun superseded(): RoutePreparationResult.Failed =
        RoutePreparationResult.Failed(
            FailureKind.CANCELLED,
            "Route calculation was superseded.",
        )

    companion object {
        fun create(application: Application, apiKey: String, configured: Boolean): NavigationRuntime {
            val sessionStore =
                NavigationSessionStore(
                    NavigationSessionState(
                        initialization =
                            if (configured) NavigationInitializationState.INITIALIZING
                            else NavigationInitializationState.CONFIGURATION_REQUIRED,
                        statusMessage =
                            if (configured) "Initializing Google Navigation…"
                            else "Add a restricted Google Maps Platform API key to secrets.properties.",
                    ),
                )
            val searchGateway: DestinationSearchGateway =
                if (configured) GooglePlacesSearchGateway(application, apiKey)
                else ConfigurationRequiredSearchGateway()
            return NavigationRuntime(
                application = application,
                isGoogleConfigured = configured,
                destinationSearchGateway = searchGateway,
                sessionStore = sessionStore,
                destinationTokenStore =
                    DestinationTokenStore(
                        SharedPreferencesDestinationTokenPersistence(application),
                    ),
            )
        }
    }
}
