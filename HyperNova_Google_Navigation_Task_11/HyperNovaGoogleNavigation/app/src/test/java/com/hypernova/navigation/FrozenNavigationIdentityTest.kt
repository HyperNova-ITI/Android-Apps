package com.hypernova.navigation

import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.navigation.NavigationContract
import org.junit.Assert.assertEquals
import org.junit.Test

class FrozenNavigationIdentityTest {
    @Test
    fun frozenIdentityMatchesExistingClients() {
        assertEquals(1, HyperNovaContract.API_VERSION)
        assertEquals("com.hypernova.navigation", NavigationContract.PACKAGE_NAME)
        assertEquals(
            "com.hypernova.navigation.service.NavigationCommandService",
            NavigationContract.COMMAND_SERVICE,
        )
        assertEquals(
            "com.hypernova.navigation.action.BIND_COMMAND",
            NavigationContract.BIND_COMMAND_ACTION,
        )
        assertEquals(
            "com.hypernova.navigation.action.OPEN",
            NavigationContract.OPEN_ACTION,
        )
    }
}

