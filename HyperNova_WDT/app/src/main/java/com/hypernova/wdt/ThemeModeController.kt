package com.hypernova.wdt

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

object ThemeModeController {
    private const val PREFS = "hypernova_wdt_ui"
    private const val KEY_NIGHT_MODE = "night_mode"

    fun applySavedMode(context: Context) {
        val mode =
            context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(
                    KEY_NIGHT_MODE,
                    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                )

        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun toggle(context: Context) {
        val currentNightMask =
            context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK

        val nextMode =
            if (currentNightMask == Configuration.UI_MODE_NIGHT_YES) {
                AppCompatDelegate.MODE_NIGHT_NO
            } else {
                AppCompatDelegate.MODE_NIGHT_YES
            }

        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_NIGHT_MODE, nextMode)
            .apply()

        AppCompatDelegate.setDefaultNightMode(nextMode)
    }
}
