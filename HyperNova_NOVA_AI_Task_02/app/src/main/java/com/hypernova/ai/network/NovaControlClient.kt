package com.hypernova.ai.network

import android.util.Log
import com.hypernova.ai.BuildConfig
import com.hypernova.ai.command.CommandResult
import com.hypernova.ai.command.CommandWireCodec
import com.hypernova.ai.runtime.NovaEndpoint
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class NovaControlClient(
    private val endpoint: NovaEndpoint,
    private val listener: Listener,
) {
    interface Listener {
        fun onControlConnectionChanged(connected: Boolean)
        fun onControlMessage(message: JSONObject)
    }

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: BufferedWriter? = null
    private val sequence = AtomicLong(0)
    private val lifecycleGeneration = AtomicLong(0)
    private val writerLock = Any()
    private var worker: Thread? = null

    @Synchronized
    fun start() {
        if (running) return
        running = true
        val generation = lifecycleGeneration.incrementAndGet()
        worker = Thread({ runLoop(generation) }, "nova-control").also { it.start() }
    }

    @Synchronized
    fun stop() {
        running = false
        lifecycleGeneration.incrementAndGet()
        closeConnection()
        worker?.interrupt()
        worker = null
    }

    fun sendCommand(text: String, turnId: String = UUID.randomUUID().toString()): Boolean =
        send(JSONObject().apply {
            put("type", "command")
            put("v", 1)
            put("seq", sequence.incrementAndGet())
            put("turn_id", turnId)
            put("text", text)
        })

    fun sendPlayback(turnId: String?, value: String): Boolean = send(JSONObject().apply {
        put("type", "playback")
        put("v", 1)
        put("seq", sequence.incrementAndGet())
        if (turnId != null) put("turn_id", turnId)
        put("value", value)
    })

    fun sendCommandResult(result: CommandResult): Boolean =
        send(CommandWireCodec.toJson(result).apply {
            put("seq", sequence.incrementAndGet())
        })

    private fun runLoop(generation: Long) {
        var backoffMs = 250L
        while (isCurrent(generation)) {
            var connectedSocket: Socket? = null
            var authenticated = false
            try {
                connectedSocket = Socket().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    connect(InetSocketAddress(endpoint.host, endpoint.controlPort), CONNECT_TIMEOUT_MS)
                }
                val accepted = synchronized(writerLock) {
                    if (!isCurrent(generation)) {
                        false
                    } else {
                        socket = connectedSocket
                        writer = BufferedWriter(
                            OutputStreamWriter(connectedSocket.getOutputStream(), Charsets.UTF_8),
                        )
                        true
                    }
                }
                if (!accepted) break
                if (!sendHello()) throw IllegalStateException("control hello send failed")
                backoffMs = 250L

                BufferedReader(InputStreamReader(connectedSocket.getInputStream(), Charsets.UTF_8)).use { reader ->
                    while (isCurrent(generation)) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        try {
                            val message = JSONObject(line)
                            when (message.optString("type")) {
                                "hello_ack" -> if (!authenticated) {
                                    authenticated = true
                                    listener.onControlConnectionChanged(true)
                                }
                                "hello_nack" -> throw SecurityException("Pi rejected NOVA control authentication")
                                else -> if (authenticated) listener.onControlMessage(message)
                            }
                        } catch (error: Exception) {
                            if (error is SecurityException) throw error
                            Log.w(TAG, "Ignored invalid control message", error)
                        }
                    }
                }
            } catch (error: Exception) {
                if (isCurrent(generation)) {
                    Log.d(TAG, "Control connection unavailable: ${error.message}")
                }
            } finally {
                closeConnection(connectedSocket)
                if (authenticated && isCurrent(generation)) {
                    listener.onControlConnectionChanged(false)
                }
            }

            if (isCurrent(generation)) {
                try {
                    Thread.sleep(backoffMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                backoffMs = (backoffMs * 2).coerceAtMost(5_000L)
            }
        }
    }

    private fun isCurrent(generation: Long): Boolean =
        running && lifecycleGeneration.get() == generation

    private fun sendHello(): Boolean =
        send(JSONObject().apply {
            put("type", "hello")
            put("v", 1)
            put("seq", sequence.incrementAndGet())
            put("client", "nova-android")
            put("app_version", "0.1.0")
            if (BuildConfig.NOVA_LINK_TOKEN.isNotBlank()) {
                put("auth", BuildConfig.NOVA_LINK_TOKEN)
            }
        })

    private fun send(message: JSONObject): Boolean = synchronized(writerLock) {
        val activeWriter = writer ?: return@synchronized false
        try {
            activeWriter.write(message.toString())
            activeWriter.newLine()
            activeWriter.flush()
            true
        } catch (error: Exception) {
            Log.w(TAG, "Control send failed", error)
            false
        }
    }

    private fun closeConnection(expectedSocket: Socket? = null) {
        synchronized(writerLock) {
            val activeSocket = socket
            if (expectedSocket != null && activeSocket !== expectedSocket) {
                try {
                    expectedSocket.close()
                } catch (_: Exception) {
                    // This belongs to an obsolete worker and is already unusable.
                }
                return
            }
            writer = null
            try {
                activeSocket?.close()
            } catch (_: Exception) {
                // The reconnect loop owns recovery.
            }
            socket = null
        }
    }

    private companion object {
        const val TAG = "NovaControlClient"
        const val CONNECT_TIMEOUT_MS = 3_000
    }
}
