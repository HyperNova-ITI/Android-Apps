package com.hypernova.navigation

import com.hypernova.navigation.data.persistence.NavigationPreferenceContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPreferenceContractTest {

    @Test
    fun localThemeIsRetiredAndNotPersisted() {
        assertFalse(
            NavigationPreferenceContract.currentPersistedKeys
                .contains(
                    NavigationPreferenceContract
                        .LEGACY_LOCAL_THEME
                )
        )
        assertTrue(
            NavigationPreferenceContract.currentPersistedKeys
                .contains(
                    NavigationPreferenceContract
                        .HOME_DESTINATION
                )
        )
    }
}
