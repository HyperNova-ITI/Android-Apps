package com.hypernova.navigation.ui

import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.model.GoogleDestinationRecord
import com.hypernova.navigation.persistence.DestinationTokenEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DestinationIntentMatcherTest {
    @Test
    fun `exact multilingual title selects the intended card`() {
        val expected = entry("target", "تاير برو TyrePro Continental Egypt")
        val selected = DestinationIntentMatcher.select(
            "تاير برو TyrePro Continental Egypt",
            listOf(expected, entry("other", "Pit Stop Smart Village")),
        )

        assertEquals(expected, selected)
    }

    @Test
    fun `one unambiguous Google result can be opened`() {
        val expected = entry("one", "TyrePro Continental Egypt - Smart Village")

        assertEquals(
            expected,
            DestinationIntentMatcher.select("TyrePro Continental Egypt", listOf(expected)),
        )
    }

    @Test
    fun `ambiguous unrelated candidates stay for user selection`() {
        assertNull(
            DestinationIntentMatcher.select(
                "Nacita Mobility",
                listOf(entry("one", "Pit Stop"), entry("two", "Handler Auto Service")),
            ),
        )
    }

    private fun entry(token: String, title: String) =
        DestinationTokenEntry(
            token = token,
            source = NavigationContract.SOURCE_SEARCH,
            record = GoogleDestinationRecord(
                placeId = token,
                title = title,
                subtitle = "Smart Village",
                category = "Vehicle service",
                latitude = 30.07112,
                longitude = 31.02075,
            ),
            createdAtMillis = 0L,
            expiresAtMillis = Long.MAX_VALUE,
        )
}
