package com.hypernova.climate

import android.content.Context
import android.content.SharedPreferences

object MockMode {
    const val NORMAL = "normal"
    const val REJECT = "reject"
    const val UNAVAILABLE = "unavailable"
    const val TIMEOUT = "timeout"

    private const val PREFS = "climate_mock"
    private const val KEY_MODE = "mode"
    private const val KEY_STATUS = "status"

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context): String =
        preferences(context)
            .getString(KEY_MODE, NORMAL) ?: NORMAL

    fun set(context: Context, mode: String) {
        preferences(context)
            .edit()
            .putString(KEY_MODE, mode)
            .apply()
    }

    fun status(context: Context): String =
        preferences(context)
            .getString(KEY_STATUS, "22°C · fan 3 · A/C on") ?: "22°C · fan 3 · A/C on"

    fun status(context: Context, value: String) {
        preferences(context)
            .edit()
            .putString(KEY_STATUS, value)
            .apply()
    }
}
