package com.hypernova.navigation.data.overpass

import android.util.Log
import com.hypernova.navigation.data.nominatim.NominatimClient
import com.hypernova.navigation.domain.model.FailureKind
import com.hypernova.navigation.domain.model.GeoDistance
import com.hypernova.navigation.domain.model.GeoPoint
import com.hypernova.navigation.domain.model.NavigationDataException
import com.hypernova.navigation.domain.model.NearbyCategory
import com.hypernova.navigation.domain.model.Place
import com.hypernova.navigation.domain.model.PlaceProvider
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class OverpassAttempt(
    val endpoint: String,
    val endpointLabel: String,
    val attemptNumber: Int
)

object OverpassRetryPolicy {
    fun attemptPlan(endpoints: List<String>): List<OverpassAttempt> {
        require(endpoints.isNotEmpty())

        return buildList {
            add(
                OverpassAttempt(
                    endpoint = endpoints.first(),
                    endpointLabel = "primary",
                    attemptNumber = 1
                )
            )
            add(
                OverpassAttempt(
                    endpoint = endpoints.first(),
                    endpointLabel = "primary",
                    attemptNumber = 2
                )
            )
            endpoints.drop(1).forEachIndexed { index, endpoint ->
                add(
                    OverpassAttempt(
                        endpoint = endpoint,
                        endpointLabel =
                            if (index == 0) {
                                "fallback"
                            } else {
                                "fallback-${index + 1}"
                            },
                        attemptNumber = 1
                    )
                )
            }
        }
    }

    fun isRetryableHttpStatus(statusCode: Int): Boolean =
        statusCode == 429 || statusCode in 500..599

    fun shouldRetry(failure: NavigationDataException): Boolean =
        failure.retryable &&
            failure.kind in
            setOf(
                FailureKind.NETWORK,
                FailureKind.TIMEOUT,
                FailureKind.PROVIDER
            )

    fun isRetryableRemark(remark: String): Boolean {
        val normalized = remark.lowercase(Locale.ROOT)
        return normalized.contains("timed out") ||
            normalized.contains("timeout") ||
            normalized.contains("runtime error") ||
            normalized.contains("dispatcher") ||
            normalized.contains("server is probably too busy")
    }
}

internal data class OverpassParseReport(
    val places: List<Place>,
    val providerElementCount: Int
)

