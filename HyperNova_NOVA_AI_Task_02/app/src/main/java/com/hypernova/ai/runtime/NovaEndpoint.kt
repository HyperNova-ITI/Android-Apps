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
    private val LEGACY_DEFAULT_HOSTS = setOf(
        "192.168.1.32",
        "192.168.10.20",
    )

    fun load(context: Context): NovaEndpoint {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val savedHost = preferences.getString(HOST, null)
        val host = resolveHost(savedHost, DEFAULT_HOST)
        if (savedHost?.trim() in LEGACY_DEFAULT_HOSTS) {
            preferences.edit().putString(HOST, host).apply()
        }
        return NovaEndpoint(host)
    }

    fun saveHost(context: Context, host: String) {
        require(host.isNotBlank()) { "NOVA host cannot be blank" }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(HOST, host.trim())
            .apply()
    }

    internal fun resolveHost(savedHost: String?, defaultHost: String): String {
        val normalized = savedHost?.trim().orEmpty()
        return when {
            normalized.isBlank() -> defaultHost
            normalized in LEGACY_DEFAULT_HOSTS -> defaultHost
            else -> normalized
        }
    }
}
