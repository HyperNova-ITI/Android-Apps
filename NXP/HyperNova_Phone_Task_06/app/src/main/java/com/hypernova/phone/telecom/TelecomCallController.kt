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
 * NXP HFP Client note:
 *
 * The remote mobile phone is represented in Android Telecom by
 * com.android.bluetooth.hfpclient.HfpClientConnectionService.
 *
 * Answering the Telecom Call changes only the call signalling state.
 * The Bluetooth HFP voice bearer (SCO) must also be explicitly requested
 * through the HfpClientConnection Call event used by AOSP:
 *
 *     com.android.bluetooth.hfpclient.SCO_CONNECT
 *
 * Without that event Telecom can correctly report MODE_IN_CALL and route
 * audio to the local speaker while no remote call audio actually enters
 * Android.
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
        Handler(
            Looper.getMainLooper()
        )

    private var pendingDtmfStop:
        Runnable? = null

    fun canPlaceCalls(): Boolean {

        return telecomManager != null &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE
            ) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun placeCall(
        number: String
    ): CommandResult {

        if (
            number.isBlank()
        ) {

            return CommandResult.Rejected(
                "Enter a valid phone number"
            )
        }

        if (
            !canPlaceCalls()
        ) {

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
                "Android Telecom rejected the call request",
                security
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

    fun answer():
        CommandResult =
        withCall(
            "answer"
        ) { call ->

            /*
             * Answer signalling first.
             *
             * SCO is deliberately requested after Android reports ACTIVE.
             * That avoids racing the Bluetooth HFP AG while the remote
             * handset is still transitioning out of RINGING.
             */
            call.answer(
                0
            )
        }

    fun decline():
        CommandResult =
        withCall(
            "decline"
        ) { call ->

            call.reject(
                false,
                null
            )
        }

    fun disconnect():
        CommandResult =
        withCall(
            "disconnect"
        ) { call ->

            /*
             * HfpClientConnection ignores call events after it has already
             * closed, therefore request SCO teardown before disconnecting
             * the Telecom call.
             */
            requestHfpScoDisconnect(
                call
            )

            call.disconnect()
        }

    fun hold():
        CommandResult =
        withCall(
            "hold"
        ) { call ->

            call.hold()
        }

    fun unhold():
        CommandResult =
        withCall(
            "resume"
        ) { call ->

            call.unhold()
        }

    /**
     * Send one real DTMF digit through the current Telecom Call.
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

            pendingDtmfStop?.let { pending ->

                dtmfHandler.removeCallbacks(
                    pending
                )

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

            action(
                call
            )

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
        ) :
            CommandResult
    }

    companion object {

        private var appContext:
            Context? = null

        private const val TAG =
            "HN-Telecom"

        private const val DTMF_DURATION_MS =
            180L

        /*
         * AOSP Bluetooth HFP Client Connection events.
         *
         * HfpClientConnection.onCallEvent() consumes these and forwards
         * them to HeadsetClientService.connectAudio()/disconnectAudio().
         */
        private const val HFP_SCO_CONNECT_EVENT =
            "com.android.bluetooth.hfpclient.SCO_CONNECT"

        private const val HFP_SCO_DISCONNECT_EVENT =
            "com.android.bluetooth.hfpclient.SCO_DISCONNECT"

        private const val HFP_CLIENT_PACKAGE =
            "com.android.bluetooth"

        private const val HFP_CLIENT_CONNECTION_SERVICE =
            "com.android.bluetooth.hfpclient.HfpClientConnectionService"

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

        /*
         * This flag avoids firing SCO_CONNECT repeatedly for every
         * Telecom callback belonging to the same active call.
         */
        private var hfpScoConnectRequested =
            false

        internal fun onCallAdded(
            call: Call
        ) {

            currentCall?.let { old ->

                callback?.let { oldCallback ->

                    old.unregisterCallback(
                        oldCallback
                    )
                }
            }

            currentCall =
                call

            hfpScoConnectRequested =
                false

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

                        /*
                         * This is the missing NXP HFP Client audio step.
                         *
                         * Telecom reporting ACTIVE means HFP call signalling
                         * has completed. Now request the SCO voice bearer.
                         */
                        if (
                            stateValue ==
                            Call.STATE_ACTIVE
                        ) {

                            requestHfpScoConnect(
                                call
                            )
                        }

                        if (
                            stateValue in
                            setOf(
                                Call.STATE_DISCONNECTING,
                                Call.STATE_DISCONNECTED
                            )
                        ) {

                            requestHfpScoDisconnect(
                                call
                            )
                        }
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
                }.also { callCallback ->

                    call.registerCallback(
                        callCallback
                    )
                }

            publish(
                call,
                call.state
            )

            /*
             * Covers calls which are already ACTIVE when this InCallService
             * becomes connected.
             */
            if (
                call.state ==
                Call.STATE_ACTIVE
            ) {

                requestHfpScoConnect(
                    call
                )
            }

            Log.i(
                TAG,
                "Telecom call added; " +
                    "hfpClient=${isHfpClientCall(call)}"
            )
        }

        internal fun onCallRemoved(
            call: Call
        ) {

            /*
             * Best-effort cleanup.
             *
             * A remotely terminated HfpClientConnection may already be
             * closed here; in that case the Bluetooth service simply
             * ignores the event.
             */
            requestHfpScoDisconnect(
                call
            )

            callback?.let { registeredCallback ->

                runCatching {

                    call.unregisterCallback(
                        registeredCallback
                    )
                }
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

            hfpScoConnectRequested =
                false

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
                "Telecom audio state: " +
                    "muted=${audioState.isMuted}, " +
                    "route=${audioState.route}, " +
                    "supported=${audioState.supportedRouteMask}"
            )
        }

        /**
         * Request the Bluetooth HFP Client SCO bearer for the current call.
         *
         * This does NOT select a Bluetooth output endpoint in Telecom.
         *
         * The HFP Client connection is the source of the remote telephone
         * voice stream. Telecom may still correctly report SPEAKER as the
         * local playback endpoint.
         */
        private fun requestHfpScoConnect(
            call: Call
        ) {

            if (
                !isHfpClientCall(
                    call
                )
            ) {

                return
            }

            if (
                hfpScoConnectRequested
            ) {

                return
            }

            try {

                call.sendCallEvent(
                    HFP_SCO_CONNECT_EVENT,
                    Bundle.EMPTY
                )

                hfpScoConnectRequested =
                    true

                Log.i(
                    TAG,
                    "HFP SCO connect event dispatched"
                )

            } catch (
                exception: Exception
            ) {

                Log.e(
                    TAG,
                    "Failed to request HFP SCO audio",
                    exception
                )
            }
        }

        private fun requestHfpScoDisconnect(
            call: Call
        ) {

            if (
                !isHfpClientCall(
                    call
                )
            ) {

                hfpScoConnectRequested =
                    false
                return
            }

            /*
             * Send this even when our local flag is false. It is a harmless
             * best-effort cleanup and protects against a process/state race.
             */
            try {

                call.sendCallEvent(
                    HFP_SCO_DISCONNECT_EVENT,
                    Bundle.EMPTY
                )

                Log.i(
                    TAG,
                    "HFP SCO disconnect event dispatched"
                )

            } catch (
                exception: Exception
            ) {

                Log.w(
                    TAG,
                    "Failed to request HFP SCO disconnect",
                    exception
                )
            } finally {

                hfpScoConnectRequested =
                    false
            }
        }

        private fun isHfpClientCall(
            call: Call
        ): Boolean {

            val account =
                call.details
                    .accountHandle
                    ?: return false

            val component =
                account.componentName

            return component.packageName ==
                HFP_CLIENT_PACKAGE &&
                component.className ==
                HFP_CLIENT_CONNECTION_SERVICE
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
                     * Preserve the real mute state last reported by
                     * HyperNovaInCallService.
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
