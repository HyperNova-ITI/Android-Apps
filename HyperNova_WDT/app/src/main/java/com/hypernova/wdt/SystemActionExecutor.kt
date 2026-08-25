package com.hypernova.wdt

import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class SystemAction(
    val displayName: String,
) {
    RESTART("Restart"),
    KERNEL_PANIC("Kernel Panic"),
    WATCHDOG("Watchdog Timer"),
}

data class SystemActionResult(
    val success: Boolean,
    val message: String,
)

object SystemActionExecutor {

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /*
     * IMPORTANT:
     * These commands are intentionally fixed.
     *
     * Do not expose arbitrary shell command execution to the Activity/UI.
     */
    private val commands =
        mapOf(
            SystemAction.RESTART to
                """
                sync
                reboot -p
                """.trimIndent(),

            SystemAction.KERNEL_PANIC to
                """
                sync
                echo 0 > /proc/sys/kernel/panic
                echo 1 > /proc/sys/kernel/sysrq
                echo c > /proc/sysrq-trigger
                """.trimIndent(),

            SystemAction.WATCHDOG to
                """
                PIDS="$(pidof watchdogd)"
                if [ -z "${'$'}PIDS" ]; then
                    echo "watchdogd is not running" >&2
                    exit 20
                fi

                kill -STOP ${'$'}PIDS
                """.trimIndent(),
        )

    fun probeRoot(
        callback: (SystemActionResult) -> Unit,
    ) {
        worker.execute {
            val result =
                runAsRoot(
                    command = "id -u",
                    timeoutSeconds = 3,
                )

            val finalResult =
                if (result.success && result.message.trim() == "0") {
                    SystemActionResult(
                        success = true,
                        message = "ROOT BACKEND READY",
                    )
                } else {
                    SystemActionResult(
                        success = false,
                        message =
                            if (result.message.isBlank()) {
                                "ROOT BACKEND UNAVAILABLE"
                            } else {
                                "ROOT ERROR • ${result.message}"
                            },
                    )
                }

            postResult(callback, finalResult)
        }
    }

    fun execute(
        action: SystemAction,
        callback: (SystemActionResult) -> Unit,
    ) {
        val command =
            commands[action]
                ?: run {
                    callback(
                        SystemActionResult(
                            success = false,
                            message = "Unknown system action",
                        ),
                    )
                    return
                }

        worker.execute {
            val result =
                runAsRoot(
                    command = command,
                    timeoutSeconds = 5,
                )

            val finalResult =
                when {
                    result.success &&
                        action == SystemAction.WATCHDOG ->
                        SystemActionResult(
                            success = true,
                            message =
                                "WATCHDOG TRIGGERED • waiting for hardware reset",
                        )

                    result.success ->
                        SystemActionResult(
                            success = true,
                            message = "${action.displayName.uppercase()} TRIGGERED",
                        )

                    else ->
                        SystemActionResult(
                            success = false,
                            message =
                                "${action.displayName.uppercase()} FAILED • ${result.message}",
                        )
                }

            postResult(callback, finalResult)
        }
    }

    private fun runAsRoot(
        command: String,
        timeoutSeconds: Long,
    ): SystemActionResult {
        return try {
            val suBinary = findSuBinary()

            val process =
                ProcessBuilder(
                    suBinary,
                    "0",
                    "/system/bin/sh",
                    "-c",
                    command,
                )
                    .redirectErrorStream(true)
                    .start()

            val finished =
                process.waitFor(
                    timeoutSeconds,
                    TimeUnit.SECONDS,
                )

            if (!finished) {
                process.destroy()

                return SystemActionResult(
                    success = false,
                    message = "root command timed out",
                )
            }

            val output =
                runCatching {
                    process.inputStream
                        .bufferedReader()
                        .readText()
                        .trim()
                }.getOrDefault("")

            val exitCode = process.exitValue()

            if (exitCode == 0) {
                SystemActionResult(
                    success = true,
                    message = output,
                )
            } else {
                SystemActionResult(
                    success = false,
                    message =
                        if (output.isBlank()) {
                            "exit code $exitCode"
                        } else {
                            output
                        },
                )
            }
        } catch (throwable: Throwable) {
            SystemActionResult(
                success = false,
                message =
                    throwable.message
                        ?: throwable.javaClass.simpleName,
            )
        }
    }

    private fun findSuBinary(): String {
        val candidates =
            listOf(
                "/system/xbin/su",
                "/system/bin/su",
            )

        for (candidate in candidates) {
            val file = File(candidate)

            if (file.exists() && file.canExecute()) {
                return candidate
            }
        }

        // Final PATH-based fallback.
        return "su"
    }

    private fun postResult(
        callback: (SystemActionResult) -> Unit,
        result: SystemActionResult,
    ) {
        mainHandler.post {
            callback(result)
        }
    }
}
