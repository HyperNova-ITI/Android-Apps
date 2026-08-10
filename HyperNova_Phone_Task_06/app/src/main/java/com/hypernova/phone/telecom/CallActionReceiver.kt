package com.hypernova.phone.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives user actions from HyperNova call notifications.
 *
 * This receiver never changes UI state directly.
 * Every call command is routed through TelecomCallController,
 * and the UI/notification state changes only after Android Telecom
 * reports the real call state back through InCallService.
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val controller = TelecomCallController(context.applicationContext)

        val result = when (intent.action) {
            ACTION_ANSWER_CALL -> {
                Log.i(TAG, "Incoming-call notification requested ANSWER")
                controller.answer()
            }

            ACTION_DECLINE_CALL -> {
                Log.i(TAG, "Incoming-call notification requested DECLINE")
                controller.decline()
            }

            ACTION_END_CALL -> {
                Log.i(TAG, "Call notification requested END")
                controller.disconnect()
            }

            else -> {
                Log.w(TAG, "Ignoring unknown call notification action")
                return
            }
        }

        when (result) {
            TelecomCallController.CommandResult.Dispatched ->
                Log.i(TAG, "Call notification command dispatched to Telecom")

            is TelecomCallController.CommandResult.Rejected ->
                Log.w(TAG, "Call notification command rejected by Telecom")
        }
    }

    companion object {
        private const val TAG = "HN-CallAction"

        const val ACTION_ANSWER_CALL =
            "com.hypernova.phone.action.ANSWER_CALL"

        const val ACTION_DECLINE_CALL =
            "com.hypernova.phone.action.DECLINE_CALL"

        const val ACTION_END_CALL =
            "com.hypernova.phone.action.END_CALL"
    }
}
