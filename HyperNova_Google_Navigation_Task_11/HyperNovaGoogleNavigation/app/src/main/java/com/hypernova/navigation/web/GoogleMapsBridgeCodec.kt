package com.hypernova.navigation.web

import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.model.GeoPoint
import com.hypernova.navigation.model.GoogleDestinationRecord
import com.hypernova.navigation.model.RouteData
import org.json.JSONArray
import org.json.JSONObject

internal object GoogleMapsBridgeCodec {
    fun parseMapDestination(payload: String): GoogleDestinationRecord {
        val value = JSONObject(payload)
        val placeId = value.optString("placeId").trim()
        val title = value.optString("title").trim()
        require(placeId.isNotBlank() && placeId.length <= MAX_PLACE_ID_LENGTH) {
            "Map destination place ID is invalid."
        }
        require(title.isNotBlank() && title.length <= MAX_TITLE_LENGTH) {
            "Map destination title is invalid."
        }
        val latitude = value.finiteDoubleOrNull("latitude")
        val longitude = value.finiteDoubleOrNull("longitude")
        require(latitude == null || latitude in -90.0..90.0) {
            "Map destination latitude is invalid."
        }
        require(longitude == null || longitude in -180.0..180.0) {
            "Map destination longitude is invalid."
        }
        return GoogleDestinationRecord(
            placeId = placeId,
            title = title,
            subtitle = value.optString("subtitle").trim().take(MAX_SUBTITLE_LENGTH),
            category = value.optString("category").trim().take(MAX_CATEGORY_LENGTH),
            latitude = latitude,
            longitude = longitude,
        )
    }

    fun parseDestinations(payload: String): List<GoogleDestinationRecord> {
        val values = JSONArray(payload)
        return buildList {
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: continue
                val placeId = value.optString("placeId").trim()
                val title = value.optString("title").trim()
                val latitude = value.finiteDoubleOrNull("latitude")
                val longitude = value.finiteDoubleOrNull("longitude")
                if (placeId.isBlank() || title.isBlank()) continue
                add(
                    GoogleDestinationRecord(
                        placeId = placeId,
                        title = title,
                        subtitle = value.optString("subtitle").trim(),
                        category = value.optString("category").trim(),
                        latitude = latitude,
                        longitude = longitude,
                    ),
                )
                if (size == NavigationContract.MAX_DESTINATION_RESULTS) break
            }
        }
    }

    fun parseRoute(payload: String): RouteData {
        val value = JSONObject(payload)
        val rawPoints = value.optJSONArray("points") ?: JSONArray()
        val points = buildList {
            for (index in 0 until rawPoints.length()) {
                val point = rawPoints.optJSONObject(index) ?: continue
                val latitude = point.finiteDoubleOrNull("latitude") ?: continue
                val longitude = point.finiteDoubleOrNull("longitude") ?: continue
                if (latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                    add(GeoPoint(latitude, longitude))
                    if (size == NavigationContract.MAX_ROUTE_PREVIEW_POINTS) break
                }
            }
        }
        require(points.size >= 2) { "Google route geometry is unavailable." }
        return RouteData(
            points = points,
            etaSeconds = value.optLong("etaSeconds", -1L).coerceAtLeast(-1L),
            distanceMeters = value.optLong("distanceMeters", -1L).coerceAtLeast(-1L),
        )
    }

    private fun JSONObject.finiteDoubleOrNull(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return optDouble(name, Double.NaN).takeIf(Double::isFinite)
    }

    private const val MAX_PLACE_ID_LENGTH = 512
    private const val MAX_TITLE_LENGTH = 200
    private const val MAX_SUBTITLE_LENGTH = 500
    private const val MAX_CATEGORY_LENGTH = 120
}
