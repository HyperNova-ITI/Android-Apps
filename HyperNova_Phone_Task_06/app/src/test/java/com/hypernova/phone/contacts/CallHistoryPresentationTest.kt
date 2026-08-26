package com.hypernova.phone.contacts

import com.hypernova.phone.domain.CallNumberPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallHistoryPresentationTest {

    @Test
    fun `allowed usable number remains available for identity resolution`() {
        assertEquals(
            "+20 10 1234 5678",
            usableRecentNumber(
                "  +20 10 1234 5678  ",
                CallNumberPresentation.ALLOWED
            )
        )
    }

    @Test
    fun `restricted row never exposes retained provider number`() {
        assertNull(
            usableRecentNumber(
                "+20 10 1234 5678",
                CallNumberPresentation.RESTRICTED
            )
        )
    }

    @Test
    fun `unknown presentation never exposes retained provider number`() {
        assertNull(
            usableRecentNumber(
                "01012345678",
                CallNumberPresentation.UNKNOWN
            )
        )
    }
}
