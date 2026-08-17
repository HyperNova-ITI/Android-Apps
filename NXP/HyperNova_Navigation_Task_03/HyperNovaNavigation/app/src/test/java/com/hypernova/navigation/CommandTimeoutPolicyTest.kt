package com.hypernova.navigation

import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.service.CommandTimeoutPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandTimeoutPolicyTest {
    @Test
    fun timeoutBehavior_usesFrozenContractTargets() {
        assertEquals(
            NavigationContract.SEARCH_TIMEOUT_MILLIS,
            CommandTimeoutPolicy.timeoutMillis(
                NavigationContract.OP_SEARCH_DESTINATIONS
            )
        )
        assertEquals(
            NavigationContract.ROUTE_TIMEOUT_MILLIS,
            CommandTimeoutPolicy.timeoutMillis(
                NavigationContract.OP_SET_DESTINATION
            )
        )
        assertNull(
            CommandTimeoutPolicy.timeoutMillis(
                NavigationContract.OP_CANCEL_NAVIGATION
            )
        )
    }
}
