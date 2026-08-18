package com.hypernova.ai.runtime

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hypernova.ai.ui.NovaVisibleState

object NovaRuntimeState {
    private val mutableState = MutableLiveData(NovaVisibleState.UNAVAILABLE)
    private val mutableSession = MutableLiveData(NovaRuntimeSnapshot())
    private val mainHandler = Handler(Looper.getMainLooper())
    val state: LiveData<NovaVisibleState> = mutableState
    val session: LiveData<NovaRuntimeSnapshot> = mutableSession
    private var latestSession = NovaRuntimeSnapshot()

    fun publish(state: NovaVisibleState, followUpWindowMs: Long? = null) {
        dispatch {
            val activeWindow = followUpWindowMs
                ?.takeIf { state == NovaVisibleState.LISTENING && it > 0L }
            mutableState.value = state
            latestSession = latestSession.copy(
                visibleState = state,
                followUpWindowMs = activeWindow,
                followUpDeadlineElapsedRealtimeMs = activeWindow?.let {
                    SystemClock.elapsedRealtime() + it
                },
            )
            mutableSession.value = latestSession
        }
    }

    fun publishTranscript(turnId: String?, text: String) = dispatch {
        latestSession = NovaRuntimeSnapshot(
            visibleState = latestSession.visibleState,
            turnId = turnId,
            transcript = text,
        )
        mutableSession.value = latestSession
    }

    fun publishProgress(turnId: String?, text: String, routeTier: String?) = dispatch {
        latestSession = latestSession.copy(
            turnId = turnId ?: latestSession.turnId,
            progressText = text,
            routeTier = routeTier ?: latestSession.routeTier,
        )
        mutableSession.value = latestSession
    }

    fun publishRoute(turnId: String?, routeTier: String?) = dispatch {
        latestSession = latestSession.copy(
            turnId = turnId ?: latestSession.turnId,
            routeTier = routeTier ?: latestSession.routeTier,
        )
        mutableSession.value = latestSession
    }

    fun publishEvidence(turnId: String?, cards: List<NovaEvidenceCard>) = dispatch {
        latestSession = latestSession.copy(
            turnId = turnId ?: latestSession.turnId,
            evidenceCards = cards.take(4),
        )
        mutableSession.value = latestSession
    }

    fun publishAction(
        turnId: String?,
        domain: String?,
        name: String?,
        result: String?,
        blocked: Boolean,
        errorMessage: String?,
    ) = dispatch {
        latestSession = latestSession.copy(
            turnId = turnId ?: latestSession.turnId,
            progressText = null,
            actionDomain = domain ?: latestSession.actionDomain,
            actionName = name,
            actionResult = result,
            errorMessage = errorMessage,
            blocked = blocked,
        )
        mutableSession.value = latestSession
    }

    fun publishResult(turnId: String?, text: String?, success: Boolean) = dispatch {
        latestSession = latestSession.copy(
            turnId = turnId ?: latestSession.turnId,
            progressText = null,
            actionResult = text,
            errorMessage = if (success) null else text,
            blocked = !success,
        )
        mutableSession.value = latestSession
    }

    fun publishResponse(turnId: String?, text: String) = dispatch {
        latestSession = latestSession.copy(
            turnId = turnId ?: latestSession.turnId,
            progressText = null,
            spokenText = text,
        )
        mutableSession.value = latestSession
    }

    fun publishError(turnId: String?, message: String) = dispatch {
        latestSession = latestSession.copy(
            turnId = turnId ?: latestSession.turnId,
            progressText = null,
            errorMessage = message,
            blocked = true,
        )
        mutableSession.value = latestSession
    }

    private fun dispatch(update: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            update()
        } else {
            // LiveData.postValue coalesces rapid events. Queue every update explicitly so the
            // transcript, action, result and visible state remain ordered.
            mainHandler.post(update)
        }
    }
}
