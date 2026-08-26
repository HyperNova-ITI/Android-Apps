package com.hypernova.phone.telecom

import android.telecom.Call
import android.telecom.TelecomManager
import com.hypernova.phone.domain.CallStatus

/**
 * Pure Telecom/HFP decision rules.
 *
 * Every input is a plain value already supplied by Android Telecom
 * (state int, direction int, capability mask). Extracted verbatim from
 * TelecomCallController so the real gating rules are unit-testable on
 * the JVM while Android Telecom remains the state authority.
 */
internal object TelecomCallPolicy {

    /**
     * Map a real Telecom call state to HyperNova status.
     *
     * RINGING is only INCOMING when Telecom reported the call direction
     * as incoming; an outgoing call waiting for remote ringback is
     * RINGING and must never be answerable as incoming.
     */
    fun mapStatus(
        callState: Int,
        callDirection: Int
    ): CallStatus {

        return when (callState) {

            Call.STATE_NEW,
            Call.STATE_CONNECTING,
            Call.STATE_DIALING,
            Call.STATE_SELECT_PHONE_ACCOUNT ->
                CallStatus.DIALING

            Call.STATE_RINGING,
            Call.STATE_SIMULATED_RINGING ->
                if (
                    callDirection ==
                    Call.Details.DIRECTION_INCOMING
                ) {
                    CallStatus.INCOMING

                } else {

                    CallStatus.RINGING
                }

            Call.STATE_ACTIVE ->
                CallStatus.ACTIVE

            Call.STATE_HOLDING ->
                CallStatus.HELD

            Call.STATE_DISCONNECTED,
            Call.STATE_DISCONNECTING ->
                CallStatus.CALL_ENDED

            else ->
                CallStatus.IDLE
        }
    }

    /**
     * Hold is genuinely supported when Telecom reports either
     * CAPABILITY_HOLD (may hold right now) or CAPABILITY_SUPPORT_HOLD
     * (holding supported regardless of other calls). The previous check
     * looked at CAPABILITY_HOLD alone and hid hold-capable calls.
     */
    fun canHold(
        capabilities: Int
    ): Boolean {

        return hasCapability(
            capabilities,
            Call.Details.CAPABILITY_HOLD
        ) ||
            hasCapability(
                capabilities,
                Call.Details.CAPABILITY_SUPPORT_HOLD
            )
    }

    /**
     * Answer/decline require a live incoming ring; anything else must
     * never reach Call.answer()/Call.reject().
     */
    fun canAnswer(
        callState: Int,
        callDirection: Int
    ): Boolean {

        return callState in
            setOf(
                Call.STATE_RINGING,
                Call.STATE_SIMULATED_RINGING
            ) &&
            callDirection ==
            Call.Details.DIRECTION_INCOMING
    }

    fun canDecline(
        callState: Int,
        callDirection: Int
    ): Boolean {

        return canAnswer(
            callState,
            callDirection
        )
    }

    /**
     * Disconnect applies to any call Telecom has not yet torn down;
     * repeat requests during DISCONNECTING stay idempotent.
     */
    fun canDisconnect(
        callState: Int
    ): Boolean {

        return callState !=
            Call.STATE_DISCONNECTED
    }

    /**
     * Hold requires ACTIVE plus Telecom hold support;
     * resume requires HELD plus the same support.
     */
    fun canHoldNow(
        callState: Int,
        capabilities: Int
    ): Boolean {

        return canHold(capabilities) &&
            callState ==
            Call.STATE_ACTIVE
    }

    fun canUnholdNow(
        callState: Int,
        capabilities: Int
    ): Boolean {

        return canHold(capabilities) &&
            callState ==
            Call.STATE_HOLDING
    }

    fun isValidDtmf(
        tone: Char
    ): Boolean {

        return tone in VALID_DTMF_DIGITS
    }

    fun shouldShowCompactIncomingUi(
        status: CallStatus
    ): Boolean =
        status ==
            CallStatus.INCOMING

    fun shouldShowFullInCallUi(
        status: CallStatus
    ): Boolean =
        status in
            setOf(
                CallStatus.DIALING,
                CallStatus.RINGING,
                CallStatus.ACTIVE,
                CallStatus.HELD
            )

    fun isNumberPresentationAllowed(
        presentation: Int
    ): Boolean =
        presentation ==
            TelecomManager.PRESENTATION_ALLOWED

    fun isCallerDisplayNamePresentationAllowed(
        presentation: Int
    ): Boolean =
        presentation ==
            TelecomManager.PRESENTATION_ALLOWED

    /**
     * Keep one active-call timer within a real ACTIVE -> HELD -> ACTIVE
     * lifecycle, and clear it for every pre-call or terminal state.
     *
     * Clearing on DIALING/RINGING is important: the controller StateFlow is
     * process-wide, so a later call must not inherit the previous call's
     * start timestamp.
     */
    fun activeStartedAtMillis(
        newStatus: CallStatus,
        previousStatus: CallStatus,
        previousStartedAtMillis: Long?,
        nowMillis: Long
    ): Long? =
        when (newStatus) {
            CallStatus.ACTIVE ->
                if (
                    previousStatus in
                    setOf(
                        CallStatus.ACTIVE,
                        CallStatus.HELD
                    ) &&
                    previousStartedAtMillis != null
                ) {
                    previousStartedAtMillis
                } else {
                    nowMillis
                }

            CallStatus.HELD ->
                previousStartedAtMillis

            else ->
                null
        }

    private fun hasCapability(
        capabilities: Int,
        capability: Int
    ): Boolean {

        return (
            capabilities and
                capability
            ) ==
            capability
    }

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
}
