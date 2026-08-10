package com.hypernova.launcher.core.assistant

import com.hypernova.launcher.core.state.AssistantRuntimeState
import java.util.Locale

/** Keeps the cross-APK wire value separate from launcher presentation logic. */
object NovaAssistantStateParser {
    fun parse(wireState: String?): AssistantRuntimeState = runCatching {
        AssistantRuntimeState.valueOf(wireState.orEmpty().uppercase(Locale.ROOT))
    }.getOrDefault(AssistantRuntimeState.UNAVAILABLE)
}