class OverpassClient(
    private val endpoints: List<String> = DEFAULT_ENDPOINTS
) {
    init {
        require(endpoints.isNotEmpty()) {
            "At least one Overpass endpoint is required."
        }
    }

    fun search(
        category: NearbyCategory,
        origin: GeoPoint,
        radiusMeters: Int
    ): List<Place> {
        val query =
            buildQuery(
                category = category,
                origin = origin,
                radiusMeters = radiusMeters
            )
        val plan = OverpassRetryPolicy.attemptPlan(endpoints)
        var lastFailure: NavigationDataException? = null

        plan.forEachIndexed { index, attempt ->
            ensureNotCancelled()

            try {
                return execute(
                    attempt = attempt,
                    query = query,
                    category = category,
                    origin = origin,
                    radiusMeters = radiusMeters
                )
            } catch (failure: NavigationDataException) {
                lastFailure = failure
                Log.w(
                    TAG,
                    "category=${category.name} radius=$radiusMeters " +
                        "endpoint=${attempt.endpointLabel} " +
                        "attempt=${attempt.attemptNumber} " +
                        "failure=${failure.kind} " +
                        "http=${failure.httpStatusCode ?: "-"} " +
                        "retryable=${failure.retryable}"
                )

                if (
                    failure.kind == FailureKind.CANCELLED ||
                    !OverpassRetryPolicy.shouldRetry(failure)
                ) {
                    throw failure
                }

                val nextAttempt = plan.getOrNull(index + 1)
                    ?: throw failure
                if (
                    nextAttempt.endpointLabel !=
                    attempt.endpointLabel
                ) {
                    Log.i(
                        TAG,
                        "Using ${nextAttempt.endpointLabel} endpoint " +
                            "after ${attempt.endpointLabel} failure"
                    )
                }
                backoff(index)
            }
        }

        throw lastFailure
            ?: NavigationDataException(
                kind = FailureKind.PROVIDER,
                message =
                    "The map data provider is temporarily unavailable."
            )
    }

    internal fun buildQuery(
        category: NearbyCategory,
        origin: GeoPoint,
        radiusMeters: Int
    ): String =
        """
        [out:json][timeout:$QUERY_TIMEOUT_SECONDS];
        (
          nwr${category.osmSelector}
             (around:$radiusMeters,${coordinate(origin.latitude)},${coordinate(origin.longitude)});
        );
        out center tags $MAX_PROVIDER_ELEMENTS;
        """.trimIndent()

    internal fun parse(
        response: String,
        category: NearbyCategory,
        origin: GeoPoint
    ): List<Place> =
        parseReport(
            response = response,
            category = category,
            origin = origin
        ).places

    internal fun parseReport(
        response: String,
        category: NearbyCategory,
        origin: GeoPoint
    ): OverpassParseReport {
        val root = JSONObject(response)
        val remark = root.optString("remark").trim()

        if (remark.isNotBlank()) {
            throw NavigationDataException(
                kind = FailureKind.PROVIDER,
                message =
                    "The map data provider could not complete this " +
                        "nearby search.",
                retryable =
                    OverpassRetryPolicy.isRetryableRemark(remark)
            )
        }

        val elements =
            root.optJSONArray("elements")
                ?: throw NavigationDataException(
                    kind = FailureKind.MALFORMED_RESPONSE,
                    message =
                        "The nearby place response could not be read."
                )

        val seenElementIds = mutableSetOf<String>()
        val seenSemanticPlaces = mutableSetOf<String>()
        val parsed = mutableListOf<Place>()

        for (index in 0 until elements.length()) {
            val element =
                elements.optJSONObject(index) ?: continue
            val osmType =
                element.optString("type")
                    .trim()
                    .lowercase(Locale.ROOT)
            val osmId =
                element.optLong("id", 0L)
                    .takeIf { it > 0L } ?: continue
            val point =
                coordinates(element, osmType) ?: continue
            val tags =
                element.optJSONObject("tags") ?: JSONObject()
            val brand = tags.optString("brand").trim()
            val operator = tags.optString("operator").trim()
            val taggedName = tags.optString("name").trim()
            val subcategory =
                if (category == NearbyCategory.SHOPPING) {
                    tags.optString("shop").trim()
                } else {
                    tags.optString("amenity").trim()
                }
            val primaryName =
                taggedName
                    .ifBlank { brand }
                    .ifBlank { operator }
                    .ifBlank {
                        category.fallbackName(subcategory)
                    }
            val address = buildAddress(tags)
            val stableId = "overpass:$osmType:$osmId"
            val semanticKey =
                if (
                    taggedName.isBlank() &&
                    brand.isBlank() &&
                    operator.isBlank()
                ) {
                    ""
                } else {
                    String.format(
                        Locale.US,
                        "%s|%.5f|%.5f",
                        primaryName.lowercase(Locale.ROOT),
                        point.latitude,
                        point.longitude
                    )
                }

            if (!seenElementIds.add(stableId)) continue
            if (
                semanticKey.isNotBlank() &&
                !seenSemanticPlaces.add(semanticKey)
            ) {
                continue
            }

            parsed +=
                Place(
                    displayName =
                        listOf(primaryName, address)
                            .filter { it.isNotBlank() }
                            .joinToString(", "),
                    latitude = point.latitude,
                    longitude = point.longitude,
                    category = category.displayName,
                    type = subcategory,
                    provider = PlaceProvider.OVERPASS,
                    providerId = stableId,
                    osmType = osmType,
                    osmId = osmId,
                    primaryName = primaryName,
                    formattedAddress = address,
                    brand = brand,
                    operator = operator,
                    subcategory = subcategory,
                    phone =
                        tags.optString("phone").trim()
                            .ifBlank {
                                tags.optString("contact:phone").trim()
                            },
                    website =
                        tags.optString("website").trim()
                            .ifBlank {
                                tags.optString("contact:website").trim()
                            },
                    openingHours =
                        tags.optString("opening_hours").trim(),
                    straightLineDistanceMeters =
                        GeoDistance.meters(origin, point)
                )
        }

        return OverpassParseReport(
            places =
                parsed.sortedBy {
                    it.straightLineDistanceMeters
                        ?: Double.POSITIVE_INFINITY
                },
            providerElementCount = elements.length()
        )
    }

    internal fun buildAddress(tags: JSONObject): String {
        val houseNumber =
            tags.optString("addr:housenumber").trim()
        val street =
            tags.optString("addr:street").trim()
                .ifBlank {
                    tags.optString("addr:place").trim()
                }
        val firstLine =
            listOf(houseNumber, street)
                .filter { it.isNotBlank() }
                .joinToString(" ")

        return listOf(
            firstLine,
            tags.optString("addr:suburb").trim(),
            tags.optString("addr:city").trim(),
            tags.optString("addr:state").trim()
        )
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")
    }

    private fun execute(
        attempt: OverpassAttempt,
        query: String,
        category: NearbyCategory,
        origin: GeoPoint,
        radiusMeters: Int
    ): List<Place> {
        val startedNanos = System.nanoTime()
        val connection =
            URL(attempt.endpoint)
                .openConnection() as HttpURLConnection
        val body =
            "data=" +
                URLEncoder.encode(
                    query,
                    StandardCharsets.UTF_8.name()
                )
        val bodyBytes =
            body.toByteArray(StandardCharsets.UTF_8)

        Log.i(
            TAG,
            "request start category=${category.name} " +
                "radius=$radiusMeters " +
                "endpoint=${attempt.endpointLabel} " +
                "attempt=${attempt.attemptNumber}"
        )

        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setFixedLengthStreamingMode(bodyBytes.size)
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8"
            )
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "User-Agent",
                NominatimClient.APP_USER_AGENT
            )

            connection.outputStream.use {
                it.write(bodyBytes)
            }

            ensureNotCancelled()
            val responseCode = connection.responseCode
            val elapsedMs = elapsedMillis(startedNanos)
            Log.i(
                TAG,
                "response category=${category.name} " +
                    "radius=$radiusMeters " +
                    "endpoint=${attempt.endpointLabel} " +
                    "attempt=${attempt.attemptNumber} " +
                    "http=$responseCode elapsedMs=$elapsedMs"
            )

            if (responseCode !in 200..299) {
                throw NavigationDataException(
                    kind = FailureKind.PROVIDER,
                    message =
                        if (
                            OverpassRetryPolicy
                                .isRetryableHttpStatus(responseCode)
                        ) {
                            "The map data provider is temporarily " +
                                "unavailable (HTTP $responseCode)."
                        } else {
                            "The map data provider rejected the nearby " +
                                "search (HTTP $responseCode)."
                        },
                    httpStatusCode = responseCode,
                    retryable =
                        OverpassRetryPolicy
                            .isRetryableHttpStatus(responseCode),
                    endpointLabel = attempt.endpointLabel
                )
            }

            val response =
                connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

            ensureNotCancelled()
            val report =
                parseReport(response, category, origin)
            Log.i(
                TAG,
                "parsed category=${category.name} " +
                    "radius=$radiusMeters " +
                    "endpoint=${attempt.endpointLabel} " +
                    "elements=${report.providerElementCount} " +
                    "valid=${report.places.size}"
            )
            report.places
        } catch (exception: SocketTimeoutException) {
            throw NavigationDataException(
                kind = FailureKind.TIMEOUT,
                message =
                    "The map data provider timed out. Please try again.",
                cause = exception,
                retryable = true,
                endpointLabel = attempt.endpointLabel
            )
        } catch (exception: NavigationDataException) {
            if (exception.endpointLabel != null) {
                throw exception
            }
            throw NavigationDataException(
                kind = exception.kind,
                message = exception.message,
                cause = exception,
                httpStatusCode = exception.httpStatusCode,
                retryable = exception.retryable,
                endpointLabel = attempt.endpointLabel
            )
        } catch (exception: IOException) {
            ensureNotCancelled()
            throw NavigationDataException(
                kind = FailureKind.NETWORK,
                message =
                    "The map data provider could not be reached. " +
                        "Please try again.",
                cause = exception,
                retryable = true,
                endpointLabel = attempt.endpointLabel
            )
        } catch (exception: Exception) {
            throw NavigationDataException(
                kind = FailureKind.MALFORMED_RESPONSE,
                message =
                    "The nearby OpenStreetMap response could not be read.",
                cause = exception,
                endpointLabel = attempt.endpointLabel
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun backoff(completedAttemptIndex: Int) {
        try {
            Thread.sleep(
                if (completedAttemptIndex == 0) {
                    PRIMARY_RETRY_BACKOFF_MS
                } else {
                    FALLBACK_BACKOFF_MS
                }
            )
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw NavigationDataException(
                kind = FailureKind.CANCELLED,
                message = "The nearby place request was cancelled.",
                cause = exception
            )
        }
    }

    private fun coordinates(
        element: JSONObject,
        osmType: String
    ): GeoPoint? {
        val location =
            if (osmType == "node") {
                element
            } else {
                element.optJSONObject("center") ?: return null
            }
        val latitude =
            location.optDouble("lat", Double.NaN)
        val longitude =
            location.optDouble("lon", Double.NaN)

        if (
            !latitude.isFinite() ||
            !longitude.isFinite() ||
            latitude !in -90.0..90.0 ||
            longitude !in -180.0..180.0
        ) {
            return null
        }

        return GeoPoint(latitude, longitude)
    }

    private fun ensureNotCancelled() {
        if (Thread.currentThread().isInterrupted) {
            throw NavigationDataException(
                kind = FailureKind.CANCELLED,
                message = "The nearby place request was cancelled."
            )
        }
    }

    private fun coordinate(value: Double): String =
        String.format(Locale.US, "%.6f", value)

    private fun elapsedMillis(startedNanos: Long): Long =
        (System.nanoTime() - startedNanos) / NANOS_PER_MILLISECOND

    companion object {
        val DEFAULT_ENDPOINTS =
            listOf(
                "https://overpass-api.de/api/interpreter",
                "https://overpass.private.coffee/api/interpreter"
            )

        private const val TAG = "HyperNovaOverpass"
        private const val QUERY_TIMEOUT_SECONDS = 25
        private const val CONNECT_TIMEOUT_MS = 7_000
        private const val READ_TIMEOUT_MS = 16_000
        private const val PRIMARY_RETRY_BACKOFF_MS = 450L
        private const val FALLBACK_BACKOFF_MS = 800L
        private const val MAX_PROVIDER_ELEMENTS = 100
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
