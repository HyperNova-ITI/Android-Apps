package com.hypernova.navigation

import android.content.res.Configuration
import com.hypernova.navigation.ui.SystemThemeResolver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemThemeResolverTest {

    @Test
    fun resolvesAndroidNightConfiguration() {
        assertTrue(
            SystemThemeResolver.isNightMode(
                Configuration.UI_MODE_NIGHT_YES
            )
        )
        assertFalse(
            SystemThemeResolver.isNightMode(
                Configuration.UI_MODE_NIGHT_NO
            )
        )
    }
}
