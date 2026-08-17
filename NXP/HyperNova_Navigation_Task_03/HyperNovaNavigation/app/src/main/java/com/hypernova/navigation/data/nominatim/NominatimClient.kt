package com.hypernova.navigation.data.nominatim

import com.hypernova.navigation.domain.model.FailureKind
import com.hypernova.navigation.domain.model.NavigationDataException
import com.hypernova.navigation.domain.model.Place
import com.hypernova.navigation.domain.model.PlaceProvider
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class NominatimClient {

    fun search(query: String): List<Place> {
        val encodedQuery =
            URLEncoder.encode(
                query,
                StandardCharsets.UTF_8.name()
            )

        val searchUrl =
            "$SEARCH_URL" +
                "?format=jsonv2" +
                "&addressdetails=1" +
                "&dedupe=1" +
                "&countrycodes=eg" +
                "&viewbox=$EGYPT_SEARCH_BIAS_VIEWBOX" +
                "&bounded=0" +
                "&limit=$RESULT_LIMIT" +
                "&q=$encodedQuery"

        val connection =
            URL(searchUrl).openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "Accept-Language",
                Locale.getDefault().toLanguageTag()
            )
            connection.setRequestProperty("User-Agent", APP_USER_AGENT)

            val responseCode = connection.responseCode

            if (responseCode !in 200..299) {
                throw providerFailure(
                    responseCode,
                    connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        .orEmpty()
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
                message = "Place search timed out. Please try again.",
                cause = exception
            )
        } catch (exception: NavigationDataException) {
            throw exception
        } catch (exception: IOException) {
            throw NavigationDataException(
                kind = FailureKind.NETWORK,
                message = "Place search is unavailable. Check the network connection.",
                cause = exception
            )
        } catch (exception: Exception) {
            throw NavigationDataException(
                kind = FailureKind.MALFORMED_RESPONSE,
                message = "The place search response could not be read.",
                cause = exception
            )
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(response: String): List<Place> {
        val places = JSONArray(response)

        return buildList {
            for (index in 0 until places.length()) {
                val json = places.optJSONObject(index) ?: continue
                val displayName = json.optString("display_name").trim()
                val latitude =
                    json.optString("lat").toDoubleOrNull()
                val longitude =
                    json.optString("lon").toDoubleOrNull()
                val osmType = json.optString("osm_type").trim()
                val osmId =
                    json.optLong("osm_id", 0L)
                        .takeIf { it > 0L }
                val primaryName =
                    json.optString("name").trim()
                        .ifBlank {
                            displayName.substringBefore(",").trim()
                        }
                val formattedAddress =
                    displayName.substringAfter(",", "").trim()

                if (
                    displayName.isBlank() ||
                    latitude == null ||
                    longitude == null
                ) {
                    continue
                }

                add(
                    Place(
                        displayName = displayName,
                        latitude = latitude,
                        longitude = longitude,
                        category = json.optString("category").trim(),
                        type = json.optString("type").trim(),
                        provider = PlaceProvider.NOMINATIM,
                        providerId =
                            if (osmType.isNotBlank() && osmId != null) {
                                "nominatim:$osmType:$osmId"
                            } else {
                                "nominatim:place:" +
                                    json.optLong("place_id", index.toLong())
                            },
                        osmType = osmType,
                        osmId = osmId,
                        primaryName = primaryName,
                        formattedAddress = formattedAddress
                    )
                )
            }
        }
    }

    private fun providerFailure(
        responseCode: Int,
        errorBody: String
    ): NavigationDataException {
        val detail =
            errorBody
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_ERROR_DETAIL_LENGTH)

        return NavigationDataException(
            kind = FailureKind.PROVIDER,
            message = if (detail.isBlank()) {
                "Place search service returned HTTP $responseCode."
            } else {
                "Place search service returned HTTP $responseCode: $detail"
            }
        )
    }

    companion object {
        const val APP_USER_AGENT =
            "HyperNovaNavigation/1.0 " +
                "(com.hypernova.navigation; " +
                "contact: HyperNova ITI Android project)"

        private const val SEARCH_URL =
            "https://nominatim.openstreetmap.org/search"

        /*
         * A broad Cairo/Giza view box biases category searches around the
         * fixed ITI origin. bounded=0 keeps valid Egyptian searches outside
         * the box eligible.
         */
        private const val EGYPT_SEARCH_BIAS_VIEWBOX =
            "30.70,30.35,31.65,29.65"

        private const val RESULT_LIMIT = 8
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_ERROR_DETAIL_LENGTH = 180
    }
}
