package com.hypernova.navigation.domain.repository

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.data.nominatim.NominatimClient
import com.hypernova.navigation.data.osrm.OsrmClient
import com.hypernova.navigation.data.overpass.OverpassClient
import com.hypernova.navigation.data.persistence.NavigationPreferences
import com.hypernova.navigation.domain.model.DestinationResolution
import com.hypernova.navigation.domain.model.FailureKind
import com.hypernova.navigation.domain.model.GeoDistance
import com.hypernova.navigation.domain.model.GeoPoint
import com.hypernova.navigation.domain.model.NavigationDataException
import com.hypernova.navigation.domain.model.NavigationSessionState
import com.hypernova.navigation.domain.model.NearbyCategory
import com.hypernova.navigation.domain.model.Place
import com.hypernova.navigation.domain.model.ResolvedDestination
import com.hypernova.navigation.domain.model.RoutePlan
import com.hypernova.navigation.domain.model.VerifiedDemoPlaces
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class NavigationRepository(
    private val nominatimClient: NominatimClient = NominatimClient(),
    private val overpassClient: OverpassClient = OverpassClient(),
    private val osrmClient: OsrmClient = OsrmClient(),
    private val preferences: NavigationPreferences? = null,
    private val destinationStore: DestinationStore =
        DestinationStore(),
    private val navigationSession: NavigationSession =
        NavigationSession(),
    private val originProvider: () -> GeoPoint? = {
        DEFAULT_ORIGIN
    }
) {
    private val executor: ExecutorService =
        Executors.newFixedThreadPool(3)

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private val textSearchGeneration =
        RequestGenerationGate()

    private val categorySearchGeneration =
        RequestGenerationGate()

    private val routeGeneration =
        RequestGenerationGate()

    private val textLock = Any()
    private val categoryLock = Any()
    private val routeLock = Any()

    @Volatile
    private var textSearchFuture: Future<*>? = null

    @Volatile
    private var categorySearchFuture: Future<*>? = null

    @Volatile
    private var routeFuture: Future<*>? = null

    @Volatile
    private var activeNearbyRequest: ActiveNearbyRequest? = null

    private var lastNominatimRequestTimeMs = 0L
    private var lastOverpassRequestTimeMs = 0L

    private val textSearchCache =
        object : LinkedHashMap<String, List<Place>>(
            MAX_CACHED_SEARCHES,
            CACHE_LOAD_FACTOR,
            true
        ) {
            override fun removeEldestEntry(
                eldest:
                    MutableMap.MutableEntry<
                        String,
                        List<Place>
                    >?
            ): Boolean =
                size > MAX_CACHED_SEARCHES
        }

    private val nearbySearchCache =
        object : LinkedHashMap<
            NearbyCacheKey,
            List<Place>
        >(
            MAX_CACHED_NEARBY_SEARCHES,
            CACHE_LOAD_FACTOR,
            true
        ) {
            override fun removeEldestEntry(
                eldest:
                    MutableMap.MutableEntry<
                        NearbyCacheKey,
                        List<Place>
                    >?
            ): Boolean =
                size > MAX_CACHED_NEARBY_SEARCHES
        }

    fun searchTextPlace(
        query: String,
        callback: (Result<List<Place>>) -> Unit
    ): Int {
        cancelNearbySearch()

        val generation: Int

        synchronized(textLock) {
            generation =
                textSearchGeneration.next()

            textSearchFuture?.cancel(true)
            textSearchFuture = null
        }

        val cacheKey =
            query
                .trim()
                .lowercase(Locale.ROOT)

        synchronized(textSearchCache) {
            textSearchCache[cacheKey]
        }?.let { cached ->
            Log.i(
                TAG_REPOSITORY,
                "text generation=$generation cache=hit " +
                    "results=${cached.size}"
            )

            deliverTextSearch(
                generation = generation,
                result = Result.success(cached),
                callback = callback
            )

            return generation
        }

        Log.i(
            TAG_REPOSITORY,
            "text generation=$generation cache=miss"
        )

        val submittedFuture =
            executor.submit {
                try {
                    waitForNominatimRateLimit()

                    val places =
                        nominatimClient.search(query)

                    synchronized(textSearchCache) {
                        textSearchCache[cacheKey] =
                            places
                    }

                    deliverTextSearch(
                        generation = generation,
                        result =
                            Result.success(places),
                        callback = callback
                    )
                } catch (throwable: Throwable) {
                    deliverTextSearch(
                        generation = generation,
                        result =
                            Result.failure(
                                normalizeFailure(
                                    throwable
                                )
                            ),
                        callback = callback
                    )
                } finally {
                    synchronized(textLock) {
                        if (
                            textSearchGeneration
                                .isCurrent(generation)
                        ) {
                            textSearchFuture = null
                        }
                    }
                }
            }

        synchronized(textLock) {
            if (
                textSearchGeneration
                    .isCurrent(generation)
            ) {
                textSearchFuture =
                    submittedFuture
            } else {
                submittedFuture.cancel(true)
            }
        }

        return generation
    }

    fun searchDestinations(
        query: String,
        callback:
            (
                Result<List<ResolvedDestination>>
            ) -> Unit
    ): Int =
        searchTextPlace(query) { result ->
            callback(
                result.map { places ->
                    destinationStore.issueSearch(
                        places =
                            DestinationSearchPolicy
                                .limitResults(
                                    places
                                ),
                        origin =
                            originProvider()
                    )
                }
            )
        }

    fun getSavedDestinations():
        List<ResolvedDestination> {
        val savedPlaces =
            currentSavedPlaces()

        return destinationStore.refreshSaved(
            places = savedPlaces,
            origin = originProvider()
        )
    }

    fun resolveDestination(
        destinationId: String
    ): DestinationResolution {
        destinationStore.refreshSaved(
            places = currentSavedPlaces(),
            origin = originProvider()
        )

        return destinationStore.resolve(
            destinationId
        )
    }

    fun currentNavigationState():
        NavigationSessionState =
        navigationSession.current()

    fun addNavigationStateListener(
        listener:
            (NavigationSessionState) -> Unit
    ) {
        navigationSession.addListener(
            listener
        )
    }

    fun removeNavigationStateListener(
        listener:
            (NavigationSessionState) -> Unit
    ) {
        navigationSession.removeListener(
            listener
        )
    }

    fun activateCurrentRoute(): Boolean =
        navigationSession.activate()

    fun selectRouteAlternative(
        routePlan: RoutePlan
    ): Boolean =
        navigationSession.selectRoute(
            routePlan
        )

    fun restoreNavigationState(
        destination: Place,
        routePlan: RoutePlan,
        active: Boolean
    ) {
        val resolved =
            destinationStore.issueSearch(
                places =
                    listOf(destination),
                origin =
                    originProvider()
            ).single()

        navigationSession.restore(
            destination = resolved,
            routePlan = routePlan,
            active = active
        )
    }

    fun startNavigation(
        destination: ResolvedDestination,
        callback:
            (
                Result<NavigationSessionState>
            ) -> Unit
    ): Int {
        val origin =
            originProvider()

        if (origin == null) {
            val failure =
                NavigationDataException(
                    kind =
                        FailureKind
                            .LOCATION_UNAVAILABLE,
                    message =
                        "The navigation origin is unavailable."
                )

            navigationSession.fail(
                destination = destination,
                message = failure.message
            )

            mainHandler.post {
                callback(
                    Result.failure(failure)
                )
            }

            return NO_GENERATION
        }

        preferences?.addRecent(
            destination.place
        )

        return requestRoute(
            origin = origin,
            destination = destination,
            activateWhenReady = true
        ) { result ->
            result.fold(
                onSuccess = {
                    val authoritative =
                        navigationSession
                            .current()

                    callback(
                        Result.success(
                            authoritative
                        )
                    )
                },
                onFailure = {
                    callback(
                        Result.failure(it)
                    )
                }
            )
        }
    }

    fun cancelNavigation(): Boolean {
        cancelRoute()

        return navigationSession.cancel()
    }

    private fun currentSavedPlaces():
        List<SavedPlace> {
        val storage =
            preferences
                ?: return emptyList()

        return SavedDestinationSelector
            .select(
                home = storage.home,
                work = storage.work,
                recents = storage.recents,
                limit =
                    NavigationContract
                        .MAX_DESTINATION_RESULTS
            )
    }

    fun isNearbySearchRunning(
        category: NearbyCategory,
        origin: GeoPoint
    ): Boolean {
        val active =
            activeNearbyRequest
                ?: return false

        return active.category == category &&
            active.origin == origin &&
            categorySearchFuture
                ?.isDone == false
    }

    /**
     * Returns the request generation.
     *
     * If an identical request is already active,
     * the existing generation is returned and no
     * second request is created.
     *
     * Search priority:
     *
     * 1. Valid live in-memory cache.
     * 2. Live Overpass provider.
     * 3. Existing partial live result if a later
     *    Overpass request fails.
     * 4. Verified OSM demo fallback when no live
     *    result exists and the origin is close to
     *    the configured ITI demo origin.
     *
     * Verified fallback results are intentionally
     * not stored in the live nearby cache.
     */
    fun searchNearbyCategory(
        category: NearbyCategory,
        origin: GeoPoint,
        onProgress:
            (NearbySearchProgress) -> Unit,
        callback:
            (
                Result<NearbySearchResult>
            ) -> Unit
    ): Int {
        cancelTextSearch()

        val generation: Int

        synchronized(categoryLock) {
            val active =
                activeNearbyRequest

            if (
                active != null &&
                active.category == category &&
                active.origin == origin &&
                categorySearchFuture
                    ?.isDone == false
            ) {
                Log.i(
                    TAG_CATEGORY,
                    "generation=" +
                        "${active.generationId} " +
                        "category=${category.name} " +
                        "duplicate=ignored"
                )

                return active.generationId
            }

            generation =
                categorySearchGeneration.next()

            categorySearchFuture
                ?.cancel(true)

            categorySearchFuture = null

            activeNearbyRequest =
                ActiveNearbyRequest(
                    generationId =
                        generation,
                    category = category,
                    origin = origin
                )
        }

        Log.i(
            TAG_CATEGORY,
            "generation=$generation " +
                "category=${category.name} " +
                "request=start"
        )

        val cached =
            cachedNearbyResult(
                category = category,
                origin = origin
            )

        if (
            cached != null &&
            NearbySearchPolicy
                .shouldUseCachedResultAsTerminal(
                    resultCount =
                        cached.places.size,
                    radiusMeters =
                        cached.finalRadiusMeters
                )
        ) {
            Log.i(
                TAG_CATEGORY,
                "generation=$generation " +
                    "category=${category.name} " +
                    "radius=" +
                    "${cached.finalRadiusMeters} " +
                    "cache=hit terminal=true " +
                    "results=${cached.places.size}"
            )

            deliverNearbyProgress(
                generation = generation,
                progress =
                    NearbySearchProgress(
                        category = category,
                        radiusMeters =
                            cached.finalRadiusMeters,
                        isExpansion =
                            cached.finalRadiusMeters >
                                NearbySearchPolicy
                                    .RADII_METERS
                                    .first(),
                        generationId =
                            generation
                    ),
                callback = onProgress
            )

            finishNearbySearch(
                generation = generation,
                result =
                    Result.success(
                        cached.copy(
                            fromCache = true
                        )
                    ),
                callback = callback
            )

            synchronized(categoryLock) {
                if (
                    categorySearchGeneration
                        .isCurrent(generation)
                ) {
                    activeNearbyRequest = null
                }
            }

            return generation
        }

        val submittedFuture =
            executor.submit {
                val accumulator =
                    NearbyResultAccumulator()

                var terminalResult:
                    Result<NearbySearchResult>? =
                    null

                try {
                    val startRadiusIndex =
                        if (cached == null) {
                            0
                        } else {
                            accumulator
                                .recordSuccess(
                                    places =
                                        cached.places,
                                    radiusMeters =
                                        cached
                                            .finalRadiusMeters
                                )

                            Log.i(
                                TAG_CATEGORY,
                                "generation=$generation " +
                                    "category=" +
                                    "${category.name} " +
                                    "radius=" +
                                    "${cached.finalRadiusMeters} " +
                                    "cache=hit " +
                                    "terminal=false " +
                                    "results=" +
                                    "${cached.places.size}"
                            )

                            (
                                NearbySearchPolicy
                                    .RADII_METERS
                                    .indexOf(
                                        cached
                                            .finalRadiusMeters
                                    ) + 1
                            ).coerceAtLeast(0)
                        }

                    for (
                        radiusIndex in
                        startRadiusIndex until
                            NearbySearchPolicy
                                .RADII_METERS
                                .size
                    ) {
                        ensureCurrentCategoryRequest(
                            generation
                        )

                        val radiusMeters =
                            NearbySearchPolicy
                                .RADII_METERS[
                                    radiusIndex
                                ]

                        deliverNearbyProgress(
                            generation =
                                generation,
                            progress =
                                NearbySearchProgress(
                                    category =
                                        category,
                                    radiusMeters =
                                        radiusMeters,
                                    isExpansion =
                                        radiusIndex > 0,
                                    generationId =
                                        generation
                                ),
                            callback =
                                onProgress
                        )

                        waitForOverpassRateLimit()

                        Log.i(
                            TAG_CATEGORY,
                            "generation=$generation " +
                                "category=" +
                                "${category.name} " +
                                "radius=$radiusMeters " +
                                "cache=miss"
                        )

                        val places =
                            try {
                                overpassClient.search(
                                    category =
                                        category,
                                    origin =
                                        origin,
                                    radiusMeters =
                                        radiusMeters
                                )
                            } catch (
                                failure:
                                    NavigationDataException
                            ) {
                                if (
                                    failure.kind ==
                                    FailureKind
                                        .CANCELLED
                                ) {
                                    throw failure
                                }

                                terminalResult =
                                    completeNearbyAfterFailure(
                                        generation =
                                            generation,
                                        category =
                                            category,
                                        origin =
                                            origin,
                                        accumulator =
                                            accumulator,
                                        failure =
                                            failure
                                    )

                                break
                            }

                        accumulator
                            .recordSuccess(
                                places = places,
                                radiusMeters =
                                    radiusMeters
                            )

                        val current =
                            accumulator.complete()

                        if (
                            NearbySearchPolicy
                                .shouldCache(
                                    current
                                )
                        ) {
                            cacheNearbyResult(
                                category =
                                    category,
                                origin =
                                    origin,
                                result =
                                    current
                            )
                        }

                        if (
                            NearbySearchPolicy
                                .shouldStop(
                                    usefulResultCount =
                                        places.size,
                                    radiusIndex =
                                        radiusIndex
                                )
                        ) {
                            break
                        }
                    }

                    val providerOutcome =
                        terminalResult
                            ?: Result.success(
                                accumulator
                                    .complete()
                            )

                    val outcome =
                        applyVerifiedFallbackToEmptyResult(
                            generation =
                                generation,
                            category =
                                category,
                            origin =
                                origin,
                            providerOutcome =
                                providerOutcome
                        )

                    logNearbyOutcome(
                        generation =
                            generation,
                        category = category,
                        outcome = outcome
                    )

                    finishNearbySearch(
                        generation =
                            generation,
                        result = outcome,
                        callback = callback
                    )
                } catch (
                    throwable: Throwable
                ) {
                    val failure =
                        normalizeFailure(
                            throwable
                        )

                    val outcome =
                        if (
                            failure.kind ==
                            FailureKind.CANCELLED
                        ) {
                            Result.failure(
                                failure
                            )
                        } else {
                            completeNearbyAfterFailure(
                                generation =
                                    generation,
                                category =
                                    category,
                                origin =
                                    origin,
                                accumulator =
                                    accumulator,
                                failure =
                                    failure
                            )
                        }

                    logNearbyOutcome(
                        generation =
                            generation,
                        category = category,
                        outcome = outcome
                    )

                    finishNearbySearch(
                        generation =
                            generation,
                        result = outcome,
                        callback = callback
                    )
                } finally {
                    synchronized(
                        categoryLock
                    ) {
                        if (
                            categorySearchGeneration
                                .isCurrent(
                                    generation
                                )
                        ) {
                            categorySearchFuture =
                                null

                            activeNearbyRequest =
                                null
                        }
                    }
                }
            }

        synchronized(categoryLock) {
            if (
                categorySearchGeneration
                    .isCurrent(generation)
            ) {
                categorySearchFuture =
                    submittedFuture
            } else {
                submittedFuture
                    .cancel(true)
            }
        }

        return generation
    }

    /**
     * Prefer a real result already collected from
     * Overpass/cache.
     *
     * Only when no real live result exists do we
     * attempt the deterministic verified OSM
     * fallback for the ITI demo region.
     */
    private fun completeNearbyAfterFailure(
        generation: Int,
        category: NearbyCategory,
        origin: GeoPoint,
        accumulator: NearbyResultAccumulator,
        failure: NavigationDataException
    ): Result<NearbySearchResult> {
        val liveResult =
            accumulator.completeAfterFailure(
                failure
            )

        if (liveResult.isSuccess) {
            return liveResult
        }

        return verifiedDemoFallback(
            generation = generation,
            category = category,
            origin = origin,
            failure = failure
        ) ?: liveResult
    }

    /**
     * A provider can also successfully return zero
     * results at every radius.
     *
     * For the configured ITI demo region, use
     * known real OSM POIs rather than returning an
     * empty screen during the demo.
     */
    private fun applyVerifiedFallbackToEmptyResult(
        generation: Int,
        category: NearbyCategory,
        origin: GeoPoint,
        providerOutcome:
            Result<NearbySearchResult>
    ): Result<NearbySearchResult> {
        val providerResult =
            providerOutcome.getOrNull()
                ?: return providerOutcome

        if (providerResult.places.isNotEmpty()) {
            return providerOutcome
        }

        return verifiedDemoFallback(
            generation = generation,
            category = category,
            origin = origin,
            failure = null
        ) ?: providerOutcome
    }

    /**
     * Deterministic demo fallback.
     *
     * Safety rule:
     * The verified POIs are defined around the
     * configured ITI Smart Village demo origin.
     *
     * They must never be returned when the actual
     * search origin is far away from that region.
     *
     * The results are not inserted into
     * nearbySearchCache, allowing a future search
     * to retry the live provider normally.
     */
    private fun verifiedDemoFallback(
        generation: Int,
        category: NearbyCategory,
        origin: GeoPoint,
        failure: NavigationDataException?
    ): Result<NearbySearchResult>? {
        val demoOriginDistanceMeters =
            GeoDistance.meters(
                from = DEFAULT_ORIGIN,
                to = origin
            )

        if (
            demoOriginDistanceMeters >
            VERIFIED_FALLBACK_ORIGIN_RADIUS_METERS
        ) {
            Log.w(
                TAG_CATEGORY,
                "generation=$generation " +
                    "category=${category.name} " +
                    "fallback=skipped " +
                    "reason=outside_demo_region " +
                    "originDistance=" +
                    demoOriginDistanceMeters
                        .toLong()
            )

            return null
        }

        val places =
            VerifiedDemoPlaces
                .forCategory(category)
                .map { place ->
                    place.copy(
                        straightLineDistanceMeters =
                            GeoDistance.meters(
                                from = origin,
                                to =
                                    GeoPoint(
                                        latitude =
                                            place.latitude,
                                        longitude =
                                            place.longitude
                                    )
                            )
                    )
                }

        if (places.isEmpty()) {
            Log.w(
                TAG_CATEGORY,
                "generation=$generation " +
                    "category=${category.name} " +
                    "fallback=unavailable " +
                    "reason=no_verified_places"
            )

            return null
        }

        val displayedPlaces =
            NearbySearchPolicy
                .displayResults(places)

        val farthestDistanceMeters =
            displayedPlaces
                .mapNotNull {
                    it.straightLineDistanceMeters
                }
                .maxOrNull()
                ?: 0.0

        val fallbackRadiusMeters =
            NearbySearchPolicy
                .RADII_METERS
                .firstOrNull {
                    it.toDouble() >=
                        farthestDistanceMeters
                }
                ?: NearbySearchPolicy
                    .MAX_RADIUS_METERS

        Log.w(
            TAG_CATEGORY,
            "generation=$generation " +
                "category=${category.name} " +
                "fallback=verified_osm " +
                "results=${displayedPlaces.size} " +
                "radius=$fallbackRadiusMeters " +
                "providerFailure=" +
                (failure?.kind ?: "NO_LIVE_RESULTS")
        )

        return Result.success(
            NearbySearchResult(
                places = displayedPlaces,
                finalRadiusMeters =
                    fallbackRadiusMeters,
                widerSearchUnavailable =
                    true,
                partialFailure = failure,
                fromCache = false
            )
        )
    }

    fun calculateRoute(
        origin: GeoPoint,
        destination: Place,
        callback:
            (Result<RoutePlan>) -> Unit
    ): Int {
        val resolvedDestination =
            destinationStore.issueSearch(
                places =
                    listOf(destination),
                origin = origin
            ).single()

        preferences?.addRecent(
            destination
        )

        return requestRoute(
            origin = origin,
            destination =
                resolvedDestination,
            activateWhenReady = false,
            callback = callback
        )
    }

    private fun requestRoute(
        origin: GeoPoint,
        destination: ResolvedDestination,
        activateWhenReady: Boolean,
        callback:
            (Result<RoutePlan>) -> Unit
    ): Int {
        val generation: Int

        synchronized(routeLock) {
            generation =
                routeGeneration.next()

            routeFuture?.cancel(true)
        }

        navigationSession
            .beginCalculation(destination)

        val submittedFuture =
            executor.submit {
                try {
                    val route =
                        osrmClient.route(
                            origin,
                            destination.place
                        )

                    mainHandler.post {
                        if (
                            routeGeneration
                                .isCurrent(
                                    generation
                                )
                        ) {
                            if (
                                activateWhenReady
                            ) {
                                navigationSession
                                    .activate(
                                        destination =
                                            destination,
                                        routePlan =
                                            route
                                    )
                            } else {
                                navigationSession
                                    .showRoutePreview(
                                        destination =
                                            destination,
                                        routePlan =
                                            route
                                    )
                            }

                            callback(
                                Result.success(
                                    route
                                )
                            )
                        }
                    }
                } catch (
                    throwable: Throwable
                ) {
                    val failure =
                        normalizeFailure(
                            throwable
                        )

                    mainHandler.post {
                        if (
                            routeGeneration
                                .isCurrent(
                                    generation
                                )
                        ) {
                            navigationSession
                                .fail(
                                    destination =
                                        destination,
                                    message =
                                        failure.message
                                )

                            callback(
                                Result.failure(
                                    failure
                                )
                            )
                        }
                    }
                } finally {
                    synchronized(routeLock) {
                        if (
                            routeGeneration
                                .isCurrent(
                                    generation
                                )
                        ) {
                            routeFuture = null
                        }
                    }
                }
            }

        synchronized(routeLock) {
            if (
                routeGeneration
                    .isCurrent(generation)
            ) {
                routeFuture =
                    submittedFuture
            } else {
                submittedFuture
                    .cancel(true)
            }
        }

        return generation
    }

    fun cancelTextSearch(): Boolean {
        val hadActive =
            textSearchFuture
                ?.isDone == false

        textSearchGeneration.cancel()

        synchronized(textLock) {
            textSearchFuture
                ?.cancel(true)

            textSearchFuture = null
        }

        return hadActive
    }

    fun cancelNearbySearch(): Boolean {
        val active =
            activeNearbyRequest

        val hadActive =
            categorySearchFuture
                ?.isDone == false

        val generation =
            categorySearchGeneration
                .cancel()

        synchronized(categoryLock) {
            categorySearchFuture
                ?.cancel(true)

            categorySearchFuture = null
            activeNearbyRequest = null
        }

        if (hadActive) {
            Log.i(
                TAG_CATEGORY,
                "generation=" +
                    "${active?.generationId ?: generation} " +
                    "category=" +
                    "${active?.category?.name ?: "-"} " +
                    "request=cancelled"
            )
        }

        return hadActive
    }

    fun cancelSearch(): Boolean {
        val textCancelled =
            cancelTextSearch()

        val nearbyCancelled =
            cancelNearbySearch()

        return textCancelled ||
            nearbyCancelled
    }

    fun cancelRoute() {
        routeGeneration.cancel()

        synchronized(routeLock) {
            routeFuture?.cancel(true)
            routeFuture = null
        }
    }

    fun cancelRoute(
        generation: Int
    ): Boolean {
        synchronized(routeLock) {
            if (
                generation ==
                NO_GENERATION ||
                !routeGeneration
                    .isCurrent(generation)
            ) {
                return false
            }

            routeGeneration.cancel()

            routeFuture
                ?.cancel(true)

            routeFuture = null
        }

        navigationSession.cancel()

        return true
    }

    fun close() {
        cancelSearch()
        cancelRoute()

        executor.shutdownNow()

        mainHandler
            .removeCallbacksAndMessages(
                null
            )
    }

    private fun cachedNearbyResult(
        category: NearbyCategory,
        origin: GeoPoint
    ): NearbySearchResult? =
        synchronized(nearbySearchCache) {
            NearbySearchPolicy
                .RADII_METERS
                .asReversed()
                .asSequence()
                .map { radius ->
                    radius to
                        nearbySearchCache[
                            nearbyCacheKey(
                                category =
                                    category,
                                origin =
                                    origin,
                                radiusMeters =
                                    radius
                            )
                        ]
                }
                .firstOrNull {
                    (_, places) ->
                    places != null
                }
                ?.let {
                    (radius, places) ->
                    NearbySearchResult(
                        places =
                            places.orEmpty(),
                        finalRadiusMeters =
                            radius,
                        fromCache = true
                    )
                }
        }

    private fun cacheNearbyResult(
        category: NearbyCategory,
        origin: GeoPoint,
        result: NearbySearchResult
    ) {
        if (
            !NearbySearchPolicy
                .shouldCache(result)
        ) {
            return
        }

        synchronized(nearbySearchCache) {
            nearbySearchCache[
                nearbyCacheKey(
                    category =
                        category,
                    origin =
                        origin,
                    radiusMeters =
                        result
                            .finalRadiusMeters
                )
            ] = result.places
        }
    }

    private fun nearbyCacheKey(
        category: NearbyCategory,
        origin: GeoPoint,
        radiusMeters: Int
    ): NearbyCacheKey =
        NearbyCacheKey(
            category = category,
            latitude =
                String.format(
                    Locale.US,
                    "%.6f",
                    origin.latitude
                ),
            longitude =
                String.format(
                    Locale.US,
                    "%.6f",
                    origin.longitude
                ),
            radiusMeters =
                radiusMeters
        )

    private fun waitForNominatimRateLimit() {
        val now =
            SystemClock.elapsedRealtime()

        val remaining =
            NOMINATIM_REQUEST_INTERVAL_MS -
                (
                    now -
                        lastNominatimRequestTimeMs
                )

        if (remaining > 0) {
            Thread.sleep(remaining)
        }

        lastNominatimRequestTimeMs =
            SystemClock.elapsedRealtime()
    }

    private fun waitForOverpassRateLimit() {
        val now =
            SystemClock.elapsedRealtime()

        val remaining =
            OVERPASS_REQUEST_INTERVAL_MS -
                (
                    now -
                        lastOverpassRequestTimeMs
                )

        if (remaining > 0) {
            Thread.sleep(remaining)
        }

        lastOverpassRequestTimeMs =
            SystemClock.elapsedRealtime()
    }

    private fun ensureCurrentCategoryRequest(
        generation: Int
    ) {
        if (
            Thread.currentThread()
                .isInterrupted ||
            !categorySearchGeneration
                .isCurrent(generation)
        ) {
            throw NavigationDataException(
                kind =
                    FailureKind.CANCELLED,
                message =
                    "The nearby search was cancelled."
            )
        }
    }

    private fun deliverTextSearch(
        generation: Int,
        result: Result<List<Place>>,
        callback:
            (Result<List<Place>>) -> Unit
    ) {
        mainHandler.post {
            if (
                textSearchGeneration
                    .isCurrent(generation)
            ) {
                callback(result)
            } else {
                Log.i(
                    TAG_REPOSITORY,
                    "text generation=$generation " +
                        "stale=ignored"
                )
            }
        }
    }

    private fun deliverNearbyProgress(
        generation: Int,
        progress: NearbySearchProgress,
        callback:
            (NearbySearchProgress) -> Unit
    ) {
        mainHandler.post {
            if (
                categorySearchGeneration
                    .isCurrent(generation)
            ) {
                callback(progress)
            } else {
                Log.i(
                    TAG_CATEGORY,
                    "generation=$generation " +
                        "progress=stale_ignored"
                )
            }
        }
    }

    private fun finishNearbySearch(
        generation: Int,
        result:
            Result<NearbySearchResult>,
        callback:
            (
                Result<NearbySearchResult>
            ) -> Unit
    ) {
        mainHandler.post {
            if (
                categorySearchGeneration
                    .isCurrent(generation)
            ) {
                callback(result)
            } else {
                Log.i(
                    TAG_CATEGORY,
                    "generation=$generation " +
                        "result=stale_ignored"
                )
            }
        }
    }

    private fun logNearbyOutcome(
        generation: Int,
        category: NearbyCategory,
        outcome:
            Result<NearbySearchResult>
    ) {
        outcome.fold(
            onSuccess = { result ->
                val finalState =
                    when {
                        result.places
                            .isEmpty() ->
                            "NO_RESULTS"

                        result
                            .widerSearchUnavailable ->
                            "RESULTS_PARTIAL"

                        else ->
                            "RESULTS"
                    }

                Log.i(
                    TAG_CATEGORY,
                    "generation=$generation " +
                        "category=${category.name} " +
                        "radius=" +
                        "${result.finalRadiusMeters} " +
                        "results=" +
                        "${result.places.size} " +
                        "final=$finalState"
                )
            },
            onFailure = { throwable ->
                val failure =
                    throwable as?
                        NavigationDataException

                Log.w(
                    TAG_CATEGORY,
                    "generation=$generation " +
                        "category=${category.name} " +
                        "failure=" +
                        "${failure?.kind ?: "UNKNOWN"} " +
                        "http=" +
                        "${failure?.httpStatusCode ?: "-"} " +
                        "final=" +
                        if (
                            failure?.kind ==
                            FailureKind.CANCELLED
                        ) {
                            "CANCELLED"
                        } else {
                            "PROVIDER_ERROR"
                        }
                )
            }
        )
    }

    private fun normalizeFailure(
        throwable: Throwable
    ): NavigationDataException =
        when (throwable) {
            is NavigationDataException ->
                throwable

            is InterruptedException ->
                NavigationDataException(
                    kind =
                        FailureKind.CANCELLED,
                    message =
                        "The request was cancelled.",
                    cause = throwable
                )

            else ->
                NavigationDataException(
                    kind =
                        FailureKind.UNKNOWN,
                    message =
                        "The navigation request could not be completed.",
                    cause = throwable
                )
        }

    private data class NearbyCacheKey(
        val category: NearbyCategory,
        val latitude: String,
        val longitude: String,
        val radiusMeters: Int
    )

    private data class ActiveNearbyRequest(
        val generationId: Int,
        val category: NearbyCategory,
        val origin: GeoPoint
    )

    companion object {
        private const val TAG_REPOSITORY =
            "HyperNovaNavigationRepository"

        private const val TAG_CATEGORY =
            "HyperNovaCategorySearch"

        private const val NOMINATIM_REQUEST_INTERVAL_MS =
            1_100L

        private const val OVERPASS_REQUEST_INTERVAL_MS =
            900L

        private const val MAX_CACHED_SEARCHES =
            20

        private const val MAX_CACHED_NEARBY_SEARCHES =
            24

        private const val CACHE_LOAD_FACTOR =
            0.75f

        /*
         * VerifiedDemoPlaces are tied to the ITI /
         * Smart Village demo area.
         *
         * Never expose them as fallback when the
         * vehicle origin is more than 5 km away
         * from the configured demo origin.
         */
        private const val VERIFIED_FALLBACK_ORIGIN_RADIUS_METERS =
            5_000.0

        const val NO_GENERATION = -1

        val DEFAULT_ORIGIN =
            GeoPoint(
                latitude = 30.07112,
                longitude = 31.02075
            )
    }
}
