package com.hypernova.ai.command

import android.os.Handler
import android.os.Looper

class HandlerCommandScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : CommandScheduler {
    override fun schedule(delayMillis: Long, action: () -> Unit): Cancelable {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMillis)
        return Cancelable { handler.removeCallbacks(runnable) }
    }
}
