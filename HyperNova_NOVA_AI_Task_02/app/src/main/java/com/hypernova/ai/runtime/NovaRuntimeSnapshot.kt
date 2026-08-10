package com.hypernova.ai.runtime

import com.hypernova.ai.ui.NovaVisibleState

/** Real session data received from the NOVA control channel. */
data class NovaRuntimeSnapshot(
    val visibleState: NovaVisibleState = NovaVisibleState.UNAVAILABLE,
    val turnId: String? = null,
    val followUpWindowMs: Long? = null,
    val followUpDeadlineElapsedRealtimeMs: Long? = null,
    val transcript: String? = null,
    val progressText: String? = null,
    val routeTier: String? = null,
    val actionName: String? = null,
    val actionResult: String? = null,
    val spokenText: String? = null,
    val errorMessage: String? = null,
    val blocked: Boolean = false,
)
