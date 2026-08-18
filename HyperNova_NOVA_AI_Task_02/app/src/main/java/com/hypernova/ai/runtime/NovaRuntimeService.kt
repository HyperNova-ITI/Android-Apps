package com.hypernova.ai.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hypernova.ai.BuildConfig
import com.hypernova.ai.NovaActivity
import com.hypernova.ai.R
import com.hypernova.ai.audio.NovaPcmPlayer
import com.hypernova.ai.command.AndroidCommandExecutor
import com.hypernova.ai.command.CommandCoordinator
import com.hypernova.ai.command.CommandResult
import com.hypernova.ai.command.CommandStatus
import com.hypernova.ai.command.CommandWireCodec
import com.hypernova.ai.command.HandlerCommandScheduler
import com.hypernova.ai.network.NovaAudioClient
import com.hypernova.ai.network.NovaControlClient
import com.hypernova.ai.protocol.AudioFrame
import com.hypernova.ai.protocol.AudioFrameType
import com.hypernova.ai.ui.NovaVisibleState
import com.hypernova.ai.vehicle.VehicleGatewayStateClient
import org.json.JSONObject

class NovaRuntimeService : Service(),
    NovaControlClient.Listener,
    NovaAudioClient.Listener,
    NovaPcmPlayer.Listener {

    private lateinit var controlClient: NovaControlClient
    private lateinit var audioClient: NovaAudioClient
    private lateinit var player: NovaPcmPlayer
    private lateinit var commandCoordinator: CommandCoordinator
    private lateinit var vehicleGatewayStateClient: VehicleGatewayStateClient
    private val stateCoordinator = NovaStateCoordinator()
    private var lastActionBlocked = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        val endpoint = NovaEndpointStore.load(this)
        controlClient = NovaControlClient(endpoint, this)
        audioClient = NovaAudioClient(endpoint, this)
        player = NovaPcmPlayer(this, this)
        commandCoordinator = CommandCoordinator(
            executor = AndroidCommandExecutor(this),
            scheduler = HandlerCommandScheduler(),
            resultSink = ::onCommandResult,
        )
        vehicleGatewayStateClient = VehicleGatewayStateClient(
            context = this,
            stateSink = controlClient::sendVehicleState,
            faultSink = controlClient::sendFaultEvent,
        )

        controlClient.start()
        audioClient.start()
        vehicleGatewayStateClient.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RECONNECT && ::controlClient.isInitialized) {
            controlClient.stop()
            audioClient.stop()
            controlClient.start()
            audioClient.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (::commandCoordinator.isInitialized) commandCoordinator.shutdown()
        if (::vehicleGatewayStateClient.isInitialized) vehicleGatewayStateClient.shutdown()
        if (::player.isInitialized) player.stop()
        if (::controlClient.isInitialized) controlClient.stop()
        if (::audioClient.isInitialized) audioClient.stop()
        NovaRuntimeState.publish(NovaVisibleState.UNAVAILABLE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onControlConnectionChanged(connected: Boolean) {
        NovaRuntimeState.publish(stateCoordinator.onControlConnectionChanged(connected))
        if (connected && ::vehicleGatewayStateClient.isInitialized) {
            vehicleGatewayStateClient.publishLatest()
        }
    }

    override fun onAudioConnectionChanged(connected: Boolean) {
        NovaRuntimeState.publish(stateCoordinator.onAudioConnectionChanged(connected))
    }

    override fun onControlMessage(message: JSONObject) {
        val turnId = message.optionalText("turn_id")
        when (message.optString("type")) {
            "command_request" -> handleCommandRequest(message)
            "state" -> publishWireState(message)
            "latency" -> publishLatency(message)
            "route" -> {
                val tier = message.optionalText("tier")
                NovaRuntimeState.publishRoute(turnId, tier)
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "NOVA route turn=${turnId ?: "unknown"} tier=${tier ?: "unknown"}",
                    )
                }
            }
            "transcript" -> {
                lastActionBlocked = false
                NovaRuntimeState.publishTranscript(turnId, message.optString("text"))
                publishControlState(NovaVisibleState.PROCESSING)
            }
            "progress" -> {
                NovaRuntimeState.publishProgress(
                    turnId,
                    message.optString("text"),
                    message.optionalText("tier"),
                )
                publishControlState(NovaVisibleState.PROCESSING)
            }
            "evidence" -> NovaRuntimeState.publishEvidence(
                turnId,
                parseEvidenceCards(message),
            )
            "alert" -> {
                val active = message.optBoolean("active", false)
                val code = message.optionalText("code")
                val text = message.optionalText("text")
                    ?: if (active) "A vehicle fault is active" else "Vehicle fault cleared"
                lastActionBlocked = active
                if (active) {
                    NovaRuntimeState.publishAction(
                        turnId = turnId,
                        domain = "vehicle",
                        name = code ?: "fault",
                        result = text,
                        blocked = true,
                        errorMessage = text,
                    )
                    publishControlState(NovaVisibleState.ERROR)
                } else {
                    NovaRuntimeState.publishResult(turnId, text, success = true)
                    publishControlState(NovaVisibleState.IDLE)
                }
            }
            "action" -> {
                lastActionBlocked = message.optBoolean("blocked", false)
                NovaRuntimeState.publishAction(
                    turnId = turnId,
                    domain = message.optionalText("domain")
                        ?: actionDomain(message.optionalText("tool")),
                    name = message.optionalText("tool"),
                    result = message.optionalText("result"),
                    blocked = lastActionBlocked,
                    errorMessage = message.optionalText("reason"),
                )
                publishControlState(
                    if (lastActionBlocked) NovaVisibleState.ERROR else NovaVisibleState.EXECUTING,
                )
            }
            "result" -> {
                val success = message.optString("status") == "success"
                lastActionBlocked = !success
                NovaRuntimeState.publishResult(turnId, message.optionalText("text"), success)
                publishControlState(if (success) NovaVisibleState.SUCCESS else NovaVisibleState.ERROR)
            }
            // Actual PCM playback owns SPEAKING; the control socket records the completed result.
            "say" -> {
                NovaRuntimeState.publishResponse(turnId, message.optString("text"))
                publishControlState(
                    if (lastActionBlocked) NovaVisibleState.ERROR else NovaVisibleState.SUCCESS,
                )
            }
            "error" -> {
                lastActionBlocked = true
                NovaRuntimeState.publishError(
                    turnId,
                    message.optionalText("message") ?: getString(R.string.error_subtitle),
                )
                publishControlState(NovaVisibleState.ERROR)
            }
        }
    }

    private fun handleCommandRequest(message: JSONObject) {
        val request = try {
            CommandWireCodec.parseRequest(message)
        } catch (error: Exception) {
            val rejected = CommandWireCodec.invalidRequest(message, error)
            Log.w(TAG, "Rejected invalid command request: ${rejected.message}")
            controlClient.sendCommandResult(rejected)
            return
        }

        lastActionBlocked = false
        NovaRuntimeState.publishAction(
            turnId = request.turnId,
            domain = request.domain,
            name = request.operation,
            result = "Completing your request…",
            blocked = false,
            errorMessage = null,
        )
        publishControlState(NovaVisibleState.EXECUTING)
        commandCoordinator.submit(request)
    }

    private fun onCommandResult(result: CommandResult) {
        controlClient.sendCommandResult(result)
        val isFailure = result.status.isFinal && result.status != CommandStatus.CONFIRMED
        lastActionBlocked = isFailure
        NovaRuntimeState.publishAction(
            turnId = result.request.turnId,
            domain = result.request.domain,
            name = result.request.operation,
            result = result.message,
            blocked = isFailure,
            errorMessage = result.message.takeIf { isFailure },
        )
        when {
            !result.status.isFinal -> publishControlState(NovaVisibleState.EXECUTING)
            result.status == CommandStatus.CONFIRMED ->
                publishControlState(NovaVisibleState.SUCCESS)
            else -> publishControlState(NovaVisibleState.ERROR)
        }
    }

    override fun onAudioFrame(frame: AudioFrame) {
        when (frame.type) {
            AudioFrameType.TTS_START -> player.start(frame.streamId, frame.payload)
            AudioFrameType.TTS_PCM -> player.write(frame.streamId, frame.payload)
            AudioFrameType.TTS_END -> player.end(frame.streamId)
            AudioFrameType.AUDIO_ERROR -> {
                Log.w(TAG, "Pi audio error: ${String(frame.payload, Charsets.UTF_8)}")
                publishControlState(NovaVisibleState.ERROR)
            }
            else -> Unit
        }
    }

    override fun onPlaybackChanged(playing: Boolean, turnId: String?) {
        NovaRuntimeState.publish(stateCoordinator.onPlaybackChanged(playing))
        if (playing) {
            controlClient.sendPlayback(turnId, "started")
        } else {
            controlClient.sendPlayback(turnId, "ended")
        }
    }

    private fun publishControlState(state: NovaVisibleState) {
        NovaRuntimeState.publish(stateCoordinator.onControlState(state))
    }

    private fun publishWireState(message: JSONObject) {
        val state = when (message.optString("value").lowercase()) {
            "idle" -> NovaVisibleState.IDLE
            "listening" -> NovaVisibleState.LISTENING
            "thinking", "processing" -> NovaVisibleState.PROCESSING
            "executing" -> NovaVisibleState.EXECUTING
            "success" -> NovaVisibleState.SUCCESS
            "error" -> NovaVisibleState.ERROR
            "speaking" -> NovaVisibleState.SPEAKING
            "unavailable" -> NovaVisibleState.UNAVAILABLE
            else -> return
        }
        val followUpWindowMs = message.optLong("window_ms", 0L)
            .takeIf {
                state == NovaVisibleState.LISTENING &&
                    message.optBoolean("followup", false) &&
                    it > 0L
            }
        NovaRuntimeState.publish(
            stateCoordinator.onControlState(state),
            followUpWindowMs,
        )
    }

    private fun publishLatency(message: JSONObject) {
        if (!BuildConfig.DEBUG) return
        val turnId = message.optionalText("turn_id") ?: "unknown"
        val ttfw = message.optionalDouble("ttfw_s")?.let { "%.2fs".format(it) } ?: "n/a"
        val total = message.optionalDouble("total_s")?.let { "%.2fs".format(it) } ?: "n/a"
        Log.d(TAG, "NOVA latency turn=$turnId ttfw=$ttfw total=$total")
    }

    private fun JSONObject.optionalText(name: String): String? =
        optString(name).trim().takeIf { it.isNotEmpty() }

    private fun JSONObject.optionalDouble(name: String): Double? =
        takeIf { has(name) && !isNull(name) }
            ?.optDouble(name)
            ?.takeIf { it.isFinite() }

    private fun parseEvidenceCards(message: JSONObject): List<NovaEvidenceCard> {
        val values = message.optJSONArray("cards") ?: return emptyList()
        return buildList {
            for (position in 0 until minOf(values.length(), 4)) {
                val value = values.optJSONObject(position) ?: continue
                val title = value.optionalText("title")?.take(120) ?: continue
                val source = value.optionalText("source")?.take(40) ?: continue
                val sourceUri = value.optionalText("source_uri")
                    ?.take(1_000)
                    ?.takeIf { it.startsWith("https://") }
                add(
                    NovaEvidenceCard(
                        index = value.optInt("index", position + 1).coerceIn(1, 4),
                        title = title,
                        detail = value.optionalText("detail")?.take(180),
                        source = source,
                        sourceUri = sourceUri,
                    ),
                )
            }
        }
    }

    private fun actionDomain(tool: String?): String? = when (tool) {
        "navigate", "trip_check" -> "navigation"
        "set_climate" -> "climate"
        "media" -> "media"
        "phone" -> "phone"
        "vehicle_status", "explain_fault", "owner_manual" -> "vehicle"
        "set_lighting" -> "ambient"
        else -> null
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                getString(R.string.runtime_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun createNotification() = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
        .setSmallIcon(R.drawable.ic_nova)
        .setContentTitle(getString(R.string.runtime_notification_title))
        .setContentText(getString(R.string.runtime_notification_text))
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(
            this,
            0,
            Intent(this, NovaActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ))
        .build()

    companion object {
        const val ACTION_RECONNECT = "com.hypernova.ai.action.RECONNECT"
        private const val TAG = "NovaRuntimeService"
        private const val NOTIFICATION_CHANNEL = "nova_runtime"
        private const val NOTIFICATION_ID = 1001
    }
}
