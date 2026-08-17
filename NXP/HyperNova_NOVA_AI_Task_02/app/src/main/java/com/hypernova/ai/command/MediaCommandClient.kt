package com.hypernova.ai.command

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.hypernova.contracts.HyperNovaContract
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

/** Controls the existing HyperNova Media app through its exported Media3 MediaSession. */
class MediaCommandClient(context: Context) {
    private data class Pending(val request: CommandRequest, val sink: (CommandResult) -> Unit)

    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
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

    private fun state(target: MediaController): Map<String, Any?> {
        val metadata: MediaMetadata = target.mediaMetadata
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return linkedMapOf(
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
    }
}
