package com.hypernova.phone.telecom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.hypernova.phone.domain.CallStatus
import com.hypernova.phone.domain.TelecomCallState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Production Android Telecom controller for HyperNova Phone.
 *
 * Android Telecom remains the authoritative owner of call state.
 *
 * Supported real call controls:
 *
 * - Answer
 * - Decline
 * - Disconnect
 * - Hold / Resume
 * - DTMF
 * - Mute
 * - Speaker
 * - Explicit audio-route selection
 */
class TelecomCallController(
    private val context: Context
) {

    init {
        appContext =
            context.applicationContext
    }


    val state: StateFlow<TelecomCallState> =
        Companion.state.asStateFlow()

    private val dtmfHandler =
        Handler(Looper.getMainLooper())

    private var pendingDtmfStop:
        Runnable? = null

    fun canPlaceCalls(): Boolean {
        return telecomManager != null &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun placeCall(
        number: String
    ): CommandResult {

        if (number.isBlank()) {
            return CommandResult.Rejected(
                "Enter a valid phone number"
            )
        }

        if (!canPlaceCalls()) {
            return CommandResult.Rejected(
                "Phone access is required before calling"
            )
        }

        return try {

            telecomManager?.placeCall(
                Uri.fromParts(
                    "tel",
                    number,
                    null
                ),
                Bundle.EMPTY
            )

            Log.i(
                TAG,
                "Outgoing call handed to Android Telecom"
            )

            CommandResult.Dispatched

        } catch (
            security: SecurityException
        ) {

            Log.w(
                TAG,
                "Android Telecom rejected the call request"
            )

            CommandResult.Rejected(
                "Android Telecom rejected this call request"
            )

        } catch (
            exception: Exception
        ) {

            Log.e(
                TAG,
                "Could not hand call request to Android Telecom",
                exception
            )

            CommandResult.Rejected(
                "Phone service is unavailable"
            )
        }
    }

    fun answer(): CommandResult =
        withCall(
            "answer"
        ) {
            it.answer(0)
        }

    fun decline(): CommandResult =
        withCall(
            "decline"
        ) {
            it.reject(
                false,
                null
            )
        }

    fun disconnect(): CommandResult =
        withCall(
            "disconnect"
        ) {
            it.disconnect()
        }

    fun hold(): CommandResult =
        withCall(
            "hold"
        ) {
            it.hold()
        }

    fun unhold(): CommandResult =
        withCall(
            "resume"
        ) {
            it.unhold()
        }

    /**
     * Send one real DTMF digit through the current Telecom Call.
     *
     * Android requires every playDtmfTone() to be paired with
     * stopDtmfTone().
     */
    fun sendDtmf(
        digit: String
    ): CommandResult {

        val tone =
            digit.singleOrNull()

        if (
            tone == null ||
            tone !in VALID_DTMF_DIGITS
        ) {
            return CommandResult.Rejected(
                "Invalid DTMF digit"
            )
        }

        val call =
            currentCall
                ?: return CommandResult.Rejected(
                    "No active Telecom call"
                )

        if (
            state.value.status !in
            setOf(
                CallStatus.ACTIVE,
                CallStatus.HELD
            )
        ) {
            return CommandResult.Rejected(
                "DTMF is unavailable until the call is active"
            )
        }

        return try {

            /*
             * Ensure a previous tone cannot overlap the new one.
             */
            pendingDtmfStop?.let {
                dtmfHandler.removeCallbacks(it)

                runCatching {
                    call.stopDtmfTone()
                }
            }

            call.playDtmfTone(
                tone
            )

            val stopRunnable =
                Runnable {

                    runCatching {
                        call.stopDtmfTone()
                    }

                    pendingDtmfStop =
                        null

                    Log.i(
                        TAG,
                        "DTMF tone stopped"
                    )
                }

            pendingDtmfStop =
                stopRunnable

            dtmfHandler.postDelayed(
                stopRunnable,
                DTMF_DURATION_MS
            )

            Log.i(
                TAG,
                "DTMF '$tone' dispatched to Telecom"
            )

            CommandResult.Dispatched

        } catch (
            exception: Exception
        ) {

            Log.w(
                TAG,
                "DTMF command rejected by Telecom",
                exception
            )

            CommandResult.Rejected(
                "DTMF is unavailable for this call"
            )
        }
    }

    /**
     * Toggle microphone mute through the active InCallService.
     *
     * The state itself is updated only when Android reports the
     * resulting CallAudioState callback.
     */
    fun toggleMute():
        CommandResult {

        val service =
            HyperNovaInCallService
                .currentService
                ?: return CommandResult.Rejected(
                    "In-call audio service is unavailable"
                )

        return if (
            service.toggleMuteFromUi()
        ) {

            Log.i(
                TAG,
                "Mute toggle dispatched to Telecom"
            )

            CommandResult.Dispatched

        } else {

            CommandResult.Rejected(
                "Mute control is unavailable"
            )
        }
    }

    /**
     * Toggle speakerphone through Android Telecom audio routing.
     */
    fun toggleSpeaker():
        CommandResult {

        val service =
            HyperNovaInCallService
                .currentService
                ?: return CommandResult.Rejected(
                    "In-call audio service is unavailable"
                )

        return if (
            service.toggleSpeakerFromUi()
        ) {

            Log.i(
                TAG,
                "Speaker toggle dispatched to Telecom"
            )

            CommandResult.Dispatched

        } else {

            CommandResult.Rejected(
                "Speaker control is unavailable"
            )
        }
    }

    /**
     * Request a specific route returned by Android Telecom.
     *
     * The UI must never invent a route that Android did not report
     * as supported.
     */
    fun selectAudioRoute(
        route: Int
    ): CommandResult {

        val service =
            HyperNovaInCallService
                .currentService
                ?: return CommandResult.Rejected(
                    "In-call audio service is unavailable"
                )

        return if (
            service.selectAudioRouteFromUi(
                route
            )
        ) {

            Log.i(
                TAG,
                "Audio-route request dispatched to Telecom"
            )

            CommandResult.Dispatched

        } else {

            CommandResult.Rejected(
                "Requested audio route is unavailable"
            )
        }
    }

    private fun withCall(
        command: String,
        action: (Call) -> Unit
    ): CommandResult {

        val call =
            currentCall
                ?: return CommandResult.Rejected(
                    "No active Telecom call"
                )

        return try {

            action(call)

            Log.i(
                TAG,
                "$command command dispatched to Telecom"
            )

            CommandResult.Dispatched

        } catch (
            exception: Exception
        ) {

            Log.w(
                TAG,
                "$command command rejected by Telecom",
                exception
            )

            CommandResult.Rejected(
                "Call control is unavailable"
            )
        }
    }

    private val telecomManager:
        TelecomManager?
        get() =
            context.getSystemService(
                TelecomManager::class.java
            )

    sealed interface CommandResult {

        data object Dispatched :
            CommandResult

        data class Rejected(
            val reason: String
        ) : CommandResult
    }

    companion object {
        private var appContext:
            Context? = null


        private const val TAG =
            "HN-Telecom"

        private const val DTMF_DURATION_MS =
            180L

        private val VALID_DTMF_DIGITS =
            setOf(
                '0',
                '1',
                '2',
                '3',
                '4',
                '5',
                '6',
                '7',
                '8',
                '9',
                '*',
                '#'
            )

        private val state =
            MutableStateFlow(
                TelecomCallState()
            )

        private var currentCall:
            Call? = null

        private var callback:
            Call.Callback? = null

        internal fun onCallAdded(
            call: Call
        ) {

            currentCall?.let { old ->

                callback?.let {
                    old.unregisterCallback(
                        it
                    )
                }
            }

            currentCall =
                call

            callback =
                object :
                    Call.Callback() {

                    override fun onStateChanged(
                        call: Call,
                        stateValue: Int
                    ) {

                        publish(
                            call,
                            stateValue
                        )
                    }

                    override fun onDetailsChanged(
                        call: Call,
                        details: Call.Details
                    ) {

                        publish(
                            call,
                            call.state
                        )
                    }
                }.also {

                    call.registerCallback(
                        it
                    )
                }

            publish(
                call,
                call.state
            )

            Log.i(
                TAG,
                "Telecom call added"
            )
        }

        internal fun onCallRemoved(
            call: Call
        ) {

            callback?.let {
                call.unregisterCallback(
                    it
                )
            }

            if (
                currentCall ==
                call
            ) {
                currentCall =
                    null
            }

            callback =
                null

            state.value =
                state.value.copy(
                    status =
                        CallStatus.CALL_ENDED,

                    isMuted =
                        false,

                    canDisconnect =
                        false,

                    canAnswer =
                        false,

                    canHold =
                        false
                )

            Log.i(
                TAG,
                "Telecom call removed"
            )
        }

        /**
         * Called by HyperNovaInCallService when Android confirms the
         * real microphone mute state.
         */
        internal fun onCallAudioStateChanged(
            audioState: CallAudioState
        ) {

            state.value =
                state.value.copy(
                    isMuted =
                        audioState.isMuted
                )

            Log.i(
                TAG,
                "Telecom audio state: muted=${audioState.isMuted}, route=${audioState.route}, supported=${audioState.supportedRouteMask}"
            )
        }

        private fun publish(
            call: Call,
            callState: Int
        ) {

            val details =
                call.details

            val handle =
                details.handle
                    ?.schemeSpecificPart

            val mapped =
                when (
                    callState
                ) {

                    Call.STATE_NEW,
                    Call.STATE_CONNECTING,
                    Call.STATE_DIALING,
                    Call.STATE_SELECT_PHONE_ACCOUNT -> {
                        CallStatus.DIALING
                    }

                    Call.STATE_RINGING,
                    Call.STATE_SIMULATED_RINGING -> {

                        if (
                            details.callDirection ==
                            Call.Details.DIRECTION_INCOMING
                        ) {
                            CallStatus.INCOMING
                        } else {
                            CallStatus.RINGING
                        }
                    }

                    Call.STATE_ACTIVE -> {
                        CallStatus.ACTIVE
                    }

                    Call.STATE_HOLDING -> {
                        CallStatus.HELD
                    }

                    Call.STATE_DISCONNECTED,
                    Call.STATE_DISCONNECTING -> {
                        CallStatus.CALL_ENDED
                    }

                    else -> {
                        CallStatus.IDLE
                    }
                }

            val previous =
                state.value

            val activeSince =
                if (
                    mapped ==
                    CallStatus.ACTIVE &&
                    previous.startedAtMillis ==
                    null
                ) {

                    System.currentTimeMillis()

                } else {

                    previous.startedAtMillis
                }

            state.value =
                TelecomCallState(
                    status =
                        mapped,

                    displayName =
                    CallerIdentityResolver.resolve(
                        context =
                            appContext,
                        telecomDisplayName =
                            details.contactDisplayName
                                ?.toString()
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                                ?: details.callerDisplayName
                                    ?.toString(),
                        number =
                            handle
                    ),

                    number =
                        handle,

                    startedAtMillis =
                        activeSince,

                    /*
                     * Preserve the real audio state previously reported
                     * by InCallService.
                     */
                    isMuted =
                        previous.isMuted,

                    canHold =
                        details.can(
                            Call.Details.CAPABILITY_HOLD
                        ),

                    canAnswer =
                        mapped ==
                        CallStatus.INCOMING,

                    canDisconnect =
                        mapped !in
                        setOf(
                            CallStatus.IDLE,
                            CallStatus.CALL_ENDED
                        )
                )

            Log.i(
                TAG,
                "Telecom call transitioned to $mapped"
            )
        }

        private fun Call.Details.can(
            capability: Int
        ): Boolean {

            return (
                callCapabilities and
                    capability
                ) ==
                capability
        }
    }
}
