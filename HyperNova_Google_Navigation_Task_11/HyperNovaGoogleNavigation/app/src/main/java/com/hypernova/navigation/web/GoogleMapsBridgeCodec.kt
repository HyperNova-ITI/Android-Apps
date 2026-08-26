package com.hypernova.navigation.web

import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.model.GeoPoint
import com.hypernova.navigation.model.GoogleDestinationRecord
import com.hypernova.navigation.model.RouteData
import com.hypernova.navigation.places.PlaceContact
import com.hypernova.navigation.session.RouteGeometry
import org.json.JSONArray
import org.json.JSONObject

internal object GoogleMapsBridgeCodec {
    /** Memory guard on the raw bridge payload, before the route is sampled to preview size. */
    private const val RAW_ROUTE_POINT_CEILING = 8_192

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
        /*
         * Read the WHOLE polyline, then sample it down.
         *
         * This used to `break` once MAX_ROUTE_PREVIEW_POINTS had been collected, which keeps the
         * first 128 points of the route -- a prefix, not a summary. Google returns thousands of
         * points for a long route, so 128 of them covered about 2 km of a 191 km trip. Every
         * later stage (RouteGeometry.sanitizeAndBound, ContractProjection.simplifyRoutePoints,
         * the Launcher's sampleForPreview) samples correctly, but they were all sampling an
         * already-truncated prefix, so the HOME widget rendered a 2 km stub while the same card
         * correctly read "191.5 km" -- distance and ETA come straight from this JSON and were
         * never truncated, which is what made the bug look like a zoom problem.
         *
         * RAW_ROUTE_POINT_CEILING still bounds what a hostile or runaway bridge payload can
         * allocate; it is a memory guard, not a route-shape decision.
         */
        val rawGeometry = buildList {
            for (index in 0 until rawPoints.length()) {
                val point = rawPoints.optJSONObject(index) ?: continue
                val latitude = point.finiteDoubleOrNull("latitude") ?: continue
                val longitude = point.finiteDoubleOrNull("longitude") ?: continue
                if (latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                    add(GeoPoint(latitude, longitude))
                    if (size == RAW_ROUTE_POINT_CEILING) break
                }
            }
        }
        val points =
            if (rawGeometry.size >= 2) {
                RouteGeometry.sanitizeAndBound(
                    rawGeometry,
                    NavigationContract.MAX_ROUTE_PREVIEW_POINTS,
                )
            } else {
                rawGeometry
            }
        require(points.size >= 2) { "Google route geometry is unavailable." }
        return RouteData(
            points = points,
            etaSeconds = value.optLong("etaSeconds", -1L).coerceAtLeast(-1L),
            distanceMeters = value.optLong("distanceMeters", -1L).coerceAtLeast(-1L),
        )
    }

    fun parseContact(payload: String): PlaceContact {
        val value = JSONObject(payload)
        val displayName = value.optString("displayName").trim().take(MAX_TITLE_LENGTH)
        val phoneNumber = value.optString("phoneNumber").trim().take(MAX_PHONE_LENGTH)
        require(displayName.isNotBlank()) { "Google Place display name is unavailable." }
        require(phoneNumber.count(Char::isDigit) >= 5) { "Google Place phone number is unavailable." }
        return PlaceContact(displayName, phoneNumber)
    }

    private fun JSONObject.finiteDoubleOrNull(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return optDouble(name, Double.NaN).takeIf(Double::isFinite)
    }

    private const val MAX_PLACE_ID_LENGTH = 512
    private const val MAX_TITLE_LENGTH = 200
    private const val MAX_SUBTITLE_LENGTH = 500
    private const val MAX_CATEGORY_LENGTH = 120
    private const val MAX_PHONE_LENGTH = 48
}
