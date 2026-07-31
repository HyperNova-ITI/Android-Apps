package com.hypernova.navigation.domain.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(
    val latitude: Double,
    val longitude: Double
)

enum class PlaceProvider {
    NOMINATIM,
    OVERPASS,
    VERIFIED_OSM
}

enum class NearbyCategory(
    val displayName: String,
    val osmSelector: String
) {
    PARKING(
        displayName = "Parking",
        osmSelector = "[\"amenity\"=\"parking\"]"
    ),
    FUEL(
        displayName = "Fuel",
        osmSelector = "[\"amenity\"=\"fuel\"]"
    ),
    FOOD(
        displayName = "Food",
        osmSelector =
            "[\"amenity\"~\"^(restaurant|fast_food|cafe|food_court)$\"]"
    ),
    HOSPITAL(
        displayName = "Hospital",
        osmSelector =
            "[\"amenity\"~\"^(hospital|clinic|doctors)$\"]"
    ),
    SHOPPING(
        displayName = "Shopping",
        osmSelector = "[\"shop\"]"
    );

    fun fallbackName(subcategory: String): String =
        when (this) {
            PARKING -> "Unnamed parking area"
            FUEL -> "Unnamed fuel station"
            FOOD ->
                "Unnamed " +
                    subcategory
                        .ifBlank { "food venue" }
                        .replace('_', ' ')
            HOSPITAL ->
                when (subcategory) {
                    "clinic" -> "Unnamed clinic"
                    "doctors" -> "Unnamed doctor's office"
                    else -> "Unnamed hospital"
                }
            SHOPPING ->
                if (subcategory.isBlank()) {
                    "Unnamed shop"
                } else {
                    "Unnamed " +
                        subcategory.replace('_', ' ') +
                        " shop"
                }
        }
}

data class Place(
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
    val category: String = "",
    val type: String = "",
    val provider: PlaceProvider = PlaceProvider.NOMINATIM,
    val providerId: String = "",
    val osmType: String = "",
    val osmId: Long? = null,
    val primaryName: String = "",
    val formattedAddress: String = "",
    val brand: String = "",
    val operator: String = "",
    val subcategory: String = "",
    val phone: String = "",
    val website: String = "",
    val openingHours: String = "",
    val straightLineDistanceMeters: Double? = null
) {
    val id: String
        get() =
            providerId.ifBlank {
                if (osmType.isNotBlank() && osmId != null) {
                    "${provider.name.lowercase(Locale.ROOT)}:$osmType:$osmId"
                } else {
                    String.format(
                        Locale.US,
                        "%.6f,%.6f",
                        latitude,
                        longitude
                    )
                }
            }

    val name: String
        get() =
            primaryName.trim().ifBlank {
                displayName
                    .substringBefore(",")
                    .trim()
                    .ifBlank { "Unnamed place" }
            }

    val address: String
        get() =
            formattedAddress.trim().ifBlank {
                displayName
                    .substringAfter(",", "")
                    .trim()
            }

    val categoryDescription: String
        get() =
            buildList {
                category
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let(::add)
                subcategory
                    .trim()
                    .replace('_', ' ')
                    .takeIf {
                        it.isNotBlank() &&
                            !it.equals(category, ignoreCase = true)
                    }
                    ?.let(::add)
            }.joinToString(" • ")
}

object GeoDistance {
    fun meters(
        from: GeoPoint,
        to: GeoPoint
    ): Double {
        val latitudeDelta =
            Math.toRadians(to.latitude - from.latitude)
        val longitudeDelta =
            Math.toRadians(to.longitude - from.longitude)
        val fromLatitude = Math.toRadians(from.latitude)
        val toLatitude = Math.toRadians(to.latitude)
        val calculation =
            sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
                cos(fromLatitude) *
                cos(toLatitude) *
                sin(longitudeDelta / 2) *
                sin(longitudeDelta / 2)

        return EARTH_RADIUS_METERS *
            2 *
            asin(sqrt(calculation))
    }

    private const val EARTH_RADIUS_METERS = 6_371_000.0
}

data class RouteStep(
    val instruction: String,
    val roadName: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val maneuverType: String,
    val maneuverModifier: String,
    val exitNumber: Int?
)

data class RouteAlternative(
    val points: List<GeoPoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<RouteStep>
)

