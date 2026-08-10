package com.hypernova.launcher.core.media

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController as PlatformMediaController
import android.media.session.PlaybackState as PlatformPlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
 * Unified media client used by HyperNova Launcher HOME.
 *
 * Sources:
 * 1) HyperNova Media Media3 session (Radio / USB / app-owned playback).
 * 2) Android Bluetooth AVRCP MediaBrowserService (phone Bluetooth playback).
 *
 * The Launcher publishes whichever source is really active, so Bluetooth
 * metadata can replace stale Radio metadata on HOME while phone audio is playing.
 */
class MediaSessionClient(
    context: Context,
    private val appLauncher: AppLauncher,
    private val onSnapshotChanged: (MediaSessionSnapshot) -> Unit
) {

    private enum class ActiveSource {
        HYPERNOVA,
        BLUETOOTH
    }

    private val applicationContext = context.applicationContext

    private val mainExecutor =
        ContextCompat.getMainExecutor(applicationContext)

    private val mainHandler =
        Handler(Looper.getMainLooper())

    /*
     * HyperNova Media3 session.
     */
    private var controllerFuture:
            ListenableFuture<MediaController>? = null

    private var mediaController: MediaController? = null

    /*
     * Bluetooth AVRCP platform MediaBrowser / MediaController.
     */
    private var bluetoothBrowser: MediaBrowser? = null

    private var bluetoothController:
            PlatformMediaController? = null

    private var connectionGeneration = 0

    private var shouldStayConnected = false

    private var lastActiveSource =
        ActiveSource.HYPERNOVA

    /**
     * HyperNova Media events.
     */
    private val playerListener =
        object : Player.Listener {

            override fun onEvents(
                player: Player,
                events: Player.Events
            ) {
                if (
                    player.isPlaying ||
                    player.playbackState == Player.STATE_BUFFERING
                ) {
                    lastActiveSource =
                        ActiveSource.HYPERNOVA
                }

                publishBestSnapshot()
            }
        }

    /**
     * Bluetooth AVRCP events.
     */
    private val bluetoothControllerCallback =
        object : PlatformMediaController.Callback() {

            override fun onMetadataChanged(
                metadata: MediaMetadata?
            ) {
                publishBestSnapshot()
            }

            override fun onPlaybackStateChanged(
                state: PlatformPlaybackState?
            ) {
                if (state.isBluetoothActive()) {
                    lastActiveSource =
                        ActiveSource.BLUETOOTH
                }

                publishBestSnapshot()
            }

            override fun onSessionDestroyed() {
                clearBluetoothController()
                publishBestSnapshot()
                scheduleBluetoothReconnect()
            }
        }

    /**
     * Progress refresh for both Media3 and Bluetooth sources.
     */
    private val progressRunnable =
        object : Runnable {

            override fun run() {
                publishBestSnapshot()
            }
        }

    /**
     * Retry Bluetooth browser connection because the Bluetooth service can
     * appear after Launcher starts or after a Bluetooth stack restart.
     */
    private val bluetoothRetryRunnable =
        object : Runnable {

            override fun run() {
                if (
                    shouldStayConnected &&
                    bluetoothController == null &&
                    bluetoothBrowser == null
                ) {
                    connectBluetoothSession(
                        connectionGeneration
                    )
                }
            }
        }

    /**
     * Connect Launcher to both media sources.
     */
    fun connect() {
        disconnect(notifyState = false)

        shouldStayConnected = true

        val generation =
            ++connectionGeneration

        publishState(
            AppConnectionState.CONNECTING
        )

        connectBluetoothSession(generation)
        connectHyperNovaSession(generation)
    }

    /**
     * Connect to HyperNova Media3 MediaSessionService.
     */
    private fun connectHyperNovaSession(
        generation: Int
    ) {
        val mediaSpec =
            AppRegistry.get(AppDestination.MEDIA)

        val availability =
            appLauncher.getAvailability(
                AppDestination.MEDIA
            )

        if (
            availability ==
            AppAvailability.NOT_INSTALLED
        ) {
            publishStateIfNoOtherSession(
                AppConnectionState.NOT_INSTALLED
            )
            return
        }

        if (
            availability ==
            AppAvailability.ERROR
        ) {
            publishStateIfNoOtherSession(
                AppConnectionState.ERROR,
                "Media availability unavailable"
            )
            return
        }

        val serviceClassName =
            mediaSpec.serviceClassName

        if (serviceClassName.isNullOrBlank()) {
            publishStateIfNoOtherSession(
                AppConnectionState.ERROR,
                "MediaSession service class is not registered"
            )
            return
        }

        val serviceComponent =
            ComponentName(
                mediaSpec.packageName,
                serviceClassName
            )

        val serviceExists =
            try {
                applicationContext.packageManager
                    .getServiceInfo(
                        serviceComponent,
                        PackageManager
                            .ComponentInfoFlags
                            .of(0L)
                    )
                true
            } catch (
                _: PackageManager.NameNotFoundException
            ) {
                false
            } catch (_: SecurityException) {
                false
            }

        if (!serviceExists) {
            publishStateIfNoOtherSession(
                AppConnectionState.DISCONNECTED
            )
            return
        }

        val sessionToken =
            SessionToken(
                applicationContext,
                serviceComponent
            )

        val newControllerFuture =
            MediaController.Builder(
                applicationContext,
                sessionToken
            )
                .setListener(
                    createControllerListener(
                        generation
                    )
                )
                .buildAsync()

        controllerFuture =
            newControllerFuture

        newControllerFuture.addListener(
            {
                if (
                    generation !=
                    connectionGeneration
                ) {
                    return@addListener
                }

                try {
                    val connectedController =
                        newControllerFuture.get()

                    mediaController =
                        connectedController

                    connectedController.addListener(
                        playerListener
                    )

                    if (
                        connectedController.isPlaying ||
                        connectedController.playbackState ==
                        Player.STATE_BUFFERING
                    ) {
                        lastActiveSource =
                            ActiveSource.HYPERNOVA
                    }

                    Log.i(
                        TAG,
                        "Connected to HyperNova MediaSession"
                    )

                    publishBestSnapshot()
                } catch (
                    exception: CancellationException
                ) {
                    if (
                        generation ==
                        connectionGeneration
                    ) {
                        publishStateIfNoOtherSession(
                            AppConnectionState.DISCONNECTED
                        )
                    }
                } catch (
                    exception: ExecutionException
                ) {
                    publishStateIfNoOtherSession(
                        AppConnectionState.ERROR,
                        exception.cause?.message
                            ?: exception.message
                    )
                } catch (
                    exception: Exception
                ) {
                    publishStateIfNoOtherSession(
                        AppConnectionState.ERROR,
                        exception.message
                    )
                }
            },
            mainExecutor
        )
    }

    /**
     * Connect directly to the AOSP Bluetooth AVRCP MediaBrowserService
     * confirmed on the target:
     *
     * com.android.bluetooth/
     * com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService
     */
    private fun connectBluetoothSession(
        generation: Int
    ) {
        if (
            !shouldStayConnected ||
            generation != connectionGeneration
        ) {
            return
        }

        mainHandler.removeCallbacks(
            bluetoothRetryRunnable
        )

        disconnectBluetoothBrowserOnly()

        val component =
            ComponentName(
                BLUETOOTH_PACKAGE,
                BLUETOOTH_MEDIA_BROWSER_SERVICE
            )

        lateinit var newBrowser: MediaBrowser

        val callback =
            object : MediaBrowser.ConnectionCallback() {

                override fun onConnected() {
                    if (
                        generation !=
                        connectionGeneration ||
                        bluetoothBrowser !== newBrowser
                    ) {
                        try {
                            newBrowser.disconnect()
                        } catch (_: Exception) {
                            // Ignore stale connection cleanup.
                        }
                        return
                    }

                    try {
                        clearBluetoothController()

                        val controller =
                            PlatformMediaController(
                                applicationContext,
                                newBrowser.sessionToken
                            )

                        controller.registerCallback(
                            bluetoothControllerCallback,
                            mainHandler
                        )

                        bluetoothController =
                            controller

                        if (
                            controller.playbackState
                                .isBluetoothActive()
                        ) {
                            lastActiveSource =
                                ActiveSource.BLUETOOTH
                        }

                        Log.i(
                            TAG,
                            "Connected to Bluetooth AVRCP MediaSession"
                        )

                        publishBestSnapshot()
                    } catch (
                        exception: Exception
                    ) {
                        Log.e(
                            TAG,
                            "Failed creating Bluetooth MediaController",
                            exception
                        )

                        clearBluetoothController()
                        disconnectBluetoothBrowserOnly()
                        publishBestSnapshot()
                        scheduleBluetoothReconnect()
                    }
                }

                override fun onConnectionSuspended() {
                    if (
                        generation !=
                        connectionGeneration
                    ) {
                        return
                    }

                    Log.w(
                        TAG,
                        "Bluetooth MediaBrowser suspended"
                    )

                    clearBluetoothController()
                    disconnectBluetoothBrowserOnly()
                    publishBestSnapshot()
                    scheduleBluetoothReconnect()
                }

                override fun onConnectionFailed() {
                    if (
                        generation !=
                        connectionGeneration
                    ) {
                        return
                    }

                    Log.w(
                        TAG,
                        "Bluetooth MediaBrowser connection failed"
                    )

                    clearBluetoothController()
                    disconnectBluetoothBrowserOnly()
                    publishBestSnapshot()
                    scheduleBluetoothReconnect()
                }
            }

        newBrowser =
            MediaBrowser(
                applicationContext,
                component,
                callback,
                null
            )

        bluetoothBrowser =
            newBrowser

        try {
            newBrowser.connect()
        } catch (exception: Exception) {
            Log.w(
                TAG,
                "Bluetooth MediaBrowser connect exception",
                exception
            )

            disconnectBluetoothBrowserOnly()
            scheduleBluetoothReconnect()
        }
    }

    /**
     * Disconnect and release both media connections.
     */
    fun disconnect() {
        disconnect(notifyState = true)
    }

    /**
     * Play or pause the source currently shown on HOME.
     */
    fun playOrPause(): Boolean {
        return when (selectControlSource()) {
            ActiveSource.BLUETOOTH ->
                playOrPauseBluetooth()

            ActiveSource.HYPERNOVA ->
                playOrPauseHyperNova()

            null ->
                false
        }
    }

    /**
     * Previous item on the source currently shown on HOME.
     */
    fun skipToPrevious(): Boolean {
        return when (selectControlSource()) {
            ActiveSource.BLUETOOTH ->
                skipBluetoothPrevious()

            ActiveSource.HYPERNOVA ->
                skipHyperNovaPrevious()

            null ->
                false
        }
    }

    /**
     * Next item on the source currently shown on HOME.
     */
    fun skipToNext(): Boolean {
        return when (selectControlSource()) {
            ActiveSource.BLUETOOTH ->
                skipBluetoothNext()

            ActiveSource.HYPERNOVA ->
                skipHyperNovaNext()

            null ->
                false
        }
    }

    /**
     * Connected to either HyperNova Media or Bluetooth MediaSession.
     */
    fun isConnected(): Boolean {
        return (
            mediaController != null ||
            bluetoothController != null
        )
    }

    private fun playOrPauseHyperNova():
            Boolean {

        val controller =
            mediaController ?: return false

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

    private fun skipHyperNovaPrevious():
            Boolean {

        val controller =
            mediaController ?: return false

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

    private fun skipHyperNovaNext():
            Boolean {

        val controller =
            mediaController ?: return false

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

    private fun playOrPauseBluetooth():
            Boolean {

        val controller =
            bluetoothController ?: return false

        val state =
            controller.playbackState

        val actions =
            state?.actions ?: 0L

        if (
            state?.state ==
            PlatformPlaybackState.STATE_PLAYING
        ) {
            if (
                actions and
                (
                    PlatformPlaybackState.ACTION_PAUSE or
                    PlatformPlaybackState.ACTION_PLAY_PAUSE
                ) == 0L
            ) {
                return false
            }

            controller.transportControls.pause()
        } else {
            if (
                actions and
                (
                    PlatformPlaybackState.ACTION_PLAY or
                    PlatformPlaybackState.ACTION_PLAY_PAUSE
                ) == 0L
            ) {
                return false
            }

            controller.transportControls.play()
        }

        return true
    }

    private fun skipBluetoothPrevious():
            Boolean {

        val controller =
            bluetoothController ?: return false

        val actions =
            controller.playbackState
                ?.actions ?: 0L

        if (
            actions and
            PlatformPlaybackState
                .ACTION_SKIP_TO_PREVIOUS == 0L
        ) {
            return false
        }

        controller.transportControls
            .skipToPrevious()

        return true
    }

    private fun skipBluetoothNext():
            Boolean {

        val controller =
            bluetoothController ?: return false

        val actions =
            controller.playbackState
                ?.actions ?: 0L

        if (
            actions and
            PlatformPlaybackState
                .ACTION_SKIP_TO_NEXT == 0L
        ) {
            return false
        }

        controller.transportControls
            .skipToNext()

        return true
    }

    /**
     * Select which source HOME should display.
     *
     * Priority:
     * - Actively playing Bluetooth.
     * - Actively playing HyperNova Media.
     * - Most recently active source.
     * - Any remaining connected source.
     */
    private fun selectDisplaySource():
            ActiveSource? {

        if (
            bluetoothController
                ?.playbackState
                .isBluetoothActive()
        ) {
            lastActiveSource =
                ActiveSource.BLUETOOTH
            return ActiveSource.BLUETOOTH
        }

        if (
            mediaController
                .isHyperNovaActive()
        ) {
            lastActiveSource =
                ActiveSource.HYPERNOVA
            return ActiveSource.HYPERNOVA
        }

        when (lastActiveSource) {
            ActiveSource.BLUETOOTH -> {
                if (bluetoothController != null) {
                    return ActiveSource.BLUETOOTH
                }
            }

            ActiveSource.HYPERNOVA -> {
                if (mediaController != null) {
                    return ActiveSource.HYPERNOVA
                }
            }
        }

        if (bluetoothController != null) {
            return ActiveSource.BLUETOOTH
        }

        if (mediaController != null) {
            return ActiveSource.HYPERNOVA
        }

        return null
    }

    private fun selectControlSource():
            ActiveSource? {
        return selectDisplaySource()
    }

    /**
     * Publish the best currently active source to LauncherStateController.
     */
    private fun publishBestSnapshot() {
        when (selectDisplaySource()) {
            ActiveSource.BLUETOOTH -> {
                val controller =
                    bluetoothController

                if (controller != null) {
                    publishBluetoothSnapshot(
                        controller
                    )
                    return
                }
            }

            ActiveSource.HYPERNOVA -> {
                val controller =
                    mediaController

                if (controller != null) {
                    publishHyperNovaSnapshot(
                        controller
                    )
                    return
                }
            }

            null -> Unit
        }

        publishState(
            AppConnectionState.CONNECTING
        )
    }

    /**
     * HyperNova Media3 snapshot.
     */
    private fun publishHyperNovaSnapshot(
        controller: MediaController
    ) {
        val currentMediaItem =
            controller.currentMediaItem

        val metadata =
            currentMediaItem?.mediaMetadata

        val durationMs =
            controller.duration
                .takeIf {
                    it != C.TIME_UNSET &&
                        it > 0L
                }
                ?: 0L

        val rawPositionMs =
            controller.currentPosition
                .coerceAtLeast(0L)

        val positionMs =
            if (durationMs > 0L) {
                rawPositionMs.coerceAtMost(
                    durationMs
                )
            } else {
                rawPositionMs
            }

        val playerError =
            controller.playerError

        val snapshot =
            MediaSessionSnapshot(
                connectionState =
                    if (playerError == null) {
                        AppConnectionState.READY
                    } else {
                        AppConnectionState.ERROR
                    },
                playbackState =
                    controller.toPlaybackState(),
                hasActiveSession = true,
                hasActiveMediaItem =
                    currentMediaItem != null,
                title =
                    metadata
                        ?.title
                        ?.toString()
                        ?.takeIf {
                            it.isNotBlank()
                        },
                artist =
                    metadata
                        ?.artist
                        ?.toString()
                        ?.takeIf {
                            it.isNotBlank()
                        },
                artworkUri =
                    metadata?.artworkUri,
                positionMs =
                    positionMs,
                durationMs =
                    durationMs,
                isPlaying =
                    controller.isPlaying,
                canPlayPause =
                    controller.isCommandAvailable(
                        Player.COMMAND_PLAY_PAUSE
                    ),
                canSkipPrevious =
                    controller.isCommandAvailable(
                        Player
                            .COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                    ),
                canSkipNext =
                    controller.isCommandAvailable(
                        Player
                            .COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                    ),
                errorMessage =
                    playerError?.message
            )

        onSnapshotChanged(snapshot)

        scheduleProgressUpdates(
            controller.isPlaying
        )
    }

    /**
     * Bluetooth AVRCP snapshot.
     */
    private fun publishBluetoothSnapshot(
        controller: PlatformMediaController
    ) {
        val metadata =
            controller.metadata

        val state =
            controller.playbackState

        val description =
            metadata?.description

        val durationMs =
            metadata
                ?.getLong(
                    MediaMetadata
                        .METADATA_KEY_DURATION
                )
                ?.takeIf { it > 0L }
                ?: 0L

        val positionMs =
            calculateBluetoothPosition(
                state,
                durationMs
            )

        val title =
            description
                ?.title
                ?.toString()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: metadata.firstMetadataText(
                    MediaMetadata
                        .METADATA_KEY_TITLE,
                    MediaMetadata
                        .METADATA_KEY_DISPLAY_TITLE
                )
                ?: BLUETOOTH_FALLBACK_TITLE

        val artist =
            description
                ?.subtitle
                ?.toString()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: metadata.firstMetadataText(
                    MediaMetadata
                        .METADATA_KEY_ARTIST,
                    MediaMetadata
                        .METADATA_KEY_ALBUM_ARTIST,
                    MediaMetadata
                        .METADATA_KEY_DISPLAY_SUBTITLE
                )
                ?: BLUETOOTH_FALLBACK_ARTIST

        val artworkUri =
            description?.iconUri
                ?: metadata.firstMetadataUri(
                    MediaMetadata
                        .METADATA_KEY_ART_URI,
                    MediaMetadata
                        .METADATA_KEY_ALBUM_ART_URI,
                    MediaMetadata
                        .METADATA_KEY_DISPLAY_ICON_URI
                )

        val actions =
            state?.actions ?: 0L

        val isPlaying =
            state?.state ==
            PlatformPlaybackState.STATE_PLAYING

        val snapshot =
            MediaSessionSnapshot(
                connectionState =
                    AppConnectionState.READY,
                playbackState =
                    state.toMediaPlaybackState(),
                hasActiveSession = true,
                hasActiveMediaItem =
                    metadata != null ||
                    state != null,
                title = title,
                artist = artist,
                artworkUri = artworkUri,
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                canPlayPause =
                    actions and
                    (
                        PlatformPlaybackState.ACTION_PLAY or
                        PlatformPlaybackState.ACTION_PAUSE or
                        PlatformPlaybackState.ACTION_PLAY_PAUSE
                    ) != 0L,
                canSkipPrevious =
                    actions and
                    PlatformPlaybackState
                        .ACTION_SKIP_TO_PREVIOUS != 0L,
                canSkipNext =
                    actions and
                    PlatformPlaybackState
                        .ACTION_SKIP_TO_NEXT != 0L,
                errorMessage = null
            )

        onSnapshotChanged(snapshot)

        scheduleProgressUpdates(isPlaying)
    }

    private fun MediaController
        .toPlaybackState():
            MediaPlaybackState {

        if (playerError != null) {
            return MediaPlaybackState.ERROR
        }

        return when (playbackState) {
            Player.STATE_BUFFERING ->
                MediaPlaybackState.BUFFERING

            Player.STATE_READY ->
                if (isPlaying) {
                    MediaPlaybackState.PLAYING
                } else if (
                    currentMediaItem != null
                ) {
                    MediaPlaybackState.PAUSED
                } else {
                    MediaPlaybackState.IDLE
                }

            Player.STATE_ENDED ->
                MediaPlaybackState.ENDED

            Player.STATE_IDLE ->
                if (
                    currentMediaItem != null
                ) {
                    MediaPlaybackState.STOPPED
                } else {
                    MediaPlaybackState.IDLE
                }

            else ->
                MediaPlaybackState.IDLE
        }
    }

    private fun PlatformPlaybackState?
        .toMediaPlaybackState():
            MediaPlaybackState {

        return when (this?.state) {
            PlatformPlaybackState
                .STATE_PLAYING,
            PlatformPlaybackState
                .STATE_FAST_FORWARDING,
            PlatformPlaybackState
                .STATE_REWINDING ->
                MediaPlaybackState.PLAYING

            PlatformPlaybackState
                .STATE_BUFFERING,
            PlatformPlaybackState
                .STATE_CONNECTING,
            PlatformPlaybackState
                .STATE_SKIPPING_TO_NEXT,
            PlatformPlaybackState
                .STATE_SKIPPING_TO_PREVIOUS,
            PlatformPlaybackState
                .STATE_SKIPPING_TO_QUEUE_ITEM ->
                MediaPlaybackState.BUFFERING

            PlatformPlaybackState
                .STATE_PAUSED ->
                MediaPlaybackState.PAUSED

            PlatformPlaybackState
                .STATE_STOPPED ->
                MediaPlaybackState.STOPPED

            PlatformPlaybackState
                .STATE_ERROR ->
                MediaPlaybackState.ERROR

            PlatformPlaybackState
                .STATE_NONE,
            null ->
                MediaPlaybackState.IDLE

            else ->
                MediaPlaybackState.IDLE
        }
    }

    private fun PlatformPlaybackState?
        .isBluetoothActive():
            Boolean {

        return when (this?.state) {
            PlatformPlaybackState
                .STATE_PLAYING,
            PlatformPlaybackState
                .STATE_BUFFERING,
            PlatformPlaybackState
                .STATE_CONNECTING,
            PlatformPlaybackState
                .STATE_FAST_FORWARDING,
            PlatformPlaybackState
                .STATE_REWINDING,
            PlatformPlaybackState
                .STATE_SKIPPING_TO_NEXT,
            PlatformPlaybackState
                .STATE_SKIPPING_TO_PREVIOUS,
            PlatformPlaybackState
                .STATE_SKIPPING_TO_QUEUE_ITEM ->
                true

            else ->
                false
        }
    }

    private fun MediaController?
        .isHyperNovaActive():
            Boolean {

        val controller =
            this ?: return false

        return (
            controller.isPlaying ||
            controller.playbackState ==
            Player.STATE_BUFFERING
        )
    }

    private fun MediaMetadata?
        .firstMetadataText(
            vararg keys: String
        ): String? {

        val metadata =
            this ?: return null

        keys.forEach { key ->
            val value =
                metadata
                    .getText(key)
                    ?.toString()
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }

            if (value != null) {
                return value
            }
        }

        return null
    }

    private fun MediaMetadata?
        .firstMetadataUri(
            vararg keys: String
        ): Uri? {

        val metadata =
            this ?: return null

        keys.forEach { key ->
            val value =
                metadata
                    .getString(key)
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }

            if (value != null) {
                return try {
                    Uri.parse(value)
                } catch (_: Exception) {
                    null
                }
            }
        }

        return null
    }

    private fun calculateBluetoothPosition(
        state: PlatformPlaybackState?,
        durationMs: Long
    ): Long {
        if (state == null) {
            return 0L
        }

        var position =
            state.position.coerceAtLeast(0L)

        if (
            state.state ==
            PlatformPlaybackState.STATE_PLAYING &&
            state.lastPositionUpdateTime > 0L
        ) {
            val elapsed =
                (
                    SystemClock.elapsedRealtime() -
                    state.lastPositionUpdateTime
                ).coerceAtLeast(0L)

            position +=
                (
                    elapsed *
                    state.playbackSpeed
                ).toLong()
        }

        return if (durationMs > 0L) {
            position.coerceIn(
                0L,
                durationMs
            )
        } else {
            position.coerceAtLeast(0L)
        }
    }

    private fun createControllerListener(
        generation: Int
    ): MediaController.Listener {
        return object :
            MediaController.Listener {

            override fun onDisconnected(
                controller: MediaController
            ) {
                if (
                    generation !=
                    connectionGeneration
                ) {
                    return
                }

                mainHandler.removeCallbacks(
                    progressRunnable
                )

                mediaController
                    ?.removeListener(
                        playerListener
                    )

                mediaController = null
                controllerFuture = null

                publishBestSnapshot()
            }
        }
    }

    private fun publishStateIfNoOtherSession(
        connectionState:
            AppConnectionState,
        errorMessage:
            String? = null
    ) {
        if (
            mediaController != null ||
            bluetoothController != null
        ) {
            publishBestSnapshot()
            return
        }

        publishState(
            connectionState,
            errorMessage
        )
    }

    private fun publishState(
        connectionState:
            AppConnectionState,
        errorMessage:
            String? = null
    ) {
        mainHandler.removeCallbacks(
            progressRunnable
        )

        onSnapshotChanged(
            MediaSessionSnapshot.empty(
                connectionState =
                    connectionState,
                errorMessage =
                    errorMessage
            )
        )
    }

    private fun scheduleProgressUpdates(
        isPlaying: Boolean
    ) {
        mainHandler.removeCallbacks(
            progressRunnable
        )

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
        shouldStayConnected = false

        connectionGeneration++

        mainHandler.removeCallbacks(
            progressRunnable
        )

        mainHandler.removeCallbacks(
            bluetoothRetryRunnable
        )

        mediaController
            ?.removeListener(
                playerListener
            )

        mediaController = null

        controllerFuture?.let { future ->
            MediaController.releaseFuture(future)
        }

        controllerFuture = null

        clearBluetoothController()
        disconnectBluetoothBrowserOnly()

        lastActiveSource =
            ActiveSource.HYPERNOVA

        if (notifyState) {
            publishState(
                AppConnectionState.DISCONNECTED
            )
        }
    }

    private fun clearBluetoothController() {
        bluetoothController
            ?.let { controller ->
                try {
                    controller.unregisterCallback(
                        bluetoothControllerCallback
                    )
                } catch (_: Exception) {
                    // Ignore cleanup failure.
                }
            }

        bluetoothController = null
    }

    private fun disconnectBluetoothBrowserOnly() {
        bluetoothBrowser
            ?.let { browser ->
                try {
                    browser.disconnect()
                } catch (_: Exception) {
                    // Ignore cleanup failure.
                }
            }

        bluetoothBrowser = null
    }

    private fun scheduleBluetoothReconnect() {
        if (!shouldStayConnected) {
            return
        }

        mainHandler.removeCallbacks(
            bluetoothRetryRunnable
        )

        mainHandler.postDelayed(
            bluetoothRetryRunnable,
            BLUETOOTH_RETRY_INTERVAL_MS
        )
    }

    companion object {
        private const val TAG =
            "HyperNovaMediaSession"

        private const val
            PROGRESS_UPDATE_INTERVAL_MS =
            1000L

        private const val
            BLUETOOTH_RETRY_INTERVAL_MS =
            5000L

        private const val
            BLUETOOTH_PACKAGE =
            "com.android.bluetooth"

        private const val
            BLUETOOTH_MEDIA_BROWSER_SERVICE =
            "com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService"

        private const val
            BLUETOOTH_FALLBACK_TITLE =
            "Bluetooth audio"

        private const val
            BLUETOOTH_FALLBACK_ARTIST =
            "Bluetooth"
    }
}

