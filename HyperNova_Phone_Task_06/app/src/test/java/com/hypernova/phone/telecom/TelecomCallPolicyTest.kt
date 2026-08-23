package com.hypernova.phone.telecom

import android.telecom.Call
import com.hypernova.phone.domain.CallStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused tests for the Telecom/HFP lifecycle decision rules used by
 * TelecomCallController gating and state publication.
 */
class TelecomCallPolicyTest {

    @Test fun dialingStatesMapToDialing() {
        assertEquals(
            CallStatus.DIALING,
            TelecomCallPolicy.mapStatus(Call.STATE_NEW, directionOutgoing())
        )
        assertEquals(
            CallStatus.DIALING,
            TelecomCallPolicy.mapStatus(Call.STATE_CONNECTING, directionOutgoing())
        )
        assertEquals(
            CallStatus.DIALING,
            TelecomCallPolicy.mapStatus(Call.STATE_DIALING, directionOutgoing())
        )
    }

    @Test fun incomingRingMapsToIncomingAndIsAnswerable() {
        val status = TelecomCallPolicy.mapStatus(
            Call.STATE_RINGING,
            Call.Details.DIRECTION_INCOMING
        )

        assertEquals(CallStatus.INCOMING, status)
        assertTrue(
            TelecomCallPolicy.canAnswer(
                Call.STATE_RINGING,
                Call.Details.DIRECTION_INCOMING
            )
        )
        assertTrue(
            TelecomCallPolicy.canDecline(
                Call.STATE_RINGING,
                Call.Details.DIRECTION_INCOMING
            )
        )
    }

    @Test fun outgoingRingbackIsNeverAnswerable() {
        assertEquals(
            CallStatus.RINGING,
            TelecomCallPolicy.mapStatus(
                Call.STATE_RINGING,
                Call.Details.DIRECTION_OUTGOING
            )
        )

        assertFalse(
            TelecomCallPolicy.canAnswer(
                Call.STATE_RINGING,
                Call.Details.DIRECTION_OUTGOING
            )
        )
        assertFalse(
            TelecomCallPolicy.canDecline(
                Call.STATE_SIMULATED_RINGING,
                Call.Details.DIRECTION_OUTGOING
            )
        )
    }

    @Test fun nonRingingStatesAreNeverAnswerable() {
        assertFalse(
            TelecomCallPolicy.canAnswer(
                Call.STATE_ACTIVE,
                Call.Details.DIRECTION_INCOMING
            )
        )
        assertFalse(
            TelecomCallPolicy.canAnswer(
                Call.STATE_DIALING,
                Call.Details.DIRECTION_INCOMING
            )
        )
    }

    @Test fun terminalStatesMapToCallEndedAndUnknownStateToIdle() {
        assertEquals(
            CallStatus.CALL_ENDED,
            TelecomCallPolicy.mapStatus(
                Call.STATE_DISCONNECTED,
                Call.Details.DIRECTION_INCOMING
            )
        )
        assertEquals(
            CallStatus.CALL_ENDED,
            TelecomCallPolicy.mapStatus(
                Call.STATE_DISCONNECTING,
                Call.Details.DIRECTION_INCOMING
            )
        )
        assertEquals(
            CallStatus.IDLE,
            TelecomCallPolicy.mapStatus(-999, Call.Details.DIRECTION_INCOMING)
        )
    }

    @Test fun holdSupportAcceptsHoldOrSupportHoldCapability() {
        val hold = Call.Details.CAPABILITY_HOLD
        val supportHold = Call.Details.CAPABILITY_SUPPORT_HOLD

        assertTrue(TelecomCallPolicy.canHold(hold))
        assertTrue(TelecomCallPolicy.canHold(supportHold))
        assertTrue(TelecomCallPolicy.canHold(hold or supportHold))
        assertFalse(TelecomCallPolicy.canHold(0))

        assertTrue(
            TelecomCallPolicy.canHoldNow(Call.STATE_ACTIVE, supportHold)
        )
        assertFalse(
            TelecomCallPolicy.canHoldNow(Call.STATE_HOLDING, hold)
        )
        assertFalse(
            TelecomCallPolicy.canHoldNow(Call.STATE_ACTIVE, 0)
        )

        assertTrue(
            TelecomCallPolicy.canUnholdNow(Call.STATE_HOLDING, hold)
        )
        assertFalse(
            TelecomCallPolicy.canUnholdNow(Call.STATE_ACTIVE, hold)
        )
    }

    @Test fun disconnectAllowedUntilTelecomTearsDownCall() {
        assertTrue(
            TelecomCallPolicy.canDisconnect(Call.STATE_ACTIVE)
        )
        assertTrue(
            TelecomCallPolicy.canDisconnect(Call.STATE_DISCONNECTING)
        )
        assertFalse(
            TelecomCallPolicy.canDisconnect(Call.STATE_DISCONNECTED)
        )
    }

    @Test fun dtmfDigitsAreValidatedExactly() {
        assertTrue(TelecomCallPolicy.isValidDtmf('0'))
        assertTrue(TelecomCallPolicy.isValidDtmf('9'))
        assertTrue(TelecomCallPolicy.isValidDtmf('*'))
        assertTrue(TelecomCallPolicy.isValidDtmf('#'))
        assertFalse(TelecomCallPolicy.isValidDtmf('a'))
        assertFalse(TelecomCallPolicy.isValidDtmf('+'))
    }

    private fun directionOutgoing(): Int =
        Call.Details.DIRECTION_OUTGOING
}
