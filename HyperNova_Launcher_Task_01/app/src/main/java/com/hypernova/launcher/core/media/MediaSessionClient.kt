package com.hypernova.launcher.core.media

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.hypernova.launcher.core.integration.AppAvailability
import com.hypernova.launcher.core.integration.AppDestination
import com.hypernova.launcher.core.integration.AppLauncher
import com.hypernova.launcher.core.integration.AppRegistry
import com.hypernova.launcher.core.state.AppConnectionState
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

/**
 * Connects the launcher to the MediaSession exposed by
 * the future HyperNova Media application.
 *
 * This class owns the MediaController connection.
 */
class MediaSessionClient(
    context: Context,
    private val appLauncher: AppLauncher,
    private val onSnapshotChanged: (MediaSessionSnapshot) -> Unit
) {

    private val applicationContext = context.applicationContext

    private val mainExecutor =
        ContextCompat.getMainExecutor(applicationContext)

    private val mainHandler = Handler(Looper.getMainLooper())

    private var controllerFuture:
            ListenableFuture<MediaController>? = null

    private var mediaController: MediaController? = null

    private var connectionGeneration = 0

    /**
     * Listen for playback and metadata changes.
     */
    private val playerListener = object : Player.Listener {

        override fun onEvents(
            player: Player,
            events: Player.Events
        ) {
            publishControllerSnapshot()
        }
    }

    /**
     * Refresh the playback position while media is playing.
     */
    private val progressRunnable = object : Runnable {

        override fun run() {
            val controller = mediaController ?: return

            publishControllerSnapshot()

            if (controller.isPlaying) {
                mainHandler.postDelayed(
                    this,
                    PROGRESS_UPDATE_INTERVAL_MS
                )
            }
        }
    }

    /**
     * Start connecting to HyperNova Media.
     */
    fun connect() {
        disconnect(notifyState = false)

        val mediaSpec = AppRegistry.get(AppDestination.MEDIA)

        val availability =
            appLauncher.getAvailability(AppDestination.MEDIA)

        if (availability == AppAvailability.NOT_INSTALLED) {
            publishState(AppConnectionState.NOT_INSTALLED)
            return
        }

        val serviceClassName = mediaSpec.serviceClassName

        if (serviceClassName.isNullOrBlank()) {
            publishState(
                connectionState = AppConnectionState.ERROR,
                errorMessage =
                    "MediaSession service class is not registered"
            )
            return
        }

        val generation = ++connectionGeneration

        publishState(AppConnectionState.CONNECTING)

        val serviceComponent = ComponentName(
            mediaSpec.packageName,
            serviceClassName
        )

        val sessionToken = SessionToken(
            applicationContext,
            serviceComponent
        )

        val newControllerFuture =
            MediaController.Builder(
                applicationContext,
                sessionToken
            )
                .setListener(
                    createControllerListener(generation)
                )
                .buildAsync()

        controllerFuture = newControllerFuture

        newControllerFuture.addListener(
            {
                if (generation != connectionGeneration) {
                    return@addListener
                }

                try {
                    val connectedController =
                        newControllerFuture.get()

                    mediaController = connectedController

                    connectedController.addListener(
                        playerListener
                    )

                    publishControllerSnapshot()
                } catch (exception: CancellationException) {
                    if (generation == connectionGeneration) {
                        publishState(
                            AppConnectionState.DISCONNECTED
                        )
                    }
                } catch (exception: ExecutionException) {
                    publishState(
                        connectionState =
                            AppConnectionState.ERROR,
                        errorMessage =
                            exception.cause?.message
                                ?: exception.message
                    )
                } catch (exception: Exception) {
                    publishState(
                        connectionState =
                            AppConnectionState.ERROR,
                        errorMessage = exception.message
                    )
                }
            },
            mainExecutor
        )
    }

    /**
     * Disconnect and release the MediaController.
     */
    fun disconnect() {
        disconnect(notifyState = true)
    }

    /**
     * Play or pause the connected MediaSession.
     *
     * Returns true when the command was sent.
     */
    fun playOrPause(): Boolean {
        val controller = mediaController ?: return false

        if (
            !controller.isCommandAvailable(
                Player.COMMAND_PLAY_PAUSE
            )
        ) {
            return false
        }

        if (controller.isPlaying) {
            controller.pause()
        } else {
            controller.play()
        }

        return true
    }

    /**
     * Request the previous media item.
     */
    fun skipToPrevious(): Boolean {
        val controller = mediaController ?: return false

        if (
            !controller.isCommandAvailable(
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
            )
        ) {
            return false
        }

        controller.seekToPreviousMediaItem()

        return true
    }

    /**
     * Request the next media item.
     */
    fun skipToNext(): Boolean {
        val controller = mediaController ?: return false

        if (
            !controller.isCommandAvailable(
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
            )
        ) {
            return false
        }

        controller.seekToNextMediaItem()

        return true
    }

    /**
     * Return whether a MediaController is currently connected.
     */
    fun isConnected(): Boolean {
        return mediaController != null
    }

    private fun createControllerListener(
        generation: Int
    ): MediaController.Listener {
        return object : MediaController.Listener {

            override fun onDisconnected(
                controller: MediaController
            ) {
                if (generation != connectionGeneration) {
                    return
                }

                mainHandler.removeCallbacks(progressRunnable)

                mediaController?.removeListener(playerListener)
                mediaController = null
                controllerFuture = null

                publishState(
                    AppConnectionState.DISCONNECTED
                )
            }
        }
    }

    /**
     * Read the latest metadata and playback state.
     */
    private fun publishControllerSnapshot() {
        val controller = mediaController ?: return

        val currentMediaItem = controller.currentMediaItem
        val metadata = currentMediaItem?.mediaMetadata

        val durationMs = controller.duration
            .takeIf {
                it != C.TIME_UNSET && it > 0L
            }
            ?: 0L

        val rawPositionMs =
            controller.currentPosition.coerceAtLeast(0L)

        val positionMs =
            if (durationMs > 0L) {
                rawPositionMs.coerceAtMost(durationMs)
            } else {
                rawPositionMs
            }

        val snapshot = MediaSessionSnapshot(
            connectionState = AppConnectionState.READY,
            hasActiveSession = true,
            hasActiveMediaItem = currentMediaItem != null,
            title = metadata
                ?.title
                ?.toString()
                ?.takeIf { it.isNotBlank() },
            artist = metadata
                ?.artist
                ?.toString()
                ?.takeIf { it.isNotBlank() },
            artworkUri = metadata?.artworkUri,
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = controller.isPlaying,
            canPlayPause = controller.isCommandAvailable(
                Player.COMMAND_PLAY_PAUSE
            ),
            canSkipPrevious = controller.isCommandAvailable(
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
            ),
            canSkipNext = controller.isCommandAvailable(
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
            )
        )

        onSnapshotChanged(snapshot)

        scheduleProgressUpdates(controller.isPlaying)
    }

    private fun publishState(
        connectionState: AppConnectionState,
        errorMessage: String? = null
    ) {
        mainHandler.removeCallbacks(progressRunnable)

        onSnapshotChanged(
            MediaSessionSnapshot.empty(
                connectionState = connectionState,
                errorMessage = errorMessage
            )
        )
    }

    private fun scheduleProgressUpdates(
        isPlaying: Boolean
    ) {
        mainHandler.removeCallbacks(progressRunnable)

        if (isPlaying) {
            mainHandler.postDelayed(
                progressRunnable,
                PROGRESS_UPDATE_INTERVAL_MS
            )
        }
    }

    private fun disconnect(
        notifyState: Boolean
    ) {
        connectionGeneration++

        mainHandler.removeCallbacks(progressRunnable)

        mediaController?.removeListener(playerListener)
        mediaController = null

        controllerFuture?.let { future ->
            MediaController.releaseFuture(future)
        }

        controllerFuture = null

        if (notifyState) {
            publishState(
                AppConnectionState.DISCONNECTED
            )
        }
    }

    companion object {
        private const val PROGRESS_UPDATE_INTERVAL_MS =
            1000L
    }
}