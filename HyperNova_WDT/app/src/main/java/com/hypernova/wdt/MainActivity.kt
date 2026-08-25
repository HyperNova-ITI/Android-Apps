package com.hypernova.wdt

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var backendStatus: TextView

    private lateinit var restartAction: View
    private lateinit var kernelPanicAction: View
    private lateinit var watchdogAction: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        configureImmersiveMode()

        backendStatus =
            findViewById(R.id.textBackendStatus)

        restartAction =
            findViewById(R.id.actionRestart)

        kernelPanicAction =
            findViewById(R.id.actionKernelPanic)

        watchdogAction =
            findViewById(R.id.actionWatchdog)

        findViewById<View>(R.id.buttonBack)
            .setOnClickListener {
                finish()
            }

        restartAction.setOnClickListener {
            confirmSystemAction(
                action = SystemAction.RESTART,
                title = "Restart System?",
                message =
                    "This will sync storage and immediately reboot the Android system.",
            )
        }

        kernelPanicAction.setOnClickListener {
            confirmSystemAction(
                action = SystemAction.KERNEL_PANIC,
                title = "Trigger Kernel Panic?",
                message =
                    "This intentionally crashes the Linux kernel using SysRq. " +
                        "The system will become unavailable immediately.",
            )
        }

        watchdogAction.setOnClickListener {
            confirmSystemAction(
                action = SystemAction.WATCHDOG,
                title = "Trigger Watchdog?",
                message =
                    "This will SIGSTOP watchdogd. The hardware watchdog should " +
                        "then expire and reset the system.",
            )
        }

        checkBackend()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            configureImmersiveMode()
        }
    }

    private fun checkBackend() {
        backendStatus.text =
            "CHECKING ROOT BACKEND..."

        SystemActionExecutor.probeRoot { result ->
            backendStatus.text = result.message
        }
    }

    private fun confirmSystemAction(
        action: SystemAction,
        title: String,
        message: String,
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Execute") { _, _ ->
                executeSystemAction(action)
            }
            .show()
    }

    private fun executeSystemAction(
        action: SystemAction,
    ) {
        setActionsEnabled(false)

        backendStatus.text =
            "EXECUTING ${action.displayName.uppercase()}..."

        Toast.makeText(
            this,
            "Executing ${action.displayName}",
            Toast.LENGTH_SHORT,
        ).show()

        SystemActionExecutor.execute(action) { result ->
            backendStatus.text = result.message

            /*
             * Restart and Kernel Panic normally kill the running Android
             * environment before a callback can be delivered.
             *
             * Watchdog returns after watchdogd is stopped, so the UI can
             * remain visible while waiting for the hardware watchdog.
             */
            if (!result.success) {
                setActionsEnabled(true)

                Toast.makeText(
                    this,
                    result.message,
                    Toast.LENGTH_LONG,
                ).show()
            } else if (action == SystemAction.WATCHDOG) {
                Toast.makeText(
                    this,
                    result.message,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        restartAction.isEnabled = enabled
        kernelPanicAction.isEnabled = enabled
        watchdogAction.isEnabled = enabled

        restartAction.alpha =
            if (enabled) 1.0f else 0.55f

        kernelPanicAction.alpha =
            if (enabled) 1.0f else 0.55f

        watchdogAction.alpha =
            if (enabled) 1.0f else 0.55f
    }

    private fun configureImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(
            window,
            false,
        )

        val controller =
            WindowInsetsControllerCompat(
                window,
                window.decorView,
            )

        val nightMask =
            resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK

        val isNight =
            nightMask ==
                Configuration.UI_MODE_NIGHT_YES

        controller.isAppearanceLightStatusBars =
            !isNight

        controller.isAppearanceLightNavigationBars =
            !isNight

        controller.hide(
            WindowInsetsCompat.Type.systemBars(),
        )

        controller.systemBarsBehavior =
            WindowInsetsControllerCompat
                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
