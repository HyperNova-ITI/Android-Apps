package com.hypernova.ai.command

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.hypernova.contracts.HyperNovaContract
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

/** Controls the existing HyperNova Media app through its exported Media3 MediaSession. */
class MediaCommandClient(context: Context) {
    private data class Pending(val request: CommandRequest, val sink: (CommandResult) -> Unit)

    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val queued = linkedMapOf<String, Pending>()
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var stopped = false

    fun execute(request: CommandRequest, sink: (CommandResult) -> Unit) {
        mainExecutor.execute {
            if (stopped) {
                sink(request.unavailable("Media client is stopped"))
                return@execute
            }
            val connected = controller
            if (connected != null && connected.isConnected) {
                dispatch(connected, request, sink)
            } else {
                queued[request.requestId] = Pending(request, sink)
                ensureConnected()
            }
        }
    }

    fun shutdown() {
        mainExecutor.execute {
            stopped = true
            failQueued("Media client stopped")
            controllerFuture?.let(MediaController::releaseFuture)
            controllerFuture = null
            controller?.release()
            controller = null
        }
    }

    private fun ensureConnected() {
        if (stopped || controllerFuture != null) return
        val token = SessionToken(
            appContext,
            ComponentName(MediaWireContract.PACKAGE_NAME, MediaWireContract.SESSION_SERVICE),
        )
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (stopped) {
                    MediaController.releaseFuture(future)
                    return@addListener
                }
                try {
                    val connected = future.get()
                    controller = connected
                    controllerFuture = null
                    val waiting = queued.values.toList()
                    queued.clear()
                    waiting.forEach { dispatch(connected, it.request, it.sink) }
                } catch (_: CancellationException) {
                    controllerFuture = null
                    failQueued("Media connection was cancelled")
                } catch (error: ExecutionException) {
                    controllerFuture = null
                    Log.w(TAG, "MediaSession connection failed", error.cause ?: error)
                    failQueued("HyperNova MediaSession is unavailable")
                } catch (error: Exception) {
                    controllerFuture = null
                    Log.w(TAG, "MediaSession connection failed", error)
                    failQueued("HyperNova MediaSession is unavailable")
                }
            },
            mainExecutor,
        )
    }

    private fun dispatch(
        target: MediaController,
        request: CommandRequest,
        sink: (CommandResult) -> Unit,
    ) {
        try {
            when (request.operation) {
                MediaWireContract.OP_GET_CURRENT_STATE -> sink(confirmed(request, "Current media state", state(target)))
                MediaWireContract.OP_PLAY -> {
                    if (target.mediaItemCount == 0) {
                        sink(request.failure(
                            CommandStatus.UNAVAILABLE,
                            "Choose a station or track in HyperNova Media first",
                            HyperNovaContract.ERROR_SERVICE_UNAVAILABLE,
                        ))
                    } else {
                        target.prepare()
                        target.play()
                        sink(confirmed(request, "Media playback started", state(target)))
                    }
                }
                MediaWireContract.OP_PAUSE -> {
                    target.pause()
                    sink(confirmed(request, "Media paused", state(target)))
                }
                MediaWireContract.OP_NEXT -> {
                    if (!target.hasNextMediaItem()) {
                        sink(request.failure(
                            CommandStatus.REJECTED,
                            "There is no next media item",
                            HyperNovaContract.ERROR_INVALID_ARGUMENT,
                        ))
                    } else {
                        target.seekToNextMediaItem()
                        target.play()
                        sink(confirmed(request, "Playing the next item", state(target)))
                    }
                }
                MediaWireContract.OP_PREVIOUS -> {
                    target.seekToPreviousMediaItem()
                    target.play()
                    sink(confirmed(request, "Playing the previous item", state(target)))
                }
                MediaWireContract.OP_SET_VOLUME -> {
                    val percent = (request.arguments as CommandArguments.VolumePercent).percent
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val index = ((percent / 100.0) * max).toInt().coerceIn(0, max)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
                    sink(confirmed(request, "Media volume set to $percent percent", state(target)))
                }
                MediaWireContract.OP_PLAY_RADIO -> playRadio(target, request, sink)
                else -> sink(request.failure(
                    CommandStatus.REJECTED,
                    "Unsupported Media operation: ${request.operation}",
                    HyperNovaContract.ERROR_UNSUPPORTED_OPERATION,
                ))
            }
        } catch (error: Exception) {
            Log.w(TAG, "Media command failed", error)
            sink(request.unavailable("HyperNova MediaSession is unavailable"))
        }
    }

    private fun playRadio(
        target: MediaController,
        request: CommandRequest,
        sink: (CommandResult) -> Unit,
    ) {
        val query = (request.arguments as CommandArguments.RadioQuery).query
        val command = SessionCommand(MediaWireContract.ACTION_PLAY_RADIO, Bundle.EMPTY)
        if (!target.availableSessionCommands.contains(command)) {
            sink(request.failure(
                CommandStatus.UNAVAILABLE,
                "Installed HyperNova Media does not support radio selection",
                HyperNovaContract.ERROR_UNSUPPORTED_OPERATION,
            ))
            return
        }
        val future = target.sendCustomCommand(
            command,
            Bundle().apply { putString(MediaWireContract.EXTRA_QUERY, query) },
        )
        future.addListener(
            {
                try {
                    val result = future.get()
                    val message = result.extras
                        .getString(MediaWireContract.EXTRA_MESSAGE)
                        ?.takeIf(String::isNotBlank)
                        ?: "Radio selection failed"
                    if (result.resultCode != SessionResult.RESULT_SUCCESS) {
                        sink(request.failure(
                            CommandStatus.UNAVAILABLE,
                            message,
                            HyperNovaContract.ERROR_SERVICE_UNAVAILABLE,
                        ))
                    } else {
                        awaitRadioPlayback(
                            target = target,
                            request = request,
                            stationName = result.extras
                                .getString(MediaWireContract.EXTRA_STATION_NAME)
                                .orEmpty(),
                            sink = sink,
                        )
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "Radio selection command failed", error)
                    sink(request.unavailable("HyperNova radio selection is unavailable"))
                }
            },
            mainExecutor,
        )
    }

    private fun awaitRadioPlayback(
        target: MediaController,
        request: CommandRequest,
        stationName: String,
        sink: (CommandResult) -> Unit,
    ) {
        var completed = false
        lateinit var listener: Player.Listener
        lateinit var timeout: Runnable

        fun finish(result: CommandResult) {
            if (completed) return
            completed = true
            mainHandler.removeCallbacks(timeout)
            target.removeListener(listener)
            sink(result)
        }

        fun inspect() {
            val radioSelected = target.currentMediaItem?.mediaId?.startsWith("radio:") == true
            if (radioSelected && target.isPlaying) {
                finish(confirmed(
                    request,
                    if (stationName.isBlank()) "Radio playback started"
                    else "Playing $stationName",
                    state(target) + mapOf("station_name" to stationName).filterValues(String::isNotBlank),
                ))
            } else if (target.playerError != null) {
                finish(request.unavailable("The selected radio station could not start"))
            }
        }

        listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = inspect()
            override fun onPlaybackStateChanged(playbackState: Int) = inspect()
            override fun onPlayerError(error: PlaybackException) = inspect()
        }
        timeout = Runnable {
            finish(request.failure(
                CommandStatus.TIMEOUT,
                "The selected radio station did not start in time",
                HyperNovaContract.ERROR_TIMEOUT,
            ))
        }
        target.addListener(listener)
        mainHandler.postDelayed(timeout, RADIO_START_TIMEOUT_MILLIS)
        inspect()
    }

    private fun state(target: MediaController): Map<String, Any?> {
        val metadata: MediaMetadata = target.mediaMetadata
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return linkedMapOf(
            "media_id" to target.currentMediaItem?.mediaId,
            "playback_state" to when {
                target.isPlaying -> "playing"
                target.playbackState == Player.STATE_BUFFERING -> "buffering"
                target.playbackState == Player.STATE_ENDED -> "ended"
                target.playbackState == Player.STATE_IDLE -> "idle"
                else -> "paused"
            },
            "title" to metadata.title?.toString(),
            "artist" to metadata.artist?.toString(),
            "position_ms" to target.currentPosition.coerceAtLeast(0L),
            "duration_ms" to target.duration.takeIf { it >= 0L },
            "volume_percent" to ((current * 100.0) / max).toInt(),
        ).filterValues { it != null }
    }

    private fun confirmed(
        request: CommandRequest,
        message: String,
        data: Map<String, Any?>,
    ) = CommandResult(request, CommandStatus.CONFIRMED, message, data = data)

    private fun failQueued(message: String) {
        val failed = queued.values.toList()
        queued.clear()
        failed.forEach { it.sink(it.request.unavailable(message)) }
    }

    private fun CommandRequest.unavailable(message: String) = failure(
        status = CommandStatus.UNAVAILABLE,
        message = message,
        errorCode = HyperNovaContract.ERROR_SERVICE_UNAVAILABLE,
    )

    private companion object {
        const val TAG = "MediaCommandClient"
        const val RADIO_START_TIMEOUT_MILLIS = 8_000L
    }
}
