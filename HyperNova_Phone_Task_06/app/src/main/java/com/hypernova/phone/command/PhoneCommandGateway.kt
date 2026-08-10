package com.hypernova.phone.command

import com.hypernova.phone.telecom.TelecomCallController

/** Internal, unexported command boundary reserved for a signature-protected NOVA integration. */
interface PhoneCommandGateway {
    fun callNumber(number: String): TelecomCallController.CommandResult
    fun answer(): TelecomCallController.CommandResult
    fun decline(): TelecomCallController.CommandResult
    fun end(): TelecomCallController.CommandResult
    fun hold(): TelecomCallController.CommandResult
    fun resume(): TelecomCallController.CommandResult
}

class PhoneCommandExecutor(private val telecom: TelecomCallController) : PhoneCommandGateway {
    override fun callNumber(number: String) = telecom.placeCall(number)
    override fun answer() = telecom.answer()
    override fun decline() = telecom.decline()
    override fun end() = telecom.disconnect()
    override fun hold() = telecom.hold()
    override fun resume() = telecom.unhold()
}
