package com.hypernova.navigation.ui

import android.content.res.Configuration

object SystemThemeResolver {
    fun isNightMode(uiMode: Int): Boolean =
        uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
}
