package com.hypernova.wdt

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * UI-only System Control screen.
 *
 * No restart, panic, or watchdog command is executed in this baseline.
 * The three click handlers deliberately stop at the UI boundary so the
 * privileged/backend execution path can be wired separately and reviewed.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var backendStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        configureImmersiveMode()

        backendStatus = findViewById(R.id.textBackendStatus)

        findViewById<View>(R.id.buttonBack).setOnClickListener {
            finish()
        }

        findViewById<View>(R.id.actionRestart).setOnClickListener {
            showUiOnlySelection(getString(R.string.restart_title))
        }

        findViewById<View>(R.id.actionKernelPanic).setOnClickListener {
            showUiOnlySelection(getString(R.string.panic_title))
        }

        findViewById<View>(R.id.actionWatchdog).setOnClickListener {
            showUiOnlySelection(getString(R.string.watchdog_title))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            configureImmersiveMode()
        }
    }

    private fun showUiOnlySelection(action: String) {
        backendStatus.text = getString(R.string.backend_pending_format, action)

        Toast.makeText(
            this,
            getString(R.string.backend_pending_format, action),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun configureImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller =
            WindowInsetsControllerCompat(
                window,
                window.decorView,
            )

        val nightMask =
            resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK

        val isNight =
            nightMask == Configuration.UI_MODE_NIGHT_YES

        controller.isAppearanceLightStatusBars = !isNight
        controller.isAppearanceLightNavigationBars = !isNight
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
