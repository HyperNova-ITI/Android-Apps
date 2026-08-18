package com.hypernova.navigation

import com.hypernova.navigation.data.overpass.OverpassClient
import com.hypernova.navigation.data.overpass.OverpassRetryPolicy
import com.hypernova.navigation.domain.model.GeoPoint
import com.hypernova.navigation.domain.model.NearbyCategory
import com.hypernova.navigation.domain.model.PlaceProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverpassClientTest {
    private val client =
        OverpassClient(
            endpoints =
                listOf(
                    "https://example.invalid/api/interpreter"
                )
        )
    private val origin = GeoPoint(30.07112, 31.02075)

    @Test
    fun parse_readsNodeAndWayAndRelationCoordinates() {
        val places =
            client.parse(
                response =
                    """
                    {
                      "elements": [
                        {
                          "type": "node",
                          "id": 1,
                          "lat": 30.0715,
                          "lon": 31.0208,
                          "tags": {
                            "amenity": "cafe",
                            "name": "Node Cafe"
                          }
                        },
                        {
                          "type": "way",
                          "id": 2,
                          "center": {
                            "lat": 30.0720,
                            "lon": 31.0210
                          },
                          "tags": {
                            "amenity": "restaurant",
                            "name": "Way Restaurant"
                          }
                        },
                        {
                          "type": "relation",
                          "id": 3,
                          "center": {
                            "lat": 30.0730,
                            "lon": 31.0220
                          },
                          "tags": {
                            "amenity": "food_court",
                            "name": "Relation Food Court"
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                category = NearbyCategory.FOOD,
                origin = origin
            )

        assertEquals(3, places.size)
        assertEquals(
            setOf("node", "way", "relation"),
            places.map { it.osmType }.toSet()
        )
        assertTrue(
            places.all {
                it.provider == PlaceProvider.OVERPASS
            }
        )
    }

    @Test
    fun parse_ignoresMalformedElementsAndDeduplicatesIds() {
        val places =
            client.parse(
                response =
                    """
                    {
                      "elements": [
                        {
                          "type": "way",
                          "id": 10,
                          "tags": {"amenity": "fuel"}
                        },
                        {
                          "type": "node",
                          "id": 11,
                          "lat": "bad",
                          "lon": 31.0,
                          "tags": {"amenity": "fuel"}
                        },
                        {
                          "type": "node",
                          "id": 12,
                          "lat": 30.07,
                          "lon": 31.02,
                          "tags": {
                            "amenity": "fuel",
                            "name": "Real Fuel"
                          }
                        },
                        {
                          "type": "node",
                          "id": 12,
                          "lat": 30.07,
                          "lon": 31.02,
                          "tags": {
                            "amenity": "fuel",
                            "name": "Real Fuel"
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                category = NearbyCategory.FUEL,
                origin = origin
            )

        assertEquals(1, places.size)
        assertEquals("overpass:node:12", places.single().id)
    }

    @Test
    fun parse_buildsAddressAndSortsByHaversineDistance() {
        val places =
            client.parse(
                response =
                    """
                    {
                      "elements": [
                        {
                          "type": "node",
                          "id": 20,
                          "lat": 30.15,
                          "lon": 31.10,
                          "tags": {
                            "amenity": "clinic",
                            "name": "Far Clinic"
                          }
                        },
                        {
                          "type": "node",
                          "id": 21,
                          "lat": 30.0712,
                          "lon": 31.0208,
                          "tags": {
                            "amenity": "hospital",
                            "name": "Near Hospital",
                            "addr:housenumber": "22",
                            "addr:street": "Test Street",
                            "addr:suburb": "Smart Village",
                            "addr:city": "Sheikh Zayed",
                            "addr:state": "Giza"
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                category = NearbyCategory.HOSPITAL,
                origin = origin
            )

        assertEquals("Near Hospital", places.first().name)
        assertEquals(
            "22 Test Street, Smart Village, Sheikh Zayed, Giza",
            places.first().address
        )
        assertTrue(
            places.first().straightLineDistanceMeters!! <
                places.last().straightLineDistanceMeters!!
        )
    }

    @Test
    fun unnamedResult_usesTruthfulCategoryFallback() {
        val places =
            client.parse(
                response =
                    """
                    {
                      "elements": [
                        {
                          "type": "way",
                          "id": 30,
                          "center": {
                            "lat": 30.08,
                            "lon": 31.03
                          },
                          "tags": {
                            "shop": "supermarket"
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                category = NearbyCategory.SHOPPING,
                origin = origin
            )

        assertEquals(
            "Unnamed supermarket shop",
            places.single().name
        )
    }

    @Test
    fun buildAddress_leavesMissingTagsUnavailable() {
        assertEquals(
            "",
            client.buildAddress(JSONObject())
        )
    }

    @Test
    fun retryPolicy_classifiesOnly429And5xxAsRetryableHttp() {
        assertTrue(
            OverpassRetryPolicy.isRetryableHttpStatus(429)
        )
        assertTrue(
            OverpassRetryPolicy.isRetryableHttpStatus(500)
        )
        assertTrue(
            OverpassRetryPolicy.isRetryableHttpStatus(504)
        )
        assertTrue(
            !OverpassRetryPolicy.isRetryableHttpStatus(400)
        )
        assertTrue(
            !OverpassRetryPolicy.isRetryableHttpStatus(404)
        )
    }

    @Test
    fun retryPolicy_retriesPrimaryThenUsesFallbackOnce() {
        val plan =
            OverpassRetryPolicy.attemptPlan(
                listOf("primary-url", "fallback-url")
            )

        assertEquals(
            listOf(
                "primary" to 1,
                "primary" to 2,
                "fallback" to 1
            ),
            plan.map { it.endpointLabel to it.attemptNumber }
        )
        assertEquals(
            listOf(
                "primary-url",
                "primary-url",
                "fallback-url"
            ),
            plan.map { it.endpoint }
        )
    }

    @Test
    fun malformedQueryRemarkIsNotRetried() {
        assertTrue(
            !OverpassRetryPolicy.isRetryableRemark(
                "parse error: unexpected token"
            )
        )
        assertTrue(
            OverpassRetryPolicy.isRetryableRemark(
                "runtime error: Query timed out"
            )
        )
    }
}
