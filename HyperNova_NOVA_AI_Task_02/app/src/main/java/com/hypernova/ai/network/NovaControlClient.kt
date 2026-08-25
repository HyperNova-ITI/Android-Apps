package com.hypernova.ai.network

import android.util.Log
import com.hypernova.ai.BuildConfig
import com.hypernova.ai.command.CommandResult
import com.hypernova.ai.command.CommandWireCodec
import com.hypernova.ai.runtime.NovaEndpoint
import com.hypernova.contracts.vehiclegateway.VehicleFaultEvent
import com.hypernova.contracts.vehiclegateway.VehicleGatewayContract
import com.hypernova.contracts.vehiclegateway.VehicleState
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
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
    @Volatile private var authenticated = false
    private val sequence = AtomicLong(0)
    private val lifecycleGeneration = AtomicLong(0)
    private val writerLock = Any()
    private val sendExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "nova-control-writer").apply { isDaemon = true }
    }
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
        enqueue(JSONObject().apply {
            put("type", "command")
            put("v", 1)
            put("seq", sequence.incrementAndGet())
            put("turn_id", turnId)
            put("text", text)
        })

    fun sendPlayback(turnId: String?, value: String): Boolean = enqueue(JSONObject().apply {
        put("type", "playback")
        put("v", 1)
        put("seq", sequence.incrementAndGet())
        if (turnId != null) put("turn_id", turnId)
        put("value", value)
    })

    /**
     * Ask the Pi to abandon the current turn: stop synthesising, drop anything still queued, and
     * return to idle. Playback already in Android's buffer is stopped locally by the runtime.
     */
    fun sendCancel(turnId: String?): Boolean = enqueue(JSONObject().apply {
        put("type", "cancel")
        put("v", 1)
        put("seq", sequence.incrementAndGet())
        if (turnId != null) put("turn_id", turnId)
    })

    /** Suspend or resume all microphone-triggered turns while keeping the cockpit link alive. */
    fun sendDeafened(deafened: Boolean, turnId: String? = null): Boolean = enqueue(JSONObject().apply {
        put("type", "set_deafened")
        put("v", 1)
        put("seq", sequence.incrementAndGet())
        put("deafened", deafened)
        if (turnId != null) put("turn_id", turnId)
    })

    fun sendCommandResult(result: CommandResult): Boolean =
        enqueue(CommandWireCodec.toJson(result).apply {
            put("seq", sequence.incrementAndGet())
        })

    fun sendVehicleState(state: VehicleState): Boolean = enqueue(JSONObject().apply {
        put("type", "vehicle_state")
        put("v", 1)
        put("seq", sequence.incrementAndGet())
        put("connection_state", when (state.connectionState) {
            VehicleGatewayContract.CONNECTION_CONNECTED -> "connected"
            VehicleGatewayContract.CONNECTION_CONNECTING -> "connecting"
            VehicleGatewayContract.CONNECTION_DEGRADED -> "degraded"
            else -> "disconnected"
        })
        putIfKnown("cabin_temp_c", state.cabinTemperatureC)
        putIfKnown("humidity_pct", state.humidityPercent)
        putIfKnown("fuel_pct", state.fuelPercent)
        putIfKnown("zone1_target_temp_c", state.zone1TargetTemperatureC)
        putIfKnown("zone2_target_temp_c", state.zone2TargetTemperatureC)
        putIfKnown("zone1_fan_level", state.zone1FanLevel)
        putIfKnown("zone2_fan_level", state.zone2FanLevel)
        put("active_dtcs", JSONArray().apply {
            state.activeDtcs.forEach { put(dtcName(it)) }
        })
        put("telemetry_fresh", state.isTelemetryFresh)
        put("updated_at_epoch_millis", state.updatedAtEpochMillis)
    })

    fun sendFaultEvent(event: VehicleFaultEvent): Boolean = enqueue(JSONObject().apply {
        put("type", "fault_event")
        put("v", 1)
        put("seq", sequence.incrementAndGet())
        put("code", dtcName(event.dtc))
        put("active", event.isActive)
        put("tc_event_sequence", event.tcEventSequence)
        put("received_at_epoch_millis", event.receivedAtEpochMillis)
    })

    private fun JSONObject.putIfKnown(name: String, value: Int) {
        if (value >= 0) put(name, value)
    }

    private fun dtcName(value: Int): String = "P%04X".format(value and 0xFFFF)

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
                                    synchronized(writerLock) {
                                        this@NovaControlClient.authenticated = true
                                    }
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
        sendNow(JSONObject().apply {
            put("type", "hello")
            put("v", 1)
            put("seq", sequence.incrementAndGet())
            put("client", "nova-android")
            put("app_version", "0.1.0")
            if (BuildConfig.NOVA_LINK_TOKEN.isNotBlank()) {
                put("auth", BuildConfig.NOVA_LINK_TOKEN)
            }
        }, requireAuthentication = false)

    private fun enqueue(message: JSONObject): Boolean {
        if (!running) return false
        sendExecutor.execute { sendNow(message) }
        return true
    }

    private fun sendNow(
        message: JSONObject,
        requireAuthentication: Boolean = true,
    ): Boolean = synchronized(writerLock) {
        if (requireAuthentication && !authenticated) return@synchronized false
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
            authenticated = false
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
