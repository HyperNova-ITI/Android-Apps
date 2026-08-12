package com.hypernova.ai.command

import android.content.Context
import com.hypernova.contracts.HyperNovaContract

class AndroidCommandExecutor(context: Context) : CommandExecutor {
    private val navigation = NavigationCommandClient(context)
    private val climate = ClimateCommandClient(context)
    private val phone = PhoneCommandClient(context)
    private val media = MediaCommandClient(context)

    override fun execute(request: CommandRequest, onResult: (CommandResult) -> Unit) {
        when (request.domain) {
            CommandWireCodec.DOMAIN_NAVIGATION -> navigation.execute(request, onResult)
            CommandWireCodec.DOMAIN_CLIMATE -> climate.execute(request, onResult)
            CommandWireCodec.DOMAIN_PHONE -> phone.execute(request, onResult)
            CommandWireCodec.DOMAIN_MEDIA -> media.execute(request, onResult)
            else -> onResult(
                request.failure(
                    status = CommandStatus.REJECTED,
                    message = "Unsupported command domain: ${request.domain}",
                    errorCode = HyperNovaContract.ERROR_UNSUPPORTED_OPERATION,
                ),
            )
        }
    }

    override fun shutdown() {
        navigation.shutdown()
        climate.shutdown()
        phone.shutdown()
        media.shutdown()
    }
}
