package com.hypernova.ai.command

import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.navigation.NavigationContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandWireCodecNavigationTest {
    @Test
    fun startNavigationHasNoDestinationArgument() {
        val request =
            CommandWireCodec.parseRequest(
                JSONObject()
                    .put("type", "command_request")
                    .put("v", HyperNovaContract.API_VERSION)
                    .put("request_id", "nav-start-1")
                    .put("domain", CommandWireCodec.DOMAIN_NAVIGATION)
                    .put("operation", NavigationContract.OP_START_NAVIGATION)
                    .put("args", JSONObject()),
            )

        assertEquals(NavigationContract.OP_START_NAVIGATION, request.operation)
        assertTrue(request.arguments is CommandArguments.None)
    }
}
