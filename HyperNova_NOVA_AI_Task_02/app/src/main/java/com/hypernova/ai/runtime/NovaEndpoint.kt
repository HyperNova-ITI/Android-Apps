package com.hypernova.ai.runtime

import android.content.Context
import com.hypernova.ai.BuildConfig

data class NovaEndpoint(
    val host: String,
    val controlPort: Int = 8765,
    val audioPort: Int = 8766,
)

object NovaEndpointStore {
    private const val PREFERENCES = "nova_runtime"
    private const val HOST = "host"
    private val DEFAULT_HOST = BuildConfig.NOVA_DEFAULT_HOST

    fun load(context: Context): NovaEndpoint {
        val host = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(HOST, DEFAULT_HOST)
            ?.trim()
            .orEmpty()
            .ifBlank { DEFAULT_HOST }
        return NovaEndpoint(host)
    }

    fun saveHost(context: Context, host: String) {
        require(host.isNotBlank()) { "NOVA host cannot be blank" }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(HOST, host.trim())
            .apply()
    }
}
