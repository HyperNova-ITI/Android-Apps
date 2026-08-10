package com.hypernova.navigation.data.osrm

import com.hypernova.navigation.domain.model.FailureKind
import com.hypernova.navigation.domain.model.GeoPoint
import com.hypernova.navigation.domain.model.NavigationDataException
import com.hypernova.navigation.domain.model.Place
import com.hypernova.navigation.domain.model.RouteAlternative
import com.hypernova.navigation.domain.model.RoutePlan
import com.hypernova.navigation.domain.model.RouteStep
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale

class OsrmClient {

    fun route(
        origin: GeoPoint,
        destination: Place
    ): RoutePlan {
        val originCoordinate =
            "${formatCoordinate(origin.longitude)}," +
                formatCoordinate(origin.latitude)

        val destinationCoordinate =
            "${formatCoordinate(destination.longitude)}," +
                formatCoordinate(destination.latitude)

        val routeUrl =
            "$ROUTE_URL/$originCoordinate;$destinationCoordinate" +
                "?alternatives=true" +
                "&steps=true" +
                "&overview=full" +
                "&geometries=geojson"

        val connection =
            URL(routeUrl).openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "User-Agent",
                com.hypernova.navigation.data.nominatim.NominatimClient
                    .APP_USER_AGENT
            )

            val responseCode = connection.responseCode

            if (responseCode !in 200..299) {
                val errorBody =
                    connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        .orEmpty()
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .take(MAX_ERROR_DETAIL_LENGTH)

                throw NavigationDataException(
                    kind = FailureKind.PROVIDER,
                    message = if (errorBody.isBlank()) {
                        "Routing service returned HTTP $responseCode."
                    } else {
                        "Routing service returned HTTP $responseCode: $errorBody"
                    }
                )
            }

            val response =
                connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

