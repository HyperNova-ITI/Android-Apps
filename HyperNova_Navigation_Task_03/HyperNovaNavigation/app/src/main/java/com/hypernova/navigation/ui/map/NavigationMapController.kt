package com.hypernova.navigation.ui.map

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import androidx.core.content.ContextCompat
import com.hypernova.navigation.R
import com.hypernova.navigation.domain.model.GeoPoint
import com.hypernova.navigation.domain.model.Place
import com.hypernova.navigation.domain.model.RoutePlan
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
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

class NavigationMapController(
    private val context: Context,
    private val mapView: MapView
) {
    private var map: MapLibreMap? = null
    private var styleReady = false
    private var fallbackAttempted = false
    private var origin: GeoPoint? = null
    private var results: List<Place> = emptyList()
    private var selectedResultId: String? = null
    private var destination: Place? = null
    private var routePlan: RoutePlan? = null
    private var calculating = false
    private var onStyleReady: (() -> Unit)? = null
    private var onStyleError: ((String) -> Unit)? = null

    private val failureListener =
        MapView.OnDidFailLoadingMapListener { message ->
            if (!styleReady) {
                if (!fallbackAttempted) {
                    fallbackAttempted = true
                    map?.let { loadStyle(it, FALLBACK_STYLE_URL) }
                } else {
                    onStyleError?.invoke(
                        message.ifBlank {
                            "The map style could not be loaded."
                        }
                    )
                }
            }
        }

    init {
        mapView.addOnDidFailLoadingMapListener(failureListener)
    }

    fun initialize(
        isNightMode: Boolean,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        onStyleReady = onReady
        onStyleError = onError
        styleReady = false
        fallbackAttempted = false

        mapView.getMapAsync { readyMap ->
            map = readyMap
            readyMap.uiSettings.apply {
                isCompassEnabled = false
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isLogoEnabled = true
                isAttributionEnabled = true
                logoGravity = Gravity.BOTTOM or Gravity.START
                attributionGravity = Gravity.BOTTOM or Gravity.START
            }

            loadStyle(
                readyMap,
                if (isNightMode) {
                    DARK_STYLE_URL
                } else {
                    LIGHT_STYLE_URL
                }
            )
        }
    }

    fun setScene(
        origin: GeoPoint,
        results: List<Place> = emptyList(),
        selectedResultId: String? = null,
        destination: Place? = null,
        routePlan: RoutePlan? = null,
        calculating: Boolean = false
    ) {
        this.origin = origin
        this.results = results
        this.selectedResultId = selectedResultId
        this.destination = destination
        this.routePlan = routePlan
        this.calculating = calculating
        reapplyScene()
    }

    fun centerOnOrigin() {
        val target = origin ?: return
        animateCamera(target, ORIGIN_ZOOM)
    }

    fun centerOnDestination() {
        val target = destination ?: return
        animateCamera(
            GeoPoint(target.latitude, target.longitude),
            DESTINATION_ZOOM
        )
    }

    fun fitSearchResults() {
        val points =
            buildList {
                origin?.let(::add)
                results.forEach {
                    add(GeoPoint(it.latitude, it.longitude))
                }
            }

        fitPoints(
            points = points,
            topPaddingDp = 260,
            bottomPaddingDp = 230
        )
    }

    fun fitRoute(
        overview: Boolean = false
    ) {
        val points =
            routePlan?.selected?.points
                ?: buildList {
                    origin?.let(::add)
                    destination?.let {
                        add(GeoPoint(it.latitude, it.longitude))
                    }
                }

        fitPoints(
            points = points,
            topPaddingDp = if (overview) 110 else 180,
            bottomPaddingDp = if (overview) 400 else 260
        )
    }

    fun zoomIn() {
        map?.animateCamera(CameraUpdateFactory.zoomIn())
    }

    fun zoomOut() {
        map?.animateCamera(CameraUpdateFactory.zoomOut())
    }

    fun destroy() {
        mapView.removeOnDidFailLoadingMapListener(failureListener)
        onStyleReady = null
        onStyleError = null
    }

    private fun loadStyle(
        map: MapLibreMap,
        styleUrl: String
    ) {
        styleReady = false

        map.setStyle(styleUrl) { style ->
            styleReady = true
            addSourcesAndLayers(style)
            reapplyScene()
            onStyleReady?.invoke()
        }
    }

    private fun addSourcesAndLayers(style: Style) {
        addSourceIfMissing(style, ALTERNATIVE_ROUTE_SOURCE)
        addSourceIfMissing(style, SELECTED_ROUTE_SOURCE)
        addSourceIfMissing(style, CALCULATION_SOURCE)
        addSourceIfMissing(style, RESULT_SOURCE)
        addSourceIfMissing(style, SELECTED_RESULT_SOURCE)
        addSourceIfMissing(style, ORIGIN_SOURCE)
        addSourceIfMissing(style, DESTINATION_SOURCE)

        val routeColor =
            ContextCompat.getColor(context, R.color.hypernova_cyan)
        val routeCasing =
            ContextCompat.getColor(context, R.color.hypernova_cyan_dark)
        val alternativeColor =
            ContextCompat.getColor(
                context,
                R.color.hypernova_text_disabled
            )
        val cardColor =
            ContextCompat.getColor(context, R.color.hypernova_card)
        val destinationColor =
            ContextCompat.getColor(
                context,
                R.color.hypernova_text_primary
            )

        if (style.getLayer(ALTERNATIVE_ROUTE_LAYER) == null) {
            style.addLayer(
                LineLayer(
                    ALTERNATIVE_ROUTE_LAYER,
                    ALTERNATIVE_ROUTE_SOURCE
                ).withProperties(
                    lineColor(alternativeColor),
                    lineWidth(5.0f),
                    lineOpacity(0.58f),
                    lineCap(Property.LINE_CAP_ROUND),
                    lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
        }

        if (style.getLayer(SELECTED_ROUTE_CASING_LAYER) == null) {
            style.addLayer(
                LineLayer(
                    SELECTED_ROUTE_CASING_LAYER,
                    SELECTED_ROUTE_SOURCE
                ).withProperties(
                    lineColor(routeCasing),
                    lineWidth(12.0f),
                    lineOpacity(0.86f),
                    lineCap(Property.LINE_CAP_ROUND),
                    lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
        }

        if (style.getLayer(SELECTED_ROUTE_LAYER) == null) {
            style.addLayer(
                LineLayer(
                    SELECTED_ROUTE_LAYER,
                    SELECTED_ROUTE_SOURCE
                ).withProperties(
                    lineColor(routeColor),
                    lineWidth(7.0f),
                    lineOpacity(1.0f),
                    lineCap(Property.LINE_CAP_ROUND),
                    lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
        }

        if (style.getLayer(CALCULATION_LAYER) == null) {
            style.addLayer(
                LineLayer(
                    CALCULATION_LAYER,
                    CALCULATION_SOURCE
                ).withProperties(
                    lineColor(routeColor),
                    lineWidth(4.0f),
                    lineOpacity(0.9f),
                    lineDasharray(arrayOf(1.0f, 1.5f)),
                    lineCap(Property.LINE_CAP_ROUND)
                )
            )
        }

        addCircleLayer(
            style = style,
            layerId = RESULT_LAYER,
            sourceId = RESULT_SOURCE,
            color = destinationColor,
            radius = 7.0f,
            strokeColor = cardColor,
            strokeWidth = 3.0f,
            opacity = 0.8f
        )

        addCircleLayer(
            style = style,
            layerId = SELECTED_RESULT_LAYER,
            sourceId = SELECTED_RESULT_SOURCE,
            color = routeColor,
            radius = 10.0f,
            strokeColor = cardColor,
            strokeWidth = 4.0f,
            opacity = 1.0f
        )

        addCircleLayer(
            style = style,
            layerId = ORIGIN_RING_LAYER,
            sourceId = ORIGIN_SOURCE,
            color = Color.TRANSPARENT,
            radius = 18.0f,
            strokeColor = routeColor,
            strokeWidth = 2.0f,
            opacity = 0.65f
        )

        addCircleLayer(
            style = style,
            layerId = ORIGIN_LAYER,
            sourceId = ORIGIN_SOURCE,
            color = routeColor,
            radius = 9.0f,
            strokeColor = cardColor,
            strokeWidth = 4.0f,
            opacity = 1.0f
        )

        addCircleLayer(
            style = style,
            layerId = DESTINATION_LAYER,
            sourceId = DESTINATION_SOURCE,
            color = destinationColor,
            radius = 10.0f,
            strokeColor = cardColor,
            strokeWidth = 4.0f,
            opacity = 1.0f
        )
    }

    private fun addCircleLayer(
        style: Style,
        layerId: String,
        sourceId: String,
        color: Int,
        radius: Float,
        strokeColor: Int,
        strokeWidth: Float,
        opacity: Float
    ) {
        if (style.getLayer(layerId) == null) {
            style.addLayer(
                CircleLayer(layerId, sourceId)
                    .withProperties(
                        circleColor(color),
                        circleRadius(radius),
                        circleStrokeColor(strokeColor),
                        circleStrokeWidth(strokeWidth),
                        circleOpacity(opacity)
                    )
            )
        }
    }

    private fun addSourceIfMissing(
        style: Style,
        sourceId: String
    ) {
        if (style.getSource(sourceId) == null) {
            style.addSource(
                GeoJsonSource(
                    sourceId,
                    FeatureCollection.fromFeatures(emptyArray())
                )
            )
        }
    }

    private fun reapplyScene() {
        val style = map?.style ?: return
        if (!styleReady) return

        updatePointSource(
            style,
            ORIGIN_SOURCE,
            origin
        )

        updatePointSource(
            style,
            DESTINATION_SOURCE,
            destination?.let {
                GeoPoint(it.latitude, it.longitude)
            }
        )

        val selected =
            results.firstOrNull { it.id == selectedResultId }
        val unselected =
            results.filterNot { it.id == selectedResultId }

        updatePlacesSource(style, RESULT_SOURCE, unselected)
        updatePlacesSource(
            style,
            SELECTED_RESULT_SOURCE,
            listOfNotNull(selected)
        )

        val selectedRoute = routePlan?.selected
        val alternativeRoutes =
            routePlan?.alternatives
                ?.filterIndexed { index, _ ->
                    index != routePlan?.selectedIndex
                }
                .orEmpty()

        updateLineSource(
            style,
            SELECTED_ROUTE_SOURCE,
            selectedRoute?.points?.let(::lineFeature)
        )

        updateLineFeaturesSource(
            style,
            ALTERNATIVE_ROUTE_SOURCE,
            alternativeRoutes.map { lineFeature(it.points) }
        )

        val calculationLine =
            if (calculating) {
                val start = origin
                val end = destination
                if (start != null && end != null) {
                    lineFeature(
                        listOf(
                            start,
                            GeoPoint(
                                latitude =
                                    (start.latitude + end.latitude) / 2.0,
                                longitude =
                                    (start.longitude + end.longitude) / 2.0
                            ),
                            GeoPoint(end.latitude, end.longitude)
                        )
                    )
                } else {
                    null
                }
            } else {
                null
            }

        updateLineSource(style, CALCULATION_SOURCE, calculationLine)
    }

    private fun updatePointSource(
        style: Style,
        sourceId: String,
        point: GeoPoint?
    ) {
        val features =
            point?.let {
                arrayOf(
                    Feature.fromGeometry(
                        Point.fromLngLat(
                            it.longitude,
                            it.latitude
                        )
                    )
                )
            } ?: emptyArray()

        source(style, sourceId)?.setGeoJson(
            FeatureCollection.fromFeatures(features)
        )
    }

    private fun updatePlacesSource(
        style: Style,
        sourceId: String,
        places: List<Place>
    ) {
        val features =
            places.map {
                Feature.fromGeometry(
                    Point.fromLngLat(
                        it.longitude,
                        it.latitude
                    )
                )
            }.toTypedArray()

        source(style, sourceId)?.setGeoJson(
            FeatureCollection.fromFeatures(features)
        )
    }

    private fun updateLineSource(
        style: Style,
        sourceId: String,
        feature: Feature?
    ) {
        updateLineFeaturesSource(
            style,
            sourceId,
            listOfNotNull(feature)
        )
    }

    private fun updateLineFeaturesSource(
        style: Style,
        sourceId: String,
        features: List<Feature>
    ) {
        source(style, sourceId)?.setGeoJson(
            FeatureCollection.fromFeatures(features)
        )
    }

    private fun source(
        style: Style,
        sourceId: String
    ): GeoJsonSource? =
        style.getSourceAs(sourceId)

    private fun lineFeature(points: List<GeoPoint>): Feature =
        Feature.fromGeometry(
            LineString.fromLngLats(
                points.map {
                    Point.fromLngLat(
                        it.longitude,
                        it.latitude
                    )
                }
            )
        )

    private fun animateCamera(
        point: GeoPoint,
        zoom: Double
    ) {
        val map = map ?: return
        val camera =
            CameraPosition.Builder()
                .target(LatLng(point.latitude, point.longitude))
                .zoom(zoom)
                .bearing(0.0)
                .tilt(0.0)
                .build()

        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(camera),
            CAMERA_ANIMATION_MS
        )
    }

    private fun fitPoints(
        points: List<GeoPoint>,
        topPaddingDp: Int,
        bottomPaddingDp: Int
    ) {
        val map = map ?: return

        if (points.size < 2) {
            points.firstOrNull()?.let {
                animateCamera(it, DESTINATION_ZOOM)
            }
            return
        }

        val bounds =
            LatLngBounds.Builder()
                .includes(
                    points.map {
                        LatLng(it.latitude, it.longitude)
                    }
                )
                .build()

        map.animateCamera(
            CameraUpdateFactory.newLatLngBounds(
                bounds,
                dp(SIDE_PADDING_DP),
                dp(topPaddingDp),
                dp(SIDE_PADDING_DP),
                dp(bottomPaddingDp)
            ),
            ROUTE_CAMERA_ANIMATION_MS
        )
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        const val DARK_STYLE_URL =
            "https://tiles.openfreemap.org/styles/dark"
        const val LIGHT_STYLE_URL =
            "https://tiles.openfreemap.org/styles/positron"
        const val FALLBACK_STYLE_URL =
            "https://tiles.openfreemap.org/styles/liberty"

        private const val ALTERNATIVE_ROUTE_SOURCE =
            "hn-alternative-route-source"
        private const val SELECTED_ROUTE_SOURCE =
            "hn-selected-route-source"
        private const val CALCULATION_SOURCE =
            "hn-calculation-source"
        private const val RESULT_SOURCE = "hn-result-source"
        private const val SELECTED_RESULT_SOURCE =
            "hn-selected-result-source"
        private const val ORIGIN_SOURCE = "hn-origin-source"
        private const val DESTINATION_SOURCE =
            "hn-destination-source"

        private const val ALTERNATIVE_ROUTE_LAYER =
            "hn-alternative-route-layer"
        private const val SELECTED_ROUTE_CASING_LAYER =
            "hn-selected-route-casing-layer"
        private const val SELECTED_ROUTE_LAYER =
            "hn-selected-route-layer"
        private const val CALCULATION_LAYER =
            "hn-calculation-layer"
        private const val RESULT_LAYER = "hn-result-layer"
        private const val SELECTED_RESULT_LAYER =
            "hn-selected-result-layer"
        private const val ORIGIN_RING_LAYER = "hn-origin-ring-layer"
        private const val ORIGIN_LAYER = "hn-origin-layer"
        private const val DESTINATION_LAYER =
            "hn-destination-layer"

        private const val ORIGIN_ZOOM = 16.2
        private const val DESTINATION_ZOOM = 14.5
        private const val SIDE_PADDING_DP = 36
        private const val CAMERA_ANIMATION_MS = 750
        private const val ROUTE_CAMERA_ANIMATION_MS = 1_050
    }
}
