package com.hypernova.launcher.core.assistant

enum class NovaServiceConnection {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

data class NovaStatusSnapshot(
    val connection: NovaServiceConnection,
    val state: String? = null,
)
