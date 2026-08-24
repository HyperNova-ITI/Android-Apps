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
        withGatedCall(
            "answer"
        ) { call ->

            TelecomCallPolicy.canAnswer(
                call.state,
                call.details.callDirection
            )
        }?.let { call ->

            /*
             * Answer signalling first.
             *
             * SCO is deliberately requested after Android reports ACTIVE.
             * That avoids racing the Bluetooth HFP AG while the remote
             * handset is still transitioning out of RINGING.
             */
            runTelecomCommand(
                "answer"
            ) {

                call.answer(
                    0
                )
            }
        } ?: CommandResult.Rejected(
            "No incoming call to answer"
        )

    fun decline():
        CommandResult =
        withGatedCall(
            "decline"
        ) { call ->

            TelecomCallPolicy.canDecline(
                call.state,
                call.details.callDirection
            )
        }?.let { call ->

            runTelecomCommand(
                "decline"
            ) {

                call.reject(
                    false,
                    null
                )
            }
        } ?: CommandResult.Rejected(
            "No incoming call to decline"
        )

    fun disconnect():
        CommandResult =
        withGatedCall(
            "disconnect"
        ) { call ->

            TelecomCallPolicy.canDisconnect(
                call.state
            )
        }?.let { call ->

            runTelecomCommand(
                "disconnect"
            ) {

                /*
                 * HfpClientConnection ignores call events after it has
                 * already closed, therefore request SCO teardown before
                 * disconnecting the Telecom call.
                 */
                requestHfpScoDisconnect(
                    call
                )

                call.disconnect()
            }
        } ?: CommandResult.Rejected(
            "No active Telecom call"
        )

    fun hold():
        CommandResult =
        withGatedCall(
            "hold"
        ) { call ->

            TelecomCallPolicy.canHoldNow(
                call.state,
                call.details.callCapabilities
            )
        }?.let { call ->

            runTelecomCommand(
                "hold"
            ) {

                call.hold()
            }
        } ?: CommandResult.Rejected(
            "Hold is not available for this call"
        )

    fun unhold():
        CommandResult =
        withGatedCall(
            "resume"
        ) { call ->

            TelecomCallPolicy.canUnholdNow(
                call.state,
                call.details.callCapabilities
            )
        }?.let { call ->

            runTelecomCommand(
                "resume"
            ) {

                call.unhold()
            }
        } ?: CommandResult.Rejected(
            "Resume is not available for this call"
        )

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
            !TelecomCallPolicy.isValidDtmf(tone)
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

        return runTelecomCommand(
            "DTMF"
        ) {

            cancelPendingDtmf()

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

    /**
     * Return the tracked call only when it satisfies the real
     * Telecom state/capability gate for the requested command.
     */
    private fun withGatedCall(
        command: String,
        gate: (Call) -> Boolean
    ): Call? {

        val call =
            currentCall
                ?: return null

        val allowed =
            try {

                gate(call)

            } catch (
                exception: Exception
            ) {

                Log.w(
                    TAG,
                    "$command gate could not read Telecom state",
                    exception
                )

                false
            }

        if (
            !allowed
        ) {

            Log.w(
                TAG,
                "$command rejected by state/capability gating"
            )
        }

        return if (allowed) call else null
    }

    private fun runTelecomCommand(
        command: String,
        action: () -> Unit
    ): CommandResult {

        return try {

            action()

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

        private val state =
            MutableStateFlow(
                TelecomCallState()
            )

        private var currentCall:
            Call? = null

        /*
         * One callback per delivered Call.
         *
         * A shared single callback reference broke bookkeeping whenever
         * Telecom removed a call that was not the currently tracked one:
         * the reference was nulled while still registered on the live
         * call, leaking it and making later unregistration impossible.
         */
        private val trackedCallbacks =
            mutableMapOf<Call, Call.Callback>()

        private val dtmfHandler =
            Handler(
                Looper.getMainLooper()
            )

        private var pendingDtmfStop:
            Runnable? = null

        /*
         * This flag avoids firing SCO_CONNECT repeatedly for every
         * Telecom callback belonging to the same active call.
         */
        private var hfpScoConnectRequested =
            false

        /**
         * Stop any in-flight DTMF tone and drop its pending stop
         * runnable so a removed call is never touched by stale
         * handler messages.
         */
        private fun cancelPendingDtmf() {

            pendingDtmfStop?.let { pending ->

                dtmfHandler.removeCallbacks(
                    pending
                )
            }

            currentCall?.let { call ->

                runCatching {
                    call.stopDtmfTone()
                }
            }

            pendingDtmfStop =
                null
        }

        internal fun onCallAdded(
            call: Call
        ) {

            currentCall =
                call

            hfpScoConnectRequested =
                false

            val callCallback =
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

                        /*
                         * Leaving ACTIVE tears down or suspends the SCO
                         * bearer on NXP HFP Client. Reset the guard here so
                         * HELD -> ACTIVE re-requests SCO_CONNECT instead of
                         * being suppressed by the previous active period.
                         */
                        if (
                            stateValue ==
                            Call.STATE_HOLDING
                        ) {

                            hfpScoConnectRequested =
                                false
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
                }

            try {

                call.registerCallback(
                    callCallback
                )

                trackedCallbacks[call] =
                    callCallback

            } catch (
                exception: Exception
            ) {

                Log.w(
                    TAG,
                    "Could not observe Telecom call callbacks",
                    exception
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

            trackedCallbacks.remove(call)?.let { registeredCallback ->

                runCatching {

                    call.unregisterCallback(
                        registeredCallback
                    )
                }
            }

            val wasTrackedCall =
                currentCall ==
                call

            var promotedCall:
                Call? = null

            if (
                wasTrackedCall
            ) {

                cancelPendingDtmf()

                /*
                 * Deterministically promote the most recently delivered
                 * remaining call so commands keep targeting a live call
                 * and published state never goes stale behind Telecom.
                 */
                promotedCall =
                    trackedCallbacks.keys.lastOrNull()

                currentCall =
                    promotedCall
            }

            if (
                trackedCallbacks.isEmpty()
            ) {

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

            } else {

                promotedCall?.let { promoted ->

                    publish(
                        promoted,
                        promoted.state
                    )

                    /*
                     * Mirror onCallAdded: an already-ACTIVE promoted call
                     * must own the SCO bearer request lifecycle.
                     */
                    if (
                        promoted.state ==
                        Call.STATE_ACTIVE
                    ) {

                        requestHfpScoConnect(
                            promoted
                        )
                    }
                }
            }

            Log.i(
                TAG,
                "Telecom call removed; " +
                    "tracked=${trackedCallbacks.size}"
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
                TelecomCallPolicy.mapStatus(
                    callState,
                    details.callDirection
                )

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

                    /*
                     * Real Telecom hold support: CAPABILITY_HOLD or
                     * CAPABILITY_SUPPORT_HOLD.
                     */
                    canHold =
                        TelecomCallPolicy.canHold(
                            details.callCapabilities
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
    }
}
