package com.hypernova.wdt

import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

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

    private const val HOST = "127.0.0.1"
    private const val PORT = 47631
    private const val TOKEN = "HN_WDT_V1_6f0ca9d2b8c34c59a1f6e723"
    private const val CONNECT_TIMEOUT_MS = 1200
    private const val READ_TIMEOUT_MS = 3000

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun probeRoot(
        callback: (SystemActionResult) -> Unit,
    ) {
        worker.execute {
            val result = sendRequest("PING")

            val finalResult =
                if (result.success && result.message.startsWith("OK ROOT")) {
                    SystemActionResult(
                        success = true,
                        message = "ROOT BACKEND READY",
                    )
                } else {
                    SystemActionResult(
                        success = false,
                        message = "ROOT BACKEND OFFLINE",
                    )
                }

            postResult(callback, finalResult)
        }
    }

    fun execute(
        action: SystemAction,
        callback: (SystemActionResult) -> Unit,
    ) {
        val request =
            when (action) {
                SystemAction.RESTART -> "POWER_OFF"
                SystemAction.KERNEL_PANIC -> "KERNEL_PANIC"
                SystemAction.WATCHDOG -> "WATCHDOG"
            }

        worker.execute {
            val response = sendRequest(request)

            val finalResult =
                if (response.success && response.message.startsWith("OK")) {
                    when (action) {
                        SystemAction.RESTART ->
                            SystemActionResult(
                                success = true,
                                message = "POWER OFF TRIGGERED",
                            )

                        SystemAction.KERNEL_PANIC ->
                            SystemActionResult(
                                success = true,
                                message = "KERNEL PANIC TRIGGERED",
                            )

                        SystemAction.WATCHDOG ->
                            SystemActionResult(
                                success = true,
                                message = "WATCHDOG TRIGGERED • WAITING FOR RESET",
                            )
                    }
                } else {
                    SystemActionResult(
                        success = false,
                        message =
                            if (response.message.isBlank()) {
                                "ROOT BACKEND OFFLINE"
                            } else {
                                response.message
                            },
                    )
                }

            postResult(callback, finalResult)
        }
    }

    private fun sendRequest(request: String): SystemActionResult {
        return try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(HOST, PORT),
                    CONNECT_TIMEOUT_MS,
                )
                socket.soTimeout = READ_TIMEOUT_MS

                val writer =
                    BufferedWriter(
                        OutputStreamWriter(socket.getOutputStream()),
                    )

                val reader =
                    BufferedReader(
                        InputStreamReader(socket.getInputStream()),
                    )

                writer.write("$TOKEN $request")
                writer.newLine()
                writer.flush()

                val response = reader.readLine().orEmpty().trim()

                if (response.startsWith("OK")) {
                    SystemActionResult(true, response)
                } else {
                    SystemActionResult(
                        false,
                        if (response.isBlank()) {
                            "ROOT BACKEND OFFLINE"
                        } else {
                            response
                        },
                    )
                }
            }
        } catch (_: Throwable) {
            SystemActionResult(
                success = false,
                message = "ROOT BACKEND OFFLINE",
            )
        }
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
