package com.hypernova.navigation

import android.app.Activity
import android.app.Application
import android.view.ViewGroup
import com.hypernova.navigation.model.FailureKind
import com.hypernova.navigation.model.NavigationInitializationState
import com.hypernova.navigation.model.NavigationPhase
import com.hypernova.navigation.model.NavigationSessionState
import com.hypernova.navigation.model.RouteData
import com.hypernova.navigation.model.RoutePreparationResult
import com.hypernova.navigation.model.VehiclePosition
import com.hypernova.navigation.navigation.GoogleRouteResult
import com.hypernova.navigation.navigation.NavigationGateway
import com.hypernova.navigation.navigation.NavigationGatewayListener
import com.hypernova.navigation.navigation.NavigatorReadinessGate
import com.hypernova.navigation.navigation.NavigatorInitializationFailure
import com.hypernova.navigation.persistence.DestinationResolution
import com.hypernova.navigation.persistence.DestinationTokenEntry
import com.hypernova.navigation.persistence.DestinationTokenStore
import com.hypernova.navigation.persistence.SharedPreferencesDestinationTokenPersistence
import com.hypernova.navigation.persistence.SavedDestinationDefaults
import com.hypernova.navigation.places.ConfigurationRequiredSearchGateway
import com.hypernova.navigation.places.DestinationSearchGateway
import com.hypernova.navigation.places.GooglePlacesException
import com.hypernova.navigation.places.PlaceContact
import com.hypernova.navigation.session.NavigationSessionStore
import com.hypernova.navigation.web.GoogleMapsWebGateway
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NavigationRuntime private constructor(
    private val application: Application,
    private val apiKey: String,
    val isGoogleConfigured: Boolean,
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
    private val navigationGateway: NavigationGateway? =
        if (isGoogleConfigured) GoogleMapsWebGateway(application, apiKey, this) else null
    private val destinationSearchGateway: DestinationSearchGateway =
        (navigationGateway as? DestinationSearchGateway) ?: ConfigurationRequiredSearchGateway()
    private val mutableMapDestinationRequests =
        MutableSharedFlow<DestinationTokenEntry>(extraBufferCapacity = 1)

    val state: StateFlow<NavigationSessionState> = sessionStore.state
    val mapDestinationRequests: SharedFlow<DestinationTokenEntry> =
        mutableMapDestinationRequests.asSharedFlow()

    fun attachActivity(activity: Activity) {
        if (!isGoogleConfigured) return
        if (navigationGateway?.isReady == true) {
            onNavigatorReady()
            return
        }
        readinessGate.waiting()
        sessionStore.initialization(
            NavigationInitializationState.INITIALIZING,
            "Connecting to Google Maps…",
        )
        navigationGateway?.initialize(activity)
    }

    fun attachMapSurface(container: ViewGroup) = navigationGateway?.attachSurface(container)

    fun detachMapSurface(container: ViewGroup) = navigationGateway?.detachSurface(container)

    fun setMapSurfaceInsets(topPixels: Int, bottomPixels: Int) =
        navigationGateway?.setSurfaceInsets(topPixels, bottomPixels)

    val supportsGuidance: Boolean
        get() = navigationGateway?.supportsGuidance == true

    suspend fun search(query: String): List<DestinationTokenEntry> {
        val normalized = query.trim().replace(Regex("\\s+"), " ")
        require(normalized.isNotBlank())
        sessionStore.searching(true)
        return try {
            navigationGateway?.initialize()
            destinationTokenStore.createSearchTokens(destinationSearchGateway.search(normalized))
        } finally {
            sessionStore.searching(false)
        }
    }

    fun savedDestinations(): List<DestinationTokenEntry> = destinationTokenStore.saved()

    fun resolveDestination(token: String): DestinationResolution =
        destinationTokenStore.resolve(token.trim())

    suspend fun destinationContact(entry: DestinationTokenEntry): PlaceContact? {
        val gateway = navigationGateway as? GoogleMapsWebGateway
            ?: throw GooglePlacesException.ConfigurationRequired
        gateway.initialize()
        if (!gateway.isReady) {
            when (val readiness = readinessGate.await()) {
                NavigatorReadinessGate.State.Ready -> Unit
                NavigatorReadinessGate.State.Waiting -> error("Readiness gate returned a waiting state")
                is NavigatorReadinessGate.State.TerminalFailure ->
                    throw GooglePlacesException.RequestFailed(
                        IllegalStateException("Google Maps is unavailable: ${readiness.failure}"),
                    )
            }
        }
        return gateway.contact(entry.record)
    }

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

            // Saved destinations can calculate a route without a preceding search. Start the
            // lazy Google engine here as well, then wait on the same readiness gate.
            gateway.initialize()

            if (!gateway.isReady) {
                when (val readiness = readinessGate.await()) {
                    NavigatorReadinessGate.State.Ready -> Unit
                    NavigatorReadinessGate.State.Waiting -> error("Readiness gate returned a waiting state")
                    is NavigatorReadinessGate.State.TerminalFailure -> {
                        return@withLock when (readiness.failure) {
                            NavigatorInitializationFailure.NETWORK ->
                                fail(FailureKind.NETWORK, "Google Maps is unavailable over the network.")
                            NavigatorInitializationFailure.LOCATION_PERMISSION_MISSING ->
                                fail(FailureKind.LOCATION, "Precise location is unavailable.")
                            NavigatorInitializationFailure.TERMS_NOT_ACCEPTED ->
                                fail(FailureKind.TERMS, "Google Maps terms are required.")
                            else ->
                                fail(FailureKind.AUTHORIZATION, "Google Maps initialization failed.")
                        }
                    }
                }
            }
            if (generation != routeCommandGeneration.get()) return@withLock superseded()

            val result = gateway.setDestination(entry.record)
            if (generation != routeCommandGeneration.get()) return@withLock superseded()
            when (result) {
                is GoogleRouteResult.Ready ->
                    RoutePreparationResult.Ready(
                        sessionStore.routeReady(result.route, simulated = result.usesDemoOrigin),
                    )
                GoogleRouteResult.NoRoute -> fail(FailureKind.NO_ROUTE, "Google could not find a route.")
                GoogleRouteResult.NetworkError -> fail(FailureKind.NETWORK, "Google route calculation needs a network connection.")
                GoogleRouteResult.LocationUnavailable -> fail(FailureKind.LOCATION, "A current location is unavailable.")
                GoogleRouteResult.Cancelled -> fail(FailureKind.CANCELLED, "Route calculation was cancelled.")
                GoogleRouteResult.AuthorizationError -> fail(FailureKind.AUTHORIZATION, "Google Maps authorization failed.")
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
            "Google Maps ready",
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
                    "Google Maps terms must be accepted.",
                    "TERMS_NOT_ACCEPTED",
                )
            NavigatorInitializationFailure.LOCATION_PERMISSION_MISSING ->
                sessionStore.initialization(
                    NavigationInitializationState.LOCATION_UNAVAILABLE,
                    "A route origin is unavailable.",
                    "LOCATION_UNAVAILABLE",
                )
            NavigatorInitializationFailure.NETWORK ->
                sessionStore.initialization(
                    NavigationInitializationState.GOOGLE_SERVICES_UNAVAILABLE,
                    "Google Maps could not reach Google services.",
                    "GOOGLE_NETWORK_ERROR",
                )
            NavigatorInitializationFailure.NOT_AUTHORIZED ->
                sessionStore.initialization(
                    NavigationInitializationState.ERROR,
                    "The Google Maps browser key is not authorized for this app origin.",
                    "GOOGLE_NOT_AUTHORIZED",
                )
            NavigatorInitializationFailure.INTERNAL ->
                sessionStore.initialization(
                    NavigationInitializationState.ERROR,
                    "Google Maps initialization failed.",
                    "GOOGLE_INITIALIZATION_ERROR",
                )
        }
    }

    override fun onMapDestinationRequested(destination: com.hypernova.navigation.model.GoogleDestinationRecord) {
        val entry = destinationTokenStore.createSearchTokens(listOf(destination)).singleOrNull() ?: return
        mutableMapDestinationRequests.tryEmit(entry)
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
                            if (configured) NavigationInitializationState.READY_IDLE
                            else NavigationInitializationState.CONFIGURATION_REQUIRED,
                        statusMessage =
                            if (configured) "Google Maps ready when needed"
                            else "Add a restricted Google Maps Platform API key to secrets.properties.",
                    ),
                )
            val destinationTokenStore =
                DestinationTokenStore(
                    SharedPreferencesDestinationTokenPersistence(application),
                )
            SavedDestinationDefaults.seedMissingHome(destinationTokenStore)
            return NavigationRuntime(
                application = application,
                apiKey = apiKey,
                isGoogleConfigured = configured,
                sessionStore = sessionStore,
                destinationTokenStore = destinationTokenStore,
            )
        }
    }
}
