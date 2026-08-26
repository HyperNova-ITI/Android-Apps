package com.hypernova.launcher.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hypernova.launcher.core.navigation.NavigationPreviewPoint
import com.hypernova.launcher.core.navigation.sampleForPreview
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loads one non-interactive Google Static Maps image for the HOME widget.
 *
 * This deliberately owns no WebView, MapView or continuously rendering surface. A request is made
 * only when the authoritative route identity/geometry changes. The existing Canvas renderer stays
 * visible while a request is pending or unavailable.
 */
class LauncherStaticMapController(
    private val apiKey: String,
    private val target: NavigationRoutePreviewView,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "launcher-static-map").apply { isDaemon = true }
    }
    private val generation = AtomicInteger(0)
    private var lastRequestIdentity: StaticMapRequestIdentity? = null
    private var destroyed = false

    fun setNavigation(
        routeId: String,
        routeVersion: Long,
        routePoints: List<NavigationPreviewPoint>,
    ) {
        if (destroyed || apiKey.isBlank()) return

        // Sample, never truncate. take() kept only the opening stretch of a long route and the
        // auto-fitted Static Maps viewport then framed that prefix as if it were the whole trip.
        val boundedPoints = routePoints.sampleForPreview(MAX_ROUTE_POINTS)
        val identity =
            StaticMapRequestIdentity(
                routeId = routeId,
                routeVersion = routeVersion,
                geometryHash = boundedPoints.hashCode(),
                hasRoute = boundedPoints.size >= 2,
            )
        if (identity == lastRequestIdentity) return
        lastRequestIdentity = identity

        val requestGeneration = generation.incrementAndGet()
        target.clearBaseMap()
        executor.execute {
            val bitmap =
                runCatching {
                    download(
                        LauncherStaticMapRequest.build(
                            apiKey = apiKey,
                            routePoints = boundedPoints,
                        ),
                    )
                }.onFailure { error ->
                    Log.w(TAG, "Static Navigation map unavailable; retaining Canvas fallback", error)
                }.getOrNull()

            mainHandler.post {
                if (destroyed || requestGeneration != generation.get()) {
                    bitmap?.recycle()
                    return@post
                }
                if (bitmap != null) {
                    target.setBaseMap(bitmap, includesRoute = identity.hasRoute)
                }
            }
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        generation.incrementAndGet()
        executor.shutdownNow()
        target.clearBaseMap()
    }

    private fun download(requestUrl: String): Bitmap? {
        val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "image/png,image/*")
        }
        return try {
            val responseCode = connection.responseCode
            val contentType = connection.contentType.orEmpty()
            if (responseCode != HttpURLConnection.HTTP_OK || !contentType.startsWith("image/")) {
                Log.w(TAG, "Static Navigation map request failed: HTTP $responseCode")
                null
            } else {
                connection.inputStream.use(BitmapFactory::decodeStream)
            }
        } finally {
            connection.disconnect()
        }
    }

    private data class StaticMapRequestIdentity(
        val routeId: String,
        val routeVersion: Long,
        val geometryHash: Int,
        val hasRoute: Boolean,
    )

    private companion object {
        private const val TAG = "LauncherStaticMap"
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 7_000
        private const val MAX_ROUTE_POINTS = 96
    }
}

/** Pure request builder kept testable without Android framework networking classes. */
internal object LauncherStaticMapRequest {
    private const val ENDPOINT = "https://maps.googleapis.com/maps/api/staticmap"
    private const val MAP_ID = "20d0a8fe56e67ae4e0d3323d"
    private const val ITI_LATITUDE = 30.07112
    private const val ITI_LONGITUDE = 31.02075

    fun build(
        apiKey: String,
        routePoints: List<NavigationPreviewPoint>,
    ): String {
        val parameters = mutableListOf(
            "size" to "640x480",
            "scale" to "2",
            "format" to "png32",
            "language" to "en",
            "region" to "eg",
            "map_id" to MAP_ID,
        )

        if (routePoints.size >= 2) {
            parameters +=
                "path" to
                    "color:0x12C8D7FF|weight:6|enc:${encodePolyline(routePoints)}"
            parameters +=
                "markers" to marker("0x00AFC6", routePoints.first())
            parameters +=
                "markers" to marker("0xE64553", routePoints.last())
        } else {
            parameters += "center" to "$ITI_LATITUDE,$ITI_LONGITUDE"
            parameters += "zoom" to "15"
            parameters +=
                "markers" to
                    "size:mid|color:0x00AFC6|$ITI_LATITUDE,$ITI_LONGITUDE"
        }
        parameters += "key" to apiKey

        return buildString {
            append(ENDPOINT)
            append('?')
            parameters.forEachIndexed { index, (name, value) ->
                if (index > 0) append('&')
                append(encode(name))
                append('=')
                append(encode(value))
            }
        }
    }

    internal fun encodePolyline(points: List<NavigationPreviewPoint>): String {
        var previousLatitude = 0L
        var previousLongitude = 0L
        return buildString {
            points.forEach { point ->
                val latitude = Math.round(point.latitude * 100_000.0)
                val longitude = Math.round(point.longitude * 100_000.0)
                appendEncodedCoordinate(latitude - previousLatitude)
                appendEncodedCoordinate(longitude - previousLongitude)
                previousLatitude = latitude
                previousLongitude = longitude
            }
        }
    }

    private fun StringBuilder.appendEncodedCoordinate(delta: Long) {
        var value = if (delta < 0) (delta shl 1).inv() else delta shl 1
        while (value >= 0x20L) {
            append(((0x20L or (value and 0x1fL)) + 63L).toInt().toChar())
            value = value shr 5
        }
        append((value + 63L).toInt().toChar())
    }

    private fun marker(color: String, point: NavigationPreviewPoint): String =
        "size:mid|color:$color|${point.latitude},${point.longitude}"

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
