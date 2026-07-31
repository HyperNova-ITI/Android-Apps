package com.hypernova.navigation.domain.repository

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.hypernova.navigation.data.nominatim.NominatimClient
import com.hypernova.navigation.data.osrm.OsrmClient
import com.hypernova.navigation.data.overpass.OverpassClient
import com.hypernova.navigation.domain.model.FailureKind
import com.hypernova.navigation.domain.model.GeoPoint
import com.hypernova.navigation.domain.model.NavigationDataException
import com.hypernova.navigation.domain.model.NearbyCategory
import com.hypernova.navigation.domain.model.Place
import com.hypernova.navigation.domain.model.RoutePlan
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class NavigationRepository(
    private val nominatimClient: NominatimClient = NominatimClient(),
    private val overpassClient: OverpassClient = OverpassClient(),
    private val osrmClient: OsrmClient = OsrmClient()
) {
    private val executor: ExecutorService =
        Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val textSearchGeneration = RequestGenerationGate()
    private val categorySearchGeneration = RequestGenerationGate()
    private val routeGeneration = RequestGenerationGate()
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
                eldest: MutableMap.MutableEntry<String, List<Place>>?
            ): Boolean = size > MAX_CACHED_SEARCHES
        }

    private val nearbySearchCache =
        object : LinkedHashMap<NearbyCacheKey, List<Place>>(
            MAX_CACHED_NEARBY_SEARCHES,
            CACHE_LOAD_FACTOR,
            true
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<
                    NearbyCacheKey,
                    List<Place>
                >?
            ): Boolean = size > MAX_CACHED_NEARBY_SEARCHES
        }

    fun searchTextPlace(
        query: String,
        callback: (Result<List<Place>>) -> Unit
    ) {
        cancelNearbySearch()
        val generation: Int
        synchronized(textLock) {
            generation = textSearchGeneration.next()
            textSearchFuture?.cancel(true)
            textSearchFuture = null
        }
        val cacheKey =
            query.trim().lowercase(Locale.ROOT)

        synchronized(textSearchCache) {
            textSearchCache[cacheKey]
        }?.let { cached ->
            Log.i(
                TAG_REPOSITORY,
                "text generation=$generation cache=hit " +
                    "results=${cached.size}"
            )
            deliverTextSearch(
                generation,
                Result.success(cached),
                callback
            )
            return
        }

        Log.i(
            TAG_REPOSITORY,
            "text generation=$generation cache=miss"
        )
        textSearchFuture = executor.submit {
            try {
                waitForNominatimRateLimit()
                val places = nominatimClient.search(query)

                synchronized(textSearchCache) {
                    textSearchCache[cacheKey] = places
                }

                deliverTextSearch(
                    generation,
                    Result.success(places),
                    callback
                )
            } catch (throwable: Throwable) {
                deliverTextSearch(
                    generation,
                    Result.failure(normalizeFailure(throwable)),
                    callback
                )
            } finally {
                synchronized(textLock) {
                    if (textSearchGeneration.isCurrent(generation)) {
                        textSearchFuture = null
                    }
                }
            }
        }
    }

    fun isNearbySearchRunning(
        category: NearbyCategory,
        origin: GeoPoint
    ): Boolean {
        val active = activeNearbyRequest ?: return false
        return active.category == category &&
            active.origin == origin &&
            categorySearchFuture?.isDone == false
    }

    /**
     * Returns the request generation. If an identical request is already
     * active, the existing generation is returned and no second request is
     * created.
     */
    fun searchNearbyCategory(
        category: NearbyCategory,
        origin: GeoPoint,
        onProgress: (NearbySearchProgress) -> Unit,
        callback: (Result<NearbySearchResult>) -> Unit
    ): Int {
        cancelTextSearch()

        val generation: Int
        synchronized(categoryLock) {
            val active = activeNearbyRequest
            if (
                active != null &&
                active.category == category &&
                active.origin == origin &&
                categorySearchFuture?.isDone == false
            ) {
                Log.i(
                    TAG_CATEGORY,
                    "generation=${active.generationId} " +
                        "category=${category.name} duplicate=ignored"
                )
                return active.generationId
            }

            generation = categorySearchGeneration.next()
            categorySearchFuture?.cancel(true)
            categorySearchFuture = null
            activeNearbyRequest =
                ActiveNearbyRequest(
                    generationId = generation,
                    category = category,
                    origin = origin
                )
        }

        Log.i(
            TAG_CATEGORY,
            "generation=$generation category=${category.name} " +
                "request=start"
        )

        val cached = cachedNearbyResult(category, origin)
        if (
            cached != null &&
            NearbySearchPolicy.shouldUseCachedResultAsTerminal(
                resultCount = cached.places.size,
                radiusMeters = cached.finalRadiusMeters
            )
        ) {
            Log.i(
                TAG_CATEGORY,
                "generation=$generation category=${category.name} " +
                    "radius=${cached.finalRadiusMeters} cache=hit " +
                    "terminal=true results=${cached.places.size}"
            )
            deliverNearbyProgress(
                generation = generation,
                progress =
                    NearbySearchProgress(
                        category = category,
                        radiusMeters = cached.finalRadiusMeters,
                        isExpansion =
                            cached.finalRadiusMeters >
                                NearbySearchPolicy.RADII_METERS.first(),
                        generationId = generation
                    ),
                callback = onProgress
            )
            finishNearbySearch(
                generation = generation,
                result =
                    Result.success(
                        cached.copy(fromCache = true)
                    ),
                callback = callback
            )
            synchronized(categoryLock) {
                if (
                    categorySearchGeneration.isCurrent(generation)
                ) {
                    activeNearbyRequest = null
                }
            }
            return generation
        }

        categorySearchFuture = executor.submit {
            val accumulator = NearbyResultAccumulator()
            var terminalResult: Result<NearbySearchResult>? = null

            try {
                val startRadiusIndex =
                    if (cached == null) {
                        0
                    } else {
                        accumulator.recordSuccess(
                            places = cached.places,
                            radiusMeters = cached.finalRadiusMeters
                        )
                        Log.i(
                            TAG_CATEGORY,
                            "generation=$generation " +
                                "category=${category.name} " +
                                "radius=${cached.finalRadiusMeters} " +
                                "cache=hit terminal=false " +
                                "results=${cached.places.size}"
                        )
                        (
                            NearbySearchPolicy.RADII_METERS
                                .indexOf(cached.finalRadiusMeters) + 1
                        ).coerceAtLeast(0)
                    }

                for (
                    radiusIndex in
                    startRadiusIndex until
                        NearbySearchPolicy.RADII_METERS.size
                ) {
                    ensureCurrentCategoryRequest(generation)
                    val radiusMeters =
                        NearbySearchPolicy.RADII_METERS[radiusIndex]

                    deliverNearbyProgress(
                        generation = generation,
                        progress =
                            NearbySearchProgress(
                                category = category,
                                radiusMeters = radiusMeters,
                                isExpansion = radiusIndex > 0,
                                generationId = generation
                            ),
                        callback = onProgress
                    )

                    waitForOverpassRateLimit()
                    Log.i(
                        TAG_CATEGORY,
                        "generation=$generation " +
                            "category=${category.name} " +
                            "radius=$radiusMeters cache=miss"
                    )

                    val places =
                        try {
                            overpassClient.search(
                                category = category,
                                origin = origin,
                                radiusMeters = radiusMeters
                            )
                        } catch (failure: NavigationDataException) {
                            if (
                                failure.kind ==
                                FailureKind.CANCELLED
                            ) {
                                throw failure
                            }
                            terminalResult =
                                accumulator
                                    .completeAfterFailure(failure)
                            break
                        }

                    accumulator.recordSuccess(
                        places = places,
                        radiusMeters = radiusMeters
                    )
                    val current = accumulator.complete()
                    if (NearbySearchPolicy.shouldCache(current)) {
                        cacheNearbyResult(
                            category = category,
                            origin = origin,
                            result = current
                        )
                    }

                    if (
                        NearbySearchPolicy.shouldStop(
                            usefulResultCount = places.size,
                            radiusIndex = radiusIndex
                        )
                    ) {
                        break
                    }
                }

                val outcome =
                    terminalResult
                        ?: Result.success(accumulator.complete())
                logNearbyOutcome(
                    generation = generation,
                    category = category,
                    outcome = outcome
                )
                finishNearbySearch(
                    generation = generation,
                    result = outcome,
                    callback = callback
                )
            } catch (throwable: Throwable) {
                val failure = normalizeFailure(throwable)
                val outcome =
                    if (failure.kind == FailureKind.CANCELLED) {
                        Result.failure(failure)
                    } else {
                        accumulator.completeAfterFailure(failure)
                    }
                logNearbyOutcome(
                    generation = generation,
                    category = category,
                    outcome = outcome
                )
                finishNearbySearch(
                    generation = generation,
                    result = outcome,
                    callback = callback
                )
            } finally {
                synchronized(categoryLock) {
                    if (
                        categorySearchGeneration
                            .isCurrent(generation)
                    ) {
                        categorySearchFuture = null
                        activeNearbyRequest = null
                    }
                }
            }
        }

        return generation
    }

    fun calculateRoute(
        origin: GeoPoint,
        destination: Place,
        callback: (Result<RoutePlan>) -> Unit
    ) {
        val generation: Int
        synchronized(routeLock) {
            generation = routeGeneration.next()
            routeFuture?.cancel(true)
        }

        routeFuture = executor.submit {
            try {
                val route = osrmClient.route(origin, destination)
                mainHandler.post {
                    if (routeGeneration.isCurrent(generation)) {
                        callback(Result.success(route))
                    }
                }
            } catch (throwable: Throwable) {
                val failure = normalizeFailure(throwable)
                mainHandler.post {
                    if (routeGeneration.isCurrent(generation)) {
                        callback(Result.failure(failure))
                    }
                }
            } finally {
                synchronized(routeLock) {
                    if (routeGeneration.isCurrent(generation)) {
                        routeFuture = null
                    }
                }
            }
        }
    }

    fun cancelTextSearch(): Boolean {
        val hadActive = textSearchFuture?.isDone == false
        textSearchGeneration.cancel()
        synchronized(textLock) {
            textSearchFuture?.cancel(true)
            textSearchFuture = null
        }
        return hadActive
    }

    fun cancelNearbySearch(): Boolean {
        val active = activeNearbyRequest
        val hadActive = categorySearchFuture?.isDone == false
        val generation = categorySearchGeneration.cancel()
        synchronized(categoryLock) {
            categorySearchFuture?.cancel(true)
            categorySearchFuture = null
            activeNearbyRequest = null
        }
        if (hadActive) {
            Log.i(
                TAG_CATEGORY,
                "generation=${active?.generationId ?: generation} " +
                    "category=${active?.category?.name ?: "-"} " +
                    "request=cancelled"
            )
        }
        return hadActive
    }

    fun cancelSearch(): Boolean {
        val textCancelled = cancelTextSearch()
        val nearbyCancelled = cancelNearbySearch()
        return textCancelled || nearbyCancelled
    }

    fun cancelRoute() {
        routeGeneration.cancel()
        synchronized(routeLock) {
            routeFuture?.cancel(true)
            routeFuture = null
        }
    }

    fun close() {
        cancelSearch()
        cancelRoute()
        executor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun cachedNearbyResult(
        category: NearbyCategory,
        origin: GeoPoint
    ): NearbySearchResult? =
        synchronized(nearbySearchCache) {
            NearbySearchPolicy.RADII_METERS
                .asReversed()
                .asSequence()
                .map { radius ->
                    radius to
                        nearbySearchCache[
                            nearbyCacheKey(
                                category,
                                origin,
                                radius
                            )
                        ]
                }
                .firstOrNull { (_, places) ->
                    places != null
                }
                ?.let { (radius, places) ->
                    NearbySearchResult(
                        places = places.orEmpty(),
                        finalRadiusMeters = radius,
                        fromCache = true
                    )
                }
        }

    private fun cacheNearbyResult(
        category: NearbyCategory,
        origin: GeoPoint,
        result: NearbySearchResult
    ) {
        if (!NearbySearchPolicy.shouldCache(result)) return

        synchronized(nearbySearchCache) {
            nearbySearchCache[
                nearbyCacheKey(
                    category = category,
                    origin = origin,
                    radiusMeters = result.finalRadiusMeters
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
            radiusMeters = radiusMeters
        )

    private fun waitForNominatimRateLimit() {
        val now = SystemClock.elapsedRealtime()
        val remaining =
            NOMINATIM_REQUEST_INTERVAL_MS -
                (now - lastNominatimRequestTimeMs)

        if (remaining > 0) {
            Thread.sleep(remaining)
        }

        lastNominatimRequestTimeMs =
            SystemClock.elapsedRealtime()
    }

    private fun waitForOverpassRateLimit() {
        val now = SystemClock.elapsedRealtime()
        val remaining =
            OVERPASS_REQUEST_INTERVAL_MS -
                (now - lastOverpassRequestTimeMs)

        if (remaining > 0) {
            Thread.sleep(remaining)
        }

        lastOverpassRequestTimeMs =
            SystemClock.elapsedRealtime()
    }

    private fun ensureCurrentCategoryRequest(generation: Int) {
        if (
            Thread.currentThread().isInterrupted ||
            !categorySearchGeneration.isCurrent(generation)
        ) {
            throw NavigationDataException(
                kind = FailureKind.CANCELLED,
                message = "The nearby search was cancelled."
            )
        }
    }

    private fun deliverTextSearch(
        generation: Int,
        result: Result<List<Place>>,
        callback: (Result<List<Place>>) -> Unit
    ) {
        mainHandler.post {
            if (textSearchGeneration.isCurrent(generation)) {
                callback(result)
            } else {
                Log.i(
                    TAG_REPOSITORY,
                    "text generation=$generation stale=ignored"
                )
            }
        }
    }

    private fun deliverNearbyProgress(
        generation: Int,
        progress: NearbySearchProgress,
        callback: (NearbySearchProgress) -> Unit
    ) {
        mainHandler.post {
            if (categorySearchGeneration.isCurrent(generation)) {
                callback(progress)
            } else {
                Log.i(
                    TAG_CATEGORY,
                    "generation=$generation progress=stale_ignored"
                )
            }
        }
    }

    private fun finishNearbySearch(
        generation: Int,
        result: Result<NearbySearchResult>,
        callback: (Result<NearbySearchResult>) -> Unit
    ) {
        mainHandler.post {
            if (categorySearchGeneration.isCurrent(generation)) {
                callback(result)
            } else {
                Log.i(
                    TAG_CATEGORY,
                    "generation=$generation result=stale_ignored"
                )
            }
        }
    }

    private fun logNearbyOutcome(
        generation: Int,
        category: NearbyCategory,
        outcome: Result<NearbySearchResult>
    ) {
        outcome.fold(
            onSuccess = { result ->
                val finalState =
                    when {
                        result.places.isEmpty() -> "NO_RESULTS"
                        result.widerSearchUnavailable ->
                            "RESULTS_PARTIAL"
                        else -> "RESULTS"
                    }
                Log.i(
                    TAG_CATEGORY,
                    "generation=$generation category=${category.name} " +
                        "radius=${result.finalRadiusMeters} " +
                        "results=${result.places.size} " +
                        "final=$finalState"
                )
            },
            onFailure = { throwable ->
                val failure =
                    throwable as? NavigationDataException
                Log.w(
                    TAG_CATEGORY,
                    "generation=$generation category=${category.name} " +
                        "failure=${failure?.kind ?: "UNKNOWN"} " +
                        "http=${failure?.httpStatusCode ?: "-"} " +
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

    private fun normalizeFailure(throwable: Throwable): NavigationDataException =
        when (throwable) {
            is NavigationDataException -> throwable
            is InterruptedException ->
                NavigationDataException(
                    kind = FailureKind.CANCELLED,
                    message = "The request was cancelled.",
                    cause = throwable
                )
            else ->
                NavigationDataException(
                    kind = FailureKind.UNKNOWN,
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
        private const val NOMINATIM_REQUEST_INTERVAL_MS = 1_100L
        private const val OVERPASS_REQUEST_INTERVAL_MS = 900L
        private const val MAX_CACHED_SEARCHES = 20
        private const val MAX_CACHED_NEARBY_SEARCHES = 24
        private const val CACHE_LOAD_FACTOR = 0.75f
    }
}
