package com.hypernova.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import androidx.core.content.ContextCompat
import com.hypernova.launcher.R
import com.hypernova.launcher.core.navigation.NavigationPreviewPoint
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Read-only MapLibre renderer for Navigation-owned route and progress data. */
class LauncherNavigationMapController(
    private val context: Context,
    private val mapView: MapView,
) {
    private var map: MapLibreMap? = null
    private var styleReady = false
    private var fallbackAttempted = false
    private var destroyed = false
    private var routeId = ""
    private var routeVersion = 0L
    private var routePoints: List<NavigationPreviewPoint> = emptyList()
    private var currentPosition: NavigationPreviewPoint? = null
    private var currentBearingDegrees: Float? = null
    private var renderedPosition: NavigationPreviewPoint? = null
    private var renderedBearingDegrees: Float? = null
    private var pendingRouteFit = false
    private var lastFollowCameraUpdateMillis = 0L
    private var markerAnimator: ValueAnimator? = null
    private var availabilityChanged: ((Boolean) -> Unit)? = null
    private var currentNightMode = false

    /*
     * HOME current-position source.
     *
     * Navigation route geometry still belongs to HyperNova Navigation,
     * but the Launcher marker comes from Android's real LocationManager.
     *
     * This deliberately prevents the Launcher HOME widget from drawing
     * Navigation's simulated route-following vehicle position.
     */
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var liveLocationPosition: NavigationPreviewPoint? = null
    private var liveLocationBearingDegrees: Float? = null
    private var locationUpdatesStarted = false

    private val locationListener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                handleLiveLocation(location)
            }

            @Suppress("DEPRECATION")
            override fun onStatusChanged(
                provider: String?,
                status: Int,
                extras: Bundle?,
            ) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }

    private val failureListener =
        MapView.OnDidFailLoadingMapListener {
            if (destroyed || styleReady) return@OnDidFailLoadingMapListener
            if (!fallbackAttempted) {
                fallbackAttempted = true

                /*
                 * Remote map failed. Keep HOME usable and visible instead
                 * of leaving the Navigation widget black.
                 */
                map?.let { readyMap ->
                    loadIdleStyle(readyMap)
                }
            } else {
                availabilityChanged?.invoke(false)
            }
        }

    init {
        mapView.addOnDidFailLoadingMapListener(failureListener)
    }

    fun initialize(
        isNightMode: Boolean,
        onAvailabilityChanged: (Boolean) -> Unit,
    ) {
        currentNightMode = isNightMode
        availabilityChanged = onAvailabilityChanged
        onAvailabilityChanged(false)

        /*
         * HOME widget uses the configured HyperNova demo origin
         * when no Navigation route is active.
         *
         * No GPS / fused provider is required here.
         */
        mapView.getMapAsync { readyMap ->
            if (destroyed) return@getMapAsync
            map = readyMap
            readyMap.uiSettings.apply {
                isCompassEnabled = false
                isScrollGesturesEnabled = false
                isZoomGesturesEnabled = false
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isDoubleTapGesturesEnabled = false
                isQuickZoomGesturesEnabled = false
                isLogoEnabled = false
                isAttributionEnabled = false
                logoGravity = Gravity.TOP or Gravity.END
                attributionGravity = Gravity.TOP or Gravity.END
            }
            /*
             * HOME initially has no active route.
             *
             * Do NOT wait for a remote style before showing the fixed
             * HyperNova ITI position. Load a bundled in-memory MapLibre
             * style immediately.
             */
            /*
             * Always start with the real themed map.
             *
             * Light -> OpenFreeMap Positron
             * Dark  -> OpenFreeMap Dark
             *
             * The local zero-network style remains failure fallback only.
             */
            loadStyle(
                readyMap,
                if (isNightMode) DARK_STYLE_URL else LIGHT_STYLE_URL,
            )
        }
    }

    fun setNavigation(
        newRouteId: String,
        newRouteVersion: Long,
        newRoutePoints: List<NavigationPreviewPoint>,
        newCurrentPosition: NavigationPreviewPoint?,
        newBearingDegrees: Float?,
    ) {
        val geometryChanged =
            newRouteId != routeId ||
                newRoutePoints != routePoints

        val routeChanged =
            newRouteVersion != routeVersion ||
                geometryChanged

        routeId = newRouteId
        routeVersion = newRouteVersion
        routePoints = newRoutePoints.toList()
        currentPosition = newCurrentPosition
        currentBearingDegrees = newBearingDegrees

        if (routeChanged) {
            markerAnimator?.cancel()
            renderedPosition = null
            renderedBearingDegrees = null
            pendingRouteFit = routePoints.size >= 2
        }

        /*
         * A Light/Dark switch is already proven to make a missing HOME route
         * appear because it reconstructs the MapLibre Style and therefore
         * recreates the custom route sources/layers.
         *
         * Do the same reconstruction automatically when new route geometry
         * reaches HOME, but reload the SAME theme.
         */
        if (geometryChanged && routePoints.size >= 2) {
            forceCurrentStyleReload()
            return
        }

        reapplyScene()

        if (routeChanged) {
            return
        }

        /*
         * Do not animate or follow Navigation's route-owned position here.
         * That position is currently simulation-backed.
         *
         * HOME uses Android's live LocationManager position instead.
         * The OSRM route itself remains fully authoritative and unchanged.
         */
        reapplyScene()
    }

    fun clearNavigation() {
        markerAnimator?.cancel()
        routeId = ""
        routeVersion = 0L
        routePoints = emptyList()
        currentPosition = null
        currentBearingDegrees = null
        renderedPosition = null
        renderedBearingDegrees = null
        pendingRouteFit = false

        /*
         * Route ended / no route:
         *
         * Never reload the MapLibre style here. HOME may render this state
         * repeatedly and continuous style reloads prevent the map from ever
         * settling.
         *
         * reapplyScene() keeps the current style and publishes the fixed
         * ITI HOME position immediately.
         */
        reapplyScene()
    }

    /**
     * Re-publish the current route after MapView lifecycle resume.
     *
     * This intentionally does not reload the map style. It restores the
     * existing GeoJSON scene and refits the known route when necessary.
     */
    fun refreshScene() {
        if (destroyed) return
        if (routePoints.size >= 2) {
            pendingRouteFit = true
        }
        reapplyScene()
    }

    fun destroy() {
        destroyed = true
        stopLiveLocationUpdates()
        markerAnimator?.cancel()
        markerAnimator = null
        availabilityChanged = null
        mapView.removeOnDidFailLoadingMapListener(failureListener)
    }

    private fun forceCurrentStyleReload() {
        val readyMap = map ?: return

        fallbackAttempted = false
        loadStyle(
            readyMap,
            if (currentNightMode) DARK_STYLE_URL else LIGHT_STYLE_URL,
        )
    }

    /**
     * Deterministic HOME idle renderer.
     *
     * This style contains no remote sources, tiles, fonts or icons.
     * Therefore the HOME widget can always become drawable even when
     * the online OpenFreeMap style is unavailable.
     *
     * HyperNova route/vehicle sources and layers are added afterwards
     * by addSourcesAndLayers().
     */
    private fun loadIdleStyle(readyMap: MapLibreMap) {
        styleReady = false
        fallbackAttempted = false

        val styleJson =
            if (currentNightMode) {
                IDLE_DARK_STYLE_JSON
            } else {
                IDLE_LIGHT_STYLE_JSON
            }

        readyMap.setStyle(
            Style.Builder().fromJson(styleJson)
        ) { style ->
            if (destroyed) return@setStyle

            styleReady = true

            addSourcesAndLayers(style)

            /*
             * reapplyScene() will select DEFAULT_HOME_POSITION whenever
             * routePoints is empty and centerIdleLocation() will move the
             * camera to the ITI demo location.
             */
            reapplyScene()

            availabilityChanged?.invoke(true)
        }
    }

    private fun loadStyle(readyMap: MapLibreMap, styleUrl: String) {
        styleReady = false
        readyMap.setStyle(styleUrl) { style ->
            if (destroyed) return@setStyle
            styleReady = true
            addSourcesAndLayers(style)
            reapplyScene()
            availabilityChanged?.invoke(true)
        }
    }

    private fun addSourcesAndLayers(style: Style) {
        addSource(style, ROUTE_SOURCE)
        addSource(style, START_SOURCE)
        addSource(style, DESTINATION_SOURCE)
        addSource(style, VEHICLE_SOURCE)
        style.addImage(VEHICLE_IMAGE, LauncherVehicleArrowBitmap.create(context))

        val cyan = ContextCompat.getColor(context, R.color.hypernova_cyan)
        val cyanDark = ContextCompat.getColor(context, R.color.hypernova_cyan_dark)
        val card = ContextCompat.getColor(context, R.color.hypernova_card)
        val destination = ContextCompat.getColor(context, R.color.hypernova_text_primary)

        style.addLayer(
            LineLayer(ROUTE_CASING_LAYER, ROUTE_SOURCE).withProperties(
                lineColor(cyanDark),
                lineWidth(10f),
                lineOpacity(0.9f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                lineColor(cyan),
                lineWidth(6f),
                lineOpacity(1f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            CircleLayer(START_LAYER, START_SOURCE).withProperties(
                circleColor(cyan),
                circleRadius(7f),
                circleStrokeColor(card),
                circleStrokeWidth(3f),
            ),
        )
        style.addLayer(
            CircleLayer(DESTINATION_LAYER, DESTINATION_SOURCE).withProperties(
                circleColor(destination),
                circleRadius(9f),
                circleStrokeColor(cyan),
                circleStrokeWidth(3f),
            ),
        )
        style.addLayer(
            SymbolLayer(VEHICLE_LAYER, VEHICLE_SOURCE).withProperties(
                iconImage(VEHICLE_IMAGE),
                iconSize(1f),
                iconAnchor(Property.ICON_ANCHOR_CENTER),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconRotationAlignment("map"),
            ),
        )
    }

    private fun addSource(style: Style, sourceId: String) {
        if (style.getSource(sourceId) == null) {
            style.addSource(
                GeoJsonSource(
                    sourceId,
                    FeatureCollection.fromFeatures(emptyArray()),
                ),
            )
        }
    }

    private fun reapplyScene() {
        val style = map?.style ?: return
        if (!styleReady) return

        source(style, ROUTE_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(
                routePoints.takeIf { it.size >= 2 }
                    ?.let { points -> arrayOf(lineFeature(points)) }
                    ?: emptyArray(),
            ),
        )
        /*
         * Prefer real Android location for the HOME marker.
         *
         * When a route exists we intentionally do NOT fall back to
         * Navigation's simulated currentPosition.
         */
        /*
         * HOME behavior:
         *
         * No route:
         *   Always show the fixed HyperNova ITI demo origin.
         *
         * Active route:
         *   Keep Navigation's route-owned position.
         */
        val effectivePosition =
            if (routePoints.isEmpty()) {
                DEFAULT_HOME_POSITION
            } else {
                currentPosition
            }

        val effectiveBearing =
            if (routePoints.isEmpty()) {
                0.0f
            } else {
                currentBearingDegrees
            }

        updatePointSource(
            style,
            START_SOURCE,
            routePoints.firstOrNull().takeIf { effectivePosition == null },
        )
        updatePointSource(style, DESTINATION_SOURCE, routePoints.lastOrNull())

        renderedPosition = effectivePosition
        renderedBearingDegrees = effectiveBearing
        updateVehicleSource(style, renderedPosition, renderedBearingDegrees)

        if (pendingRouteFit) {
            pendingRouteFit = false
            fitRoute()
        } else if (routePoints.size < 2 && effectivePosition != null) {
            centerIdleLocation(effectivePosition)
        }
    }

    private fun updateMarkerSmoothly(
        target: NavigationPreviewPoint?,
        targetBearing: Float?,
    ) {
        val style = map?.style ?: return
        if (!styleReady) return
        markerAnimator?.cancel()

        val start = renderedPosition
        val startBearing = renderedBearingDegrees
        if (start == null || target == null) {
            renderedPosition = target
            renderedBearingDegrees = targetBearing
            updatePointSource(style, START_SOURCE, routePoints.firstOrNull().takeIf { target == null })
            updateVehicleSource(style, target, targetBearing)
            return
        }

        markerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MARKER_INTERPOLATION_MILLIS
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction.toDouble()
                val point =
                    NavigationPreviewPoint(
                        start.latitude + (target.latitude - start.latitude) * fraction,
                        start.longitude + (target.longitude - start.longitude) * fraction,
                    )
                val bearing = interpolateBearing(startBearing, targetBearing, animator.animatedFraction)
                renderedPosition = point
                renderedBearingDegrees = bearing
                map?.style?.takeIf { styleReady }?.let { currentStyle ->
                    updatePointSource(currentStyle, START_SOURCE, null)
                    updateVehicleSource(currentStyle, point, bearing)
                }
            }
            start()
        }
    }

    private fun updateVehicleSource(
        style: Style,
        point: NavigationPreviewPoint?,
        bearingDegrees: Float?,
    ) {
        updatePointSource(style, VEHICLE_SOURCE, point)
        style.getLayerAs<SymbolLayer>(VEHICLE_LAYER)?.setProperties(
            iconRotate(bearingDegrees ?: 0f),
        )
    }

    private fun updatePointSource(
        style: Style,
        sourceId: String,
        point: NavigationPreviewPoint?,
    ) {
        val features =
            point?.let { value ->
                arrayOf(
                    Feature.fromGeometry(Point.fromLngLat(value.longitude, value.latitude)),
                )
            } ?: emptyArray()
        source(style, sourceId)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun source(style: Style, sourceId: String): GeoJsonSource? =
        style.getSourceAs(sourceId)

    private fun lineFeature(points: List<NavigationPreviewPoint>): Feature =
        Feature.fromGeometry(
            LineString.fromLngLats(
                points.map { point -> Point.fromLngLat(point.longitude, point.latitude) },
            ),
        )

    private fun fitRoute() {
        val readyMap = map ?: return
        if (routePoints.size < 2 || mapView.width <= 0 || mapView.height <= 0) {
            mapView.post { if (!destroyed && routePoints.size >= 2) fitRoute() }
            return
        }
        val bounds =
            LatLngBounds.Builder()
                .includes(routePoints.map { point -> LatLng(point.latitude, point.longitude) })
                .build()
        readyMap.animateCamera(
            CameraUpdateFactory.newLatLngBounds(
                bounds,
                dp(32),
                dp(42),
                dp(32),
                dp(102),
            ),
            ROUTE_CAMERA_ANIMATION_MILLIS,
        )
    }

    private fun followPosition(position: NavigationPreviewPoint) {
        val readyMap = map ?: return
        if (!styleReady || pendingRouteFit) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFollowCameraUpdateMillis < FOLLOW_CAMERA_INTERVAL_MILLIS) return
        lastFollowCameraUpdateMillis = now
        val camera =
            CameraPosition.Builder()
                .target(LatLng(position.latitude, position.longitude))
                .zoom(FOLLOW_ZOOM)
                .bearing(0.0)
                .tilt(0.0)
                .padding(0.0, mapView.height * FOLLOW_TOP_PADDING_FRACTION, 0.0, 0.0)
                .build()
        readyMap.easeCamera(
            CameraUpdateFactory.newCameraPosition(camera),
            FOLLOW_CAMERA_EASE_MILLIS,
        )
    }

    private fun startLiveLocationUpdates() {
        if (locationUpdatesStarted || destroyed) return

        val fineGranted =
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted =
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            Log.w(TAG, "HOME location unavailable: location permission not granted")
            return
        }

        val provider =
            listOf(
                FUSED_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
            ).firstOrNull { candidate ->
                runCatching {
                    locationManager.getProvider(candidate) != null &&
                        locationManager.isProviderEnabled(candidate)
                }.getOrDefault(false)
            }

        if (provider == null) {
            Log.w(TAG, "HOME location unavailable: no enabled location provider")
            return
        }

        runCatching {
            locationManager.getLastKnownLocation(provider)
        }.getOrNull()?.let(::handleLiveLocation)

        try {
            locationManager.requestLocationUpdates(
                provider,
                LOCATION_UPDATE_INTERVAL_MS,
                LOCATION_UPDATE_MIN_DISTANCE_METERS,
                locationListener,
                Looper.getMainLooper(),
            )

            locationUpdatesStarted = true
            Log.i(TAG, "HOME live location started provider=$provider")
        } catch (security: SecurityException) {
            Log.w(TAG, "HOME live location permission rejected", security)
        } catch (failure: Exception) {
            Log.w(TAG, "HOME live location could not start", failure)
        }
    }

    private fun stopLiveLocationUpdates() {
        if (!locationUpdatesStarted) return

        runCatching {
            locationManager.removeUpdates(locationListener)
        }

        locationUpdatesStarted = false
    }

    private fun handleLiveLocation(location: Location) {
        val latitude = location.latitude
        val longitude = location.longitude

        if (
            !latitude.isFinite() ||
            !longitude.isFinite() ||
            latitude !in -90.0..90.0 ||
            longitude !in -180.0..180.0
        ) {
            return
        }

        val point =
            NavigationPreviewPoint(
                latitude = latitude,
                longitude = longitude,
            )

        liveLocationPosition = point
        liveLocationBearingDegrees =
            location.bearing
                .takeIf { location.hasBearing() && it.isFinite() }

        mapView.post {
            if (destroyed || !styleReady) return@post

            val style = map?.style ?: return@post

            renderedPosition = point
            renderedBearingDegrees = liveLocationBearingDegrees

            updatePointSource(
                style,
                START_SOURCE,
                routePoints.firstOrNull().takeIf { point == null },
            )

            updateVehicleSource(
                style,
                point,
                liveLocationBearingDegrees,
            )

            if (routePoints.size < 2) {
                centerIdleLocation(point)
            }
        }
    }

    private fun centerIdleLocation(position: NavigationPreviewPoint) {
        val readyMap = map ?: return
        if (!styleReady || mapView.width <= 0 || mapView.height <= 0) {
            mapView.post {
                if (!destroyed && routePoints.size < 2) {
                    liveLocationPosition?.let(::centerIdleLocation)
                }
            }
            return
        }

        val camera =
            CameraPosition.Builder()
                .target(
                    LatLng(
                        position.latitude,
                        position.longitude,
                    ),
                )
                .zoom(IDLE_LOCATION_ZOOM)
                .bearing(0.0)
                .tilt(0.0)
                .build()

        readyMap.easeCamera(
            CameraUpdateFactory.newCameraPosition(camera),
            IDLE_CAMERA_EASE_MILLIS,
        )
    }

    private fun interpolateBearing(
        start: Float?,
        end: Float?,
        fraction: Float,
    ): Float? {
        if (start == null) return end
        if (end == null) return start
        val delta = ((end - start + 540f) % 360f) - 180f
        return (start + delta * fraction + 360f) % 360f
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        /*
         * Local zero-network HOME styles.
         *
         * These guarantee that the HOME Navigation widget has a valid
         * MapLibre style even when no online map style is available.
         */
        private val IDLE_LIGHT_STYLE_JSON =
            """
            {
              "version": 8,
              "name": "HyperNova Home Light",
              "sources": {},
              "layers": [
                {
                  "id": "hypernova-idle-background",
                  "type": "background",
                  "paint": {
                    "background-color": "#EEF3F7"
                  }
                }
              ]
            }
            """.trimIndent()

        private val IDLE_DARK_STYLE_JSON =
            """
            {
              "version": 8,
              "name": "HyperNova Home Dark",
              "sources": {},
              "layers": [
                {
                  "id": "hypernova-idle-background",
                  "type": "background",
                  "paint": {
                    "background-color": "#071521"
                  }
                }
              ]
            }
            """.trimIndent()

        const val DARK_STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
        const val LIGHT_STYLE_URL = "https://tiles.openfreemap.org/styles/positron"
        const val FALLBACK_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

        private const val ROUTE_SOURCE = "hn-launcher-route-source"
        private const val START_SOURCE = "hn-launcher-start-source"
        private const val DESTINATION_SOURCE = "hn-launcher-destination-source"
        private const val VEHICLE_SOURCE = "hn-launcher-vehicle-source"
        private const val ROUTE_CASING_LAYER = "hn-launcher-route-casing"
        private const val ROUTE_LAYER = "hn-launcher-route"
        private const val START_LAYER = "hn-launcher-start"
        private const val DESTINATION_LAYER = "hn-launcher-destination"
        private const val VEHICLE_LAYER = "hn-launcher-vehicle"
        private const val VEHICLE_IMAGE = "hn-launcher-vehicle-arrow"
        private const val MARKER_INTERPOLATION_MILLIS = 550L
        private const val ROUTE_CAMERA_ANIMATION_MILLIS = 900
        private const val FOLLOW_CAMERA_EASE_MILLIS = 650
        private const val FOLLOW_CAMERA_INTERVAL_MILLIS = 800L
        private const val FOLLOW_ZOOM = 15.8
        private const val FOLLOW_TOP_PADDING_FRACTION = 0.28

        /*
         * HyperNova demo origin / ITI fixed position.
         *
         * This is intentionally fixed and does not represent
         * live GPS position.
         */
        private val DEFAULT_HOME_POSITION =
            NavigationPreviewPoint(
                latitude = 30.07112,
                longitude = 31.02075,
            )

        private const val FUSED_PROVIDER = "fused"
        private const val LOCATION_UPDATE_INTERVAL_MS = 1_000L
        private const val LOCATION_UPDATE_MIN_DISTANCE_METERS = 1f
        private const val IDLE_LOCATION_ZOOM = 14.7
        private const val IDLE_CAMERA_EASE_MILLIS = 550

        private const val TAG = "LauncherNavigationMap"
    }
}
