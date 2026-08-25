package com.hypernova.ai.command

import com.hypernova.contracts.HyperNovaContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandWireCodecMediaTest {
    @Test
    fun playRadioCarriesOnlyTheBoundedCatalogQuery() {
        val request =
            CommandWireCodec.parseRequest(
                JSONObject()
                    .put("type", "command_request")
                    .put("v", HyperNovaContract.API_VERSION)
                    .put("request_id", "media-radio-1")
                    .put("domain", CommandWireCodec.DOMAIN_MEDIA)
                    .put("operation", MediaWireContract.OP_PLAY_RADIO)
                    .put("args", JSONObject().put("query", "something relaxing")),
            )

        assertEquals(MediaWireContract.OP_PLAY_RADIO, request.operation)
        assertEquals(
            CommandArguments.RadioQuery("something relaxing"),
            request.arguments,
        )
    }
}
