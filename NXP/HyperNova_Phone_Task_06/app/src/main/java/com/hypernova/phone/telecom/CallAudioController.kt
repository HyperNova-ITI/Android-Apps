package com.hypernova.phone.telecom

import android.telecom.InCallService
import android.util.Log

/** Audio control remains Telecom-owned. Bluetooth profiles are never manipulated by the UI. */
class CallAudioController {
    fun setMuted(muted: Boolean): Boolean {
        val service = HyperNovaInCallService.currentService ?: return false
        return try {
            service.setMuted(muted)
            Log.i(TAG, "Mute command sent to Telecom")
            true
        } catch (exception: Exception) {
            Log.w(TAG, "Mute command unavailable", exception)
            false
        }
    }
    private companion object { const val TAG = "HN-Telecom" }
}
