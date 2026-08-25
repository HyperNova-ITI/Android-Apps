package com.hypernova.navigation.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleMapsBridgeCodecTest {
    @Test
    fun interactiveMapDestinationIsValidatedBeforeEnteringNavigationRuntime() {
        val value =
            GoogleMapsBridgeCodec.parseMapDestination(
                """{
                    "placeId":"ChIJ_demo",
                    "title":"Smart Village",
                    "subtitle":"Cairo - Alexandria Desert Road",
                    "category":"Business park",
                    "latitude":30.07112,
                    "longitude":31.02075
                }""".trimIndent(),
            )

        assertEquals("ChIJ_demo", value.placeId)
        assertEquals("Smart Village", value.title)
        assertEquals(30.07112, value.latitude!!, 0.0)
    }

    @Test
    fun invalidInteractiveMapDestinationIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleMapsBridgeCodec.parseMapDestination(
                """{"placeId":"valid","title":"","latitude":300,"longitude":31}""",
            )
        }
    }

    @Test
    fun destinationResultsAreValidatedAndBoundedToFrozenMaximum() {
        val payload =
            """[
                {"placeId":"one","title":"One","subtitle":"A","category":"Cafe","latitude":30.1,"longitude":31.1},
                {"placeId":"","title":"Invalid","latitude":30.2,"longitude":31.2},
                {"placeId":"two","title":"Two","latitude":30.2,"longitude":31.2},
                {"placeId":"three","title":"Three","latitude":30.3,"longitude":31.3},
                {"placeId":"four","title":"Four","latitude":30.4,"longitude":31.4},
                {"placeId":"five","title":"Five","latitude":30.5,"longitude":31.5}
            ]""".trimIndent()

        val values = GoogleMapsBridgeCodec.parseDestinations(payload)

        assertEquals(listOf("one", "two", "three", "four"), values.map { it.placeId })
        assertEquals("Cafe", values.first().category)
    }

    @Test
    fun routePayloadProducesRealMetricsAndGeometry() {
        val route =
            GoogleMapsBridgeCodec.parseRoute(
                """{
                    "points":[
                        {"latitude":30.07112,"longitude":31.02075},
                        {"latitude":30.07873,"longitude":31.01791}
                    ],
                    "etaSeconds":420,
                    "distanceMeters":5100
                }""".trimIndent(),
            )

        assertEquals(2, route.points.size)
        assertEquals(420L, route.etaSeconds)
        assertEquals(5100L, route.distanceMeters)
    }

    @Test
    fun malformedRouteCannotBeReportedAsReady() {
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                GoogleMapsBridgeCodec.parseRoute(
                    """{"points":[{"latitude":300,"longitude":31}],"etaSeconds":10}""",
                )
            }

        assertTrue(failure.message.orEmpty().contains("geometry"))
    }

    @Test
    fun placeContactRequiresARealProviderPhoneNumber() {
        val contact = GoogleMapsBridgeCodec.parseContact(
            """{"displayName":"Burger and fries B&F","phoneNumber":"+20 100 123 4567"}""",
        )

        assertEquals("Burger and fries B&F", contact.displayName)
        assertEquals("+20 100 123 4567", contact.phoneNumber)
        assertThrows(IllegalArgumentException::class.java) {
            GoogleMapsBridgeCodec.parseContact(
                """{"displayName":"No Phone Cafe","phoneNumber":""}""",
            )
        }
    }
}