data class RoutePlan(
    val alternatives: List<RouteAlternative>,
    val selectedIndex: Int = 0
) {
    val selected: RouteAlternative
        get() = alternatives[
            selectedIndex.coerceIn(
                0,
                alternatives.lastIndex.coerceAtLeast(0)
            )
        ]
}

enum class NavigationScreen {
    HOME,
    SEARCH,
    SEARCHING,
    RESULTS,
    CALCULATING_ROUTE,
    ROUTE_PREVIEW,
    ROUTE_ACTIVE,
    ROUTE_OVERVIEW,
    REROUTING,
    ARRIVED,
    LOCATION_UNAVAILABLE,
    OFFLINE,
    ROUTE_ERROR
}

enum class SavedDestinationTarget {
    HOME,
    WORK
}

data class NavigationUiState(
    val screen: NavigationScreen = NavigationScreen.HOME,
    val query: String = "",
    val nearbyCategory: NearbyCategory? = null,
    val searchRadiusMeters: Int? = null,
    val searchResults: List<Place> = emptyList(),
    val selectedResultId: String? = null,
    val destination: Place? = null,
    val routePlan: RoutePlan? = null,
    val savedDestinationTarget: SavedDestinationTarget? = null,
    val message: String? = null
)

enum class FailureKind {
    NETWORK,
    TIMEOUT,
    LOCATION_UNAVAILABLE,
    NO_ROUTE,
    MALFORMED_RESPONSE,
    PROVIDER,
    CANCELLED,
    UNKNOWN
}

class NavigationDataException(
    val kind: FailureKind,
    override val message: String,
    cause: Throwable? = null,
    val httpStatusCode: Int? = null,
    val retryable: Boolean = false,
    val endpointLabel: String? = null
) : Exception(message, cause)

object NavigationJson {

    fun placeToJson(place: Place): JSONObject =
        JSONObject()
            .put("displayName", place.displayName)
            .put("latitude", place.latitude)
            .put("longitude", place.longitude)
            .put("category", place.category)
            .put("type", place.type)
            .put("provider", place.provider.name)
            .put("providerId", place.providerId)
            .put("osmType", place.osmType)
            .put("osmId", place.osmId ?: JSONObject.NULL)
            .put("primaryName", place.primaryName)
            .put("formattedAddress", place.formattedAddress)
            .put("brand", place.brand)
            .put("operator", place.operator)
            .put("subcategory", place.subcategory)
            .put("phone", place.phone)
            .put("website", place.website)
            .put("openingHours", place.openingHours)
            .put(
                "straightLineDistanceMeters",
                place.straightLineDistanceMeters ?: JSONObject.NULL
            )

    fun placeFromJson(json: JSONObject): Place? {
        val displayName = json.optString("displayName").trim()
        val latitude = json.optDouble("latitude", Double.NaN)
        val longitude = json.optDouble("longitude", Double.NaN)

        if (
            displayName.isBlank() ||
            !latitude.isFinite() ||
            !longitude.isFinite()
        ) {
            return null
        }

        return Place(
            displayName = displayName,
            latitude = latitude,
            longitude = longitude,
            category = json.optString("category"),
            type = json.optString("type"),
            provider =
                runCatching {
                    PlaceProvider.valueOf(
                        json.optString(
                            "provider",
                            PlaceProvider.NOMINATIM.name
                        )
                    )
                }.getOrDefault(PlaceProvider.NOMINATIM),
            providerId = json.optString("providerId"),
            osmType = json.optString("osmType"),
            osmId =
                if (json.isNull("osmId")) {
                    null
                } else {
                    json.optLong("osmId").takeIf { it > 0L }
                },
            primaryName = json.optString("primaryName"),
            formattedAddress = json.optString("formattedAddress"),
            brand = json.optString("brand"),
            operator = json.optString("operator"),
            subcategory = json.optString("subcategory"),
            phone = json.optString("phone"),
            website = json.optString("website"),
            openingHours = json.optString("openingHours"),
            straightLineDistanceMeters =
                if (json.isNull("straightLineDistanceMeters")) {
                    null
                } else {
                    json.optDouble(
                        "straightLineDistanceMeters",
                        Double.NaN
                    ).takeIf { it.isFinite() }
                }
        )
    }

