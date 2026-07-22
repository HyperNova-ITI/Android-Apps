package com.hypernova.ai.runtime

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import android.os.Handler
import android.os.Looper
import com.hypernova.ai.ui.NovaVisibleState

object NovaRuntimeState {
    private val mutableState = MutableLiveData(NovaVisibleState.UNAVAILABLE)
    private val mutableSession = MutableLiveData(NovaRuntimeSnapshot())
    private val mainHandler = Handler(Looper.getMainLooper())
    val state: LiveData<NovaVisibleState> = mutableState
    val session: LiveData<NovaRuntimeSnapshot> = mutableSession
    private var latestSession = NovaRuntimeSnapshot()

    fun publish(state: NovaVisibleState) {
        dispatch {
            mutableState.value = state
            latestSession = latestSession.copy(visibleState = state)
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

    fun publishAction(
        turnId: String?,
        name: String?,
        result: String?,
        blocked: Boolean,
        errorMessage: String?,
    ) = dispatch {
        latestSession = latestSession.copy(
            turnId = turnId ?: latestSession.turnId,
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
            actionResult = text,
            errorMessage = if (success) null else text,
            blocked = !success,
        )
        mutableSession.value = latestSession
    }

    fun publishResponse(turnId: String?, text: String) = dispatch {
        latestSession = latestSession.copy(
            turnId = turnId ?: latestSession.turnId,
            spokenText = text,
        )
        mutableSession.value = latestSession
    }

    fun publishError(turnId: String?, message: String) = dispatch {
        latestSession = latestSession.copy(
            turnId = turnId ?: latestSession.turnId,
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
