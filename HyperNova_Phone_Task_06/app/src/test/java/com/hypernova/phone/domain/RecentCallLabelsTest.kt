package com.hypernova.phone.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentCallLabelsTest {
    @Test fun savedContactUsesName() {
        assertEquals("Ahmed", RecentCallLabels.primary("Ahmed", "+201001234567", CallNumberPresentation.ALLOWED))
    }

    @Test fun unsavedContactUsesRealNumber() {
        assertEquals("+201001234567", RecentCallLabels.primary(null, "+201001234567", CallNumberPresentation.ALLOWED))
    }

    @Test fun unavailableNumberUsesHonestProviderState() {
        assertEquals("Private number", RecentCallLabels.primary(null, null, CallNumberPresentation.PRIVATE))
    }
}