    fun routePlanToJson(routePlan: RoutePlan): JSONObject =
        JSONObject()
            .put("selectedIndex", routePlan.selectedIndex)
            .put(
                "alternatives",
                JSONArray().apply {
                    routePlan.alternatives.forEach { route ->
                        put(
                            JSONObject()
                                .put(
                                    "points",
                                    JSONArray().apply {
                                        route.points.forEach { point ->
                                            put(
                                                JSONArray()
                                                    .put(point.longitude)
                                                    .put(point.latitude)
                                            )
                                        }
                                    }
                                )
                                .put(
                                    "distanceMeters",
                                    route.distanceMeters
                                )
                                .put(
                                    "durationSeconds",
                                    route.durationSeconds
                                )
                                .put(
                                    "steps",
                                    JSONArray().apply {
                                        route.steps.forEach { step ->
                                            put(
                                                JSONObject()
                                                    .put(
                                                        "instruction",
                                                        step.instruction
                                                    )
                                                    .put(
                                                        "roadName",
                                                        step.roadName
                                                    )
                                                    .put(
                                                        "distanceMeters",
                                                        step.distanceMeters
                                                    )
                                                    .put(
                                                        "durationSeconds",
                                                        step.durationSeconds
                                                    )
                                                    .put(
                                                        "maneuverType",
                                                        step.maneuverType
                                                    )
                                                    .put(
                                                        "maneuverModifier",
                                                        step.maneuverModifier
                                                    )
                                                    .put(
                                                        "exitNumber",
                                                        step.exitNumber
                                                    )
                                            )
                                        }
                                    }
                                )
                        )
                    }
                }
            )

    fun routePlanFromJson(json: JSONObject): RoutePlan? {
        val alternativesJson =
            json.optJSONArray("alternatives") ?: return null

        val alternatives = buildList {
            for (routeIndex in 0 until alternativesJson.length()) {
                val routeJson =
                    alternativesJson.optJSONObject(routeIndex) ?: continue

                val pointsJson =
                    routeJson.optJSONArray("points") ?: continue

                val points = buildList {
                    for (pointIndex in 0 until pointsJson.length()) {
                        val coordinate =
                            pointsJson.optJSONArray(pointIndex) ?: continue

                        val longitude =
                            coordinate.optDouble(0, Double.NaN)

                        val latitude =
                            coordinate.optDouble(1, Double.NaN)

                        if (
                            longitude.isFinite() &&
                            latitude.isFinite()
                        ) {
                            add(
                                GeoPoint(
                                    latitude = latitude,
                                    longitude = longitude
                                )
                            )
                        }
                    }
                }

                val distance =
                    routeJson.optDouble(
                        "distanceMeters",
                        Double.NaN
                    )

                val duration =
                    routeJson.optDouble(
                        "durationSeconds",
                        Double.NaN
                    )

                if (
                    points.size < 2 ||
                    !distance.isFinite() ||
                    !duration.isFinite()
                ) {
                    continue
                }

                val stepsJson =
                    routeJson.optJSONArray("steps") ?: JSONArray()

                val steps = buildList {
                    for (stepIndex in 0 until stepsJson.length()) {
                        val stepJson =
                            stepsJson.optJSONObject(stepIndex) ?: continue

                        add(
                            RouteStep(
                                instruction =
                                    stepJson.optString("instruction"),
                                roadName =
                                    stepJson.optString("roadName"),
                                distanceMeters =
                                    stepJson.optDouble(
                                        "distanceMeters",
                                        0.0
                                    ),
                                durationSeconds =
                                    stepJson.optDouble(
                                        "durationSeconds",
                                        0.0
                                    ),
                                maneuverType =
                                    stepJson.optString("maneuverType"),
                                maneuverModifier =
                                    stepJson.optString("maneuverModifier"),
                                exitNumber =
                                    if (stepJson.isNull("exitNumber")) {
                                        null
                                    } else {
                                        stepJson.optInt("exitNumber")
                                    }
                            )
                        )
                    }
                }

                add(
                    RouteAlternative(
                        points = points,
                        distanceMeters = distance,
                        durationSeconds = duration,
                        steps = steps
                    )
                )
            }
        }

        if (alternatives.isEmpty()) {
            return null
        }

        return RoutePlan(
            alternatives = alternatives,
            selectedIndex =
                json.optInt("selectedIndex", 0)
                    .coerceIn(0, alternatives.lastIndex)
        )
    }
}
