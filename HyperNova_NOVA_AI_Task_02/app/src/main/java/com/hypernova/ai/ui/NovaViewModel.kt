package com.hypernova.ai.ui

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import com.hypernova.ai.runtime.NovaRuntimeSnapshot
import com.hypernova.ai.runtime.NovaRuntimeState
import com.hypernova.ai.session.NovaStateMachine

class NovaViewModel : ViewModel() {
    private val stateMachine = NovaStateMachine()
    private val mutableUiState = MutableLiveData(NovaUiState())
    private val runtimeObserver = Observer<NovaRuntimeSnapshot>(::onRuntimeSnapshot)

    val uiState: LiveData<NovaUiState> = mutableUiState

    init {
        NovaRuntimeState.session.observeForever(runtimeObserver)
    }

    fun onRuntimeSnapshot(snapshot: NovaRuntimeSnapshot) {
        if (!stateMachine.transitionTo(snapshot.visibleState)) {
            Log.w(
                TAG,
                "Ignored invalid NOVA transition ${stateMachine.currentState} -> ${snapshot.visibleState}",
            )
            return
        }

        mutableUiState.value = NovaUiStateFactory.create(snapshot)
    }

    override fun onCleared() {
        NovaRuntimeState.session.removeObserver(runtimeObserver)
        super.onCleared()
    }

    private companion object {
        const val TAG = "NovaViewModel"
    }
}