            parse(response)
        } catch (exception: SocketTimeoutException) {
            throw NavigationDataException(
                kind = FailureKind.TIMEOUT,
                message = "Route calculation timed out. Please try again.",
                cause = exception
            )
        } catch (exception: NavigationDataException) {
            throw exception
        } catch (exception: IOException) {
            throw NavigationDataException(
                kind = FailureKind.NETWORK,
                message = "The route service is unavailable. Check the network connection.",
                cause = exception
            )
        } catch (exception: Exception) {
            throw NavigationDataException(
                kind = FailureKind.MALFORMED_RESPONSE,
                message = "The route response could not be read.",
                cause = exception
            )
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(response: String): RoutePlan {
        val root = JSONObject(response)
        val responseCode = root.optString("code")

        if (responseCode != "Ok") {
            throw NavigationDataException(
                kind = FailureKind.NO_ROUTE,
                message = root.optString(
                    "message",
                    "No driving route was found for this destination."
                )
            )
        }

        val routes =
            root.optJSONArray("routes")
                ?: throw malformed("The routing response has no routes.")

        val alternatives = buildList {
            for (routeIndex in 0 until routes.length()) {
                val route = routes.optJSONObject(routeIndex) ?: continue
                val distance =
                    route.optDouble("distance", Double.NaN)
                val duration =
                    route.optDouble("duration", Double.NaN)
                val coordinates =
                    route.optJSONObject("geometry")
                        ?.optJSONArray("coordinates")

                if (
                    !distance.isFinite() ||
                    !duration.isFinite() ||
                    coordinates == null
                ) {
                    continue
                }

                val points = buildList {
                    for (pointIndex in 0 until coordinates.length()) {
                        val coordinate =
                            coordinates.optJSONArray(pointIndex) ?: continue
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

                if (points.size < 2) {
                    continue
                }

                add(
                    RouteAlternative(
                        points = points,
                        distanceMeters = distance,
                        durationSeconds = duration,
                        steps = parseSteps(route)
                    )
                )
            }
        }

        if (alternatives.isEmpty()) {
            throw NavigationDataException(
                kind = FailureKind.NO_ROUTE,
                message = "No usable driving route was returned."
            )
        }

        return RoutePlan(
            alternatives =
                alternatives.sortedBy {
                    it.durationSeconds
                }
        )
    }

    private fun parseSteps(route: JSONObject): List<RouteStep> {
        val legs = route.optJSONArray("legs") ?: return emptyList()

        return buildList {
            for (legIndex in 0 until legs.length()) {
                val steps =
                    legs.optJSONObject(legIndex)
                        ?.optJSONArray("steps") ?: continue

                for (stepIndex in 0 until steps.length()) {
                    val step = steps.optJSONObject(stepIndex) ?: continue
                    val maneuver =
                        step.optJSONObject("maneuver") ?: continue
                    val type =
                        maneuver.optString("type")
                            .trim()
                            .lowercase(Locale.ROOT)
                    val modifier =
                        maneuver.optString("modifier")
                            .trim()
                            .lowercase(Locale.ROOT)
                    val exit =
                        if (maneuver.has("exit")) {
                            maneuver.optInt("exit").takeIf { it > 0 }
                        } else {
                            null
                        }
                    val road =
                        step.optString("name").trim()
                            .ifBlank {
                                step.optString("ref").trim()
                            }
                    val destinations =
                        step.optString("destinations").trim()

                    add(
                        RouteStep(
                            instruction = buildInstruction(
                                type = type,
                                modifier = modifier,
                                road = road,
                                destinations = destinations,
                                exitNumber = exit
                            ),
                            roadName = road,
                            distanceMeters =
                                step.optDouble("distance", 0.0)
                                    .takeIf { it.isFinite() } ?: 0.0,
                            durationSeconds =
                                step.optDouble("duration", 0.0)
                                    .takeIf { it.isFinite() } ?: 0.0,
                            maneuverType = type,
                            maneuverModifier = modifier,
                            exitNumber = exit
                        )
                    )
                }
            }
        }
    }

    private fun buildInstruction(
        type: String,
        modifier: String,
        road: String,
        destinations: String,
        exitNumber: Int?
    ): String {
        val direction = directionText(modifier)
        val roadSuffix =
            if (road.isBlank()) "" else " onto $road"
        val startRoadSuffix =
            if (road.isBlank()) "" else " on $road"
        val destinationSuffix =
            if (destinations.isBlank()) "" else " toward $destinations"

        return when (type) {
            "depart" -> "Start$startRoadSuffix"
            "arrive" -> "Arrive at destination"
            "turn" -> if (modifier == "straight") {
                "Continue straight$roadSuffix"
            } else {
                "Turn ${direction.ifBlank { "ahead" }}$roadSuffix"
            }
            "continue" -> if (
                modifier.isBlank() ||
                modifier == "straight"
            ) {
                "Continue straight$roadSuffix"
            } else {
                "Continue $direction$roadSuffix"
            }
            "new name" -> if (road.isBlank()) {
                "Continue straight"
            } else {
                "Continue onto $road"
            }
            "merge" ->
                "Merge ${direction.ifBlank { "ahead" }}" +
                    roadSuffix +
                    destinationSuffix
            "on ramp" ->
                "Take the ramp ${direction.ifBlank { "ahead" }}" +
                    destinationSuffix
            "off ramp" ->
                "Take the exit ${direction.ifBlank { "ahead" }}" +
                    destinationSuffix
            "fork" ->
                "Keep ${direction.ifBlank { "straight" }}$roadSuffix"
            "end of road" ->
                "At the end of the road, turn " +
                    direction.ifBlank { "ahead" } +
                    roadSuffix
            "roundabout", "rotary" ->
                "Enter the roundabout" +
                    (exitNumber?.let { " and take exit $it" } ?: "") +
                    roadSuffix
            "exit roundabout", "exit rotary" ->
                "Exit the roundabout$roadSuffix"
            "use lane" -> "Use the indicated lane$roadSuffix"
            else -> "Continue$roadSuffix"
        }
    }

    private fun directionText(modifier: String): String =
        when (modifier) {
            "uturn" -> "and make a U-turn"
            "sharp right" -> "sharp right"
            "right" -> "right"
            "slight right" -> "slightly right"
            "straight" -> "straight"
            "slight left" -> "slightly left"
            "left" -> "left"
            "sharp left" -> "sharp left"
            else -> ""
        }

    private fun malformed(message: String) =
        NavigationDataException(
            kind = FailureKind.MALFORMED_RESPONSE,
            message = message
        )

    private fun formatCoordinate(coordinate: Double): String =
        String.format(Locale.US, "%.6f", coordinate)

    companion object {
        private const val ROUTE_URL =
            "https://router.project-osrm.org/route/v1/driving"
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val MAX_ERROR_DETAIL_LENGTH = 180
    }
}
