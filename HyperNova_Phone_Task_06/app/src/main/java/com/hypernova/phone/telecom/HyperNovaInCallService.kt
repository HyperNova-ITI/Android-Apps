package com.hypernova.phone.telecom

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.util.Log
import com.hypernova.phone.MainActivity
import com.hypernova.phone.domain.CallStatus
import com.hypernova.phone.domain.TelecomCallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * HyperNova Android Telecom InCallService.
 *
 * Production responsibilities:
 *
 * - Receive real Android Telecom calls.
 * - Forward Call objects to TelecomCallController.
 * - Present incoming-call HUN.
 * - Bring HyperNova Phone forward when the call becomes ACTIVE.
 * - Control real in-call microphone mute.
 * - Control real Telecom audio routing.
 * - Publish confirmed CallAudioState changes back to the controller.
 *
 * Android Telecom remains authoritative for both call and audio state.
 */
class HyperNovaInCallService : InCallService() {

    private val serviceScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Main.immediate
        )

    private lateinit var telecomCallController:
        TelecomCallController

    private lateinit var incomingCallNotifier:
        IncomingCallNotifier

    private var lastObservedStatus:
        CallStatus = CallStatus.IDLE

    override fun onCreate() {
        super.onCreate()

        currentService = this

        telecomCallController =
            TelecomCallController(
                applicationContext
            )

        incomingCallNotifier =
            IncomingCallNotifier(
                applicationContext
            )

        observeTelecomState()

        /*
         * Seed the mute state Telecom already holds so a service
         * rebinding during a muted call does not present a stale
         * default until the next onCallAudioStateChanged() arrives.
         */
        callAudioState?.let(
            TelecomCallController::onCallAudioStateChanged
        )

        Log.i(
            TAG,
            "InCallService created"
        )
    }

    override fun onCallAdded(
        call: Call
    ) {
        super.onCallAdded(call)

        Log.i(
            TAG,
            "Telecom delivered a call to HyperNova"
        )

        TelecomCallController.onCallAdded(
            call
        )
    }

    override fun onCallRemoved(
        call: Call
    ) {
        Log.i(
            TAG,
            "Telecom removed a call from HyperNova"
        )

        TelecomCallController.onCallRemoved(
            call
        )

        incomingCallNotifier
            .cancelIncomingCall()

        lastObservedStatus =
            CallStatus.CALL_ENDED

        super.onCallRemoved(call)
    }

    /**
     * Android Telecom calls this whenever the real call-audio state changes.
     *
     * Examples:
     *
     * - microphone muted/unmuted
     * - speaker enabled
     * - earpiece selected
     * - Bluetooth selected
     * - wired headset selected
     */
    override fun onCallAudioStateChanged(
        audioState: CallAudioState
    ) {
        super.onCallAudioStateChanged(
            audioState
        )

        Log.i(
            TAG,
            "CallAudioState changed: muted=${audioState.isMuted}, " +
                "route=${routeName(audioState.route)}, " +
                "supported=${supportedRoutesDescription(audioState.supportedRouteMask)}"
        )

        TelecomCallController
            .onCallAudioStateChanged(
                audioState
            )
    }

    /**
     * Telecom requests the selected Dialer's active-call UI.
     */
    override fun onBringToForeground(
        showDialpad: Boolean
    ) {
        super.onBringToForeground(
            showDialpad
        )

        Log.i(
            TAG,
            "Telecom requested HyperNova in-call UI foreground; " +
                "showDialpad=$showDialpad"
        )

        val intent =
            Intent(
                this,
                MainActivity::class.java
            ).apply {

                action =
                    ACTION_OPEN_PHONE

                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )

                putExtra(
                    EXTRA_SHOW_IN_CALL_DIALPAD,
                    showDialpad
                )
            }

        try {

            startActivity(
                intent
            )

            Log.i(
                TAG,
                "HyperNova Phone activity requested for active call"
            )

        } catch (
            exception: RuntimeException
        ) {

            Log.e(
                TAG,
                "Could not open HyperNova active-call UI",
                exception
            )
        }
    }

    /**
     * Toggle the real in-call microphone state.
     *
     * Do not update UI optimistically here.
     *
     * Android confirms the result through onCallAudioStateChanged(),
     * and that confirmed state is then published through
     * TelecomCallController.
     */
    internal fun toggleMuteFromUi():
        Boolean {

        val current =
            callAudioState
                ?: run {

                    Log.w(
                        TAG,
                        "Cannot toggle mute: CallAudioState unavailable"
                    )

                    return false
                }

        val requestedMuted =
            !current.isMuted

        return try {

            setMuted(
                requestedMuted
            )

            Log.i(
                TAG,
                "Requested microphone muted=$requestedMuted"
            )

            true

        } catch (
            exception: RuntimeException
        ) {

            Log.e(
                TAG,
                "Mute request failed",
                exception
            )

            false
        }
    }

    /**
     * Toggle between SPEAKER and the best non-speaker route currently
     * reported by Android Telecom.
     *
     * No unsupported audio route is invented.
     */
    internal fun toggleSpeakerFromUi():
        Boolean {

        val current =
            callAudioState
                ?: run {

                    Log.w(
                        TAG,
                        "Cannot toggle speaker: CallAudioState unavailable"
                    )

                    return false
                }

        val targetRoute =
            if (
                current.route ==
                    CallAudioState.ROUTE_SPEAKER
            ) {

                chooseBestNonSpeakerRoute(
                    current.supportedRouteMask
                )

            } else {

                if (
                    supportsRoute(
                        current.supportedRouteMask,
                        CallAudioState.ROUTE_SPEAKER
                    )
                ) {
                    CallAudioState.ROUTE_SPEAKER
                } else {
                    null
                }
            }

        if (
            targetRoute ==
            null
        ) {

            Log.w(
                TAG,
                "No valid speaker toggle route is available"
            )

            return false
        }

        return requestAudioRoute(
            current,
            targetRoute
        )
    }

    /**
     * Select one real audio route explicitly.
     *
     * This will later be used by the AUDIO button.
     */
    internal fun selectAudioRouteFromUi(
        route: Int
    ): Boolean {

        val current =
            callAudioState
                ?: run {

                    Log.w(
                        TAG,
                        "Cannot select audio route: CallAudioState unavailable"
                    )

                    return false
                }

        if (
            !isConcreteRoute(
                route
            )
        ) {

            Log.w(
                TAG,
                "Rejected invalid Telecom audio route=$route"
            )

            return false
        }

        if (
            !supportsRoute(
                current.supportedRouteMask,
                route
            )
        ) {

            Log.w(
                TAG,
                "Requested route ${routeName(route)} is not supported"
            )

            return false
        }

        return requestAudioRoute(
            current,
            route
        )
    }

    @Suppress("DEPRECATION")
    private fun requestAudioRoute(
        current: CallAudioState,
        route: Int
    ): Boolean {

        if (
            current.route ==
            route
        ) {

            Log.i(
                TAG,
                "Audio route ${routeName(route)} is already active"
            )

            return true
        }

        return try {

            /*
             * setAudioRoute() remains useful for the current standalone
             * Android validation path.
             *
             * When the same application is integrated into the final AAOS
             * system image we can move the routing UI to the newer
             * CallEndpoint APIs where required by the platform.
             */
            setAudioRoute(
                route
            )

            Log.i(
                TAG,
                "Requested Telecom audio route: ${routeName(route)}"
            )

            true

        } catch (
            exception: RuntimeException
        ) {

            Log.e(
                TAG,
                "Audio-route request failed",
                exception
            )

            false
        }
    }

    /**
     * Pick the best available route when turning speaker OFF.
     *
     * Preference:
     *
     * Bluetooth
     *     ↓
     * Wired headset
     *     ↓
     * Earpiece
     *
     * On the final AAOS target the available Telecom endpoints will be
     * supplied by the automotive Bluetooth/audio platform.
     */
    private fun chooseBestNonSpeakerRoute(
        supportedRouteMask: Int
    ): Int? {

        val preferredRoutes =
            listOf(
                CallAudioState.ROUTE_BLUETOOTH,
                CallAudioState.ROUTE_WIRED_HEADSET,
                CallAudioState.ROUTE_EARPIECE
            )

        return preferredRoutes
            .firstOrNull { route ->

                supportsRoute(
                    supportedRouteMask,
                    route
                )
            }
    }

    private fun supportsRoute(
        supportedRouteMask: Int,
        route: Int
    ): Boolean {

        return (
            supportedRouteMask and
                route
            ) ==
            route
    }

    private fun isConcreteRoute(
        route: Int
    ): Boolean {

        return route in
            setOf(
                CallAudioState.ROUTE_EARPIECE,
                CallAudioState.ROUTE_BLUETOOTH,
                CallAudioState.ROUTE_WIRED_HEADSET,
                CallAudioState.ROUTE_SPEAKER
            )
    }

    private fun routeName(
        route: Int
    ): String {

        return when (
            route
        ) {

            CallAudioState.ROUTE_EARPIECE ->
                "EARPIECE"

            CallAudioState.ROUTE_BLUETOOTH ->
                "BLUETOOTH"

            CallAudioState.ROUTE_WIRED_HEADSET ->
                "WIRED_HEADSET"

            CallAudioState.ROUTE_SPEAKER ->
                "SPEAKER"

            CallAudioState.ROUTE_WIRED_OR_EARPIECE ->
                "WIRED_OR_EARPIECE"

            else ->
                "UNKNOWN($route)"
        }
    }

    private fun supportedRoutesDescription(
        mask: Int
    ): String {

        val routes =
            mutableListOf<String>()

        if (
            supportsRoute(
                mask,
                CallAudioState.ROUTE_EARPIECE
            )
        ) {
            routes +=
                "EARPIECE"
        }

        if (
            supportsRoute(
                mask,
                CallAudioState.ROUTE_BLUETOOTH
            )
        ) {
            routes +=
                "BLUETOOTH"
        }

        if (
            supportsRoute(
                mask,
                CallAudioState.ROUTE_WIRED_HEADSET
            )
        ) {
            routes +=
                "WIRED_HEADSET"
        }

        if (
            supportsRoute(
                mask,
                CallAudioState.ROUTE_SPEAKER
            )
        ) {
            routes +=
                "SPEAKER"
        }

        return if (
            routes.isEmpty()
        ) {
            "NONE"
        } else {
            routes.joinToString(
                separator = "|"
            )
        }
    }

    override fun onDestroy() {

        incomingCallNotifier
            .cancelIncomingCall()

        serviceScope.cancel()

        if (
            currentService === this
        ) {
            currentService =
                null
        }

        lastObservedStatus =
            CallStatus.IDLE

        Log.i(
            TAG,
            "InCallService destroyed"
        )

        super.onDestroy()
    }

    private fun observeTelecomState() {

        serviceScope.launch {

            telecomCallController
                .state
                .collectLatest { state ->

                    val previousStatus =
                        lastObservedStatus

                    Log.i(
                        TAG,
                        "Observed Telecom state: ${state.status}"
                    )

                    when (
                        state.status
                    ) {

                        CallStatus.INCOMING -> {

                            /*
                             * HyperNova owns the selected car-mode InCall UX.
                             * Remove the stock HUN and show the HyperNova call
                             * surface instead.
                             */
                            incomingCallNotifier
                                .cancelIncomingCall()

                            showIncomingCallActivity(
                                state
                            )
                        }

                        CallStatus.ACTIVE -> {

                            incomingCallNotifier
                                .cancelIncomingCall()

                            /*
                             * Only request the foreground UI when ENTERING
                             * ACTIVE.
                             *
                             * This includes:
                             *
                             * INCOMING -> ACTIVE
                             * HELD     -> ACTIVE
                             */
                            if (
                                previousStatus !=
                                CallStatus.ACTIVE
                            ) {

                                requestInCallUi()
                            }
                        }

                        else -> {

                            incomingCallNotifier
                                .cancelIncomingCall()
                        }
                    }

                    lastObservedStatus =
                        state.status
                }
        }
    }

    /**
     * Show the HyperNova-designed incoming-call experience.
     *
     * If Android rejects the Activity start for any reason, retain the old
     * notification as a safe fallback so an incoming call is never hidden.
     */
    private fun showIncomingCallActivity(
        state: TelecomCallState
    ) {

        val intent =
            Intent(
                this,
                IncomingCallActivity::class.java
            ).apply {

                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }

        try {

            startActivity(
                intent
            )

            Log.i(
                TAG,
                "HyperNova incoming-call UI requested"
            )

        } catch (
            exception: RuntimeException
        ) {

            Log.e(
                TAG,
                "Could not open HyperNova incoming-call UI; using HUN fallback",
                exception
            )

            serviceScope.launch {
                incomingCallNotifier
                    .showIncomingCall(
                        state
                    )
            }
        }
    }

    /**
     * Bring HyperNova Phone forward only after Android Telecom confirms
     * that the call is ACTIVE.
     *
     * Do not use TelecomManager.showInCallScreen() here; opening our own
     * selected car-mode InCall UI avoids the READ_PHONE_STATE restriction
     * seen on this AAOS target.
     */
    private fun requestInCallUi() {

        val intent =
            Intent(
                this,
                MainActivity::class.java
            ).apply {

                action =
                    ACTION_OPEN_PHONE

                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )

                putExtra(
                    EXTRA_SHOW_IN_CALL_DIALPAD,
                    false
                )
            }

        try {

            startActivity(
                intent
            )

            Log.i(
                TAG,
                "Call ACTIVE; HyperNova Phone opened"
            )

        } catch (
            exception: RuntimeException
        ) {

            Log.e(
                TAG,
                "Could not open HyperNova Phone for active call",
                exception
            )
        }
    }

    companion object {

        private const val TAG =
            "HN-InCall"

        private const val ACTION_OPEN_PHONE =
            "com.hypernova.phone.action.OPEN"

        const val EXTRA_SHOW_IN_CALL_DIALPAD =
            "com.hypernova.phone.extra.SHOW_IN_CALL_DIALPAD"

        @Volatile
        internal var currentService:
            HyperNovaInCallService? = null
    }
}
