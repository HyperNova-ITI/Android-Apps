package com.hypernova.navigation.ui.map

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.Window
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Latest-frame-only mirror of the rendered Navigation map region.
 *
 * HNMF is deliberately separate from HNCL/6200. PixelCopy reads the actual
 * composited mapContainer: MapLibre and SoftwareRouteOverlay, without reading
 * the surrounding Navigation activity controls. At most one frame waits for
 * JPEG encoding and at most one waits for TCP send; newer frames replace older
 * ones in both places.
 */
class NavigationMapFrameStreamer(
    private val window: Window,
    private val captureView: View,
    private val host: String,
    private val port: Int,
) : AutoCloseable {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val encoder: ExecutorService = Executors.newSingleThreadExecutor()
    private val sender = LatestFrameSender(host, port)
    private val lock = Any()

    private var enabled = false
    private var closed = false
    private var captureInFlight = false
    private var encodeScheduled = false
    private var pendingBitmap: PendingBitmap? = null
    private var sequence = 0
    private var emittedFrames = 0

    fun setStreamingEnabled(value: Boolean) {
        checkMainThread()
        if (closed || enabled == value) return

        enabled = value
        if (enabled) {
            Log.i(TAG, "HNMF capture enabled for $host:$port")
            scheduleNextCapture(0L)
        } else {
            mainHandler.removeCallbacks(captureTick)
            Log.i(TAG, "HNMF capture paused")
        }
    }

    override fun close() {
        checkMainThread()
        if (closed) return
        closed = true
        enabled = false
        mainHandler.removeCallbacksAndMessages(null)

        synchronized(lock) {
            pendingBitmap?.bitmap?.recycle()
            pendingBitmap = null
        }

        encoder.shutdownNow()
        sender.close()
    }

    private val captureTick = Runnable {
        if (!enabled || closed) return@Runnable

        if (!captureInFlight && captureView.isShown) {
            requestFrame()
        }

        scheduleNextCapture(CAPTURE_INTERVAL_MS)
    }

    private fun scheduleNextCapture(delayMs: Long) {
        mainHandler.removeCallbacks(captureTick)
        if (enabled && !closed) {
            mainHandler.postDelayed(captureTick, delayMs)
        }
    }

    private fun requestFrame() {
        val width = captureView.width
        val height = captureView.height
        if (width <= 0 || height <= 0) return

        val location = IntArray(2)
        captureView.getLocationInWindow(location)
        val sourceRect = cropToTargetAspect(
            location[0],
            location[1],
            location[0] + width,
            location[1] + height,
        )

        val bitmap = Bitmap.createBitmap(
            TARGET_WIDTH,
            TARGET_HEIGHT,
            Bitmap.Config.ARGB_8888,
        )
        val captureStartedAt = SystemClock.elapsedRealtimeNanos()
        val captureTimestampMs = System.currentTimeMillis()
        captureInFlight = true

        PixelCopy.request(
            window,
            sourceRect,
            bitmap,
            { result ->
                captureInFlight = false
                if (!enabled || closed) {
                    bitmap.recycle()
                } else if (result != PixelCopy.SUCCESS) {
                    bitmap.recycle()
                    Log.w(TAG, "HNMF PixelCopy failed: $result")
                } else {
                    offerForEncoding(
                        PendingBitmap(
                            bitmap = bitmap,
                            sequence = nextSequence(),
                            captureTimestampMs = captureTimestampMs,
                            captureMillis = elapsedMillis(captureStartedAt),
                            sourceWidth = sourceRect.width(),
                            sourceHeight = sourceRect.height(),
                        ),
                    )
                }
            },
            mainHandler,
        )
    }

    private fun offerForEncoding(frame: PendingBitmap) {
        synchronized(lock) {
            pendingBitmap?.bitmap?.recycle()
            pendingBitmap = frame
            if (encodeScheduled) return
            encodeScheduled = true
        }

        encoder.execute {
            while (!Thread.currentThread().isInterrupted) {
                val next = synchronized(lock) {
                    val item = pendingBitmap
                    pendingBitmap = null
                    if (item == null) {
                        encodeScheduled = false
                    }
                    item
                } ?: return@execute

                encodeAndOffer(next)
            }
        }
    }

    private fun encodeAndOffer(frame: PendingBitmap) {
        val encodeStartedAt = SystemClock.elapsedRealtimeNanos()
        val output = ByteArrayOutputStream(INITIAL_JPEG_BUFFER_BYTES)

        try {
            if (!frame.bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                Log.w(TAG, "HNMF JPEG encode failed")
                return
            }

            val jpeg = output.toByteArray()
            if (jpeg.isEmpty() || jpeg.size > MAX_PAYLOAD_BYTES) {
                Log.w(TAG, "HNMF rejected JPEG payload size=${jpeg.size}")
                return
            }

            emittedFrames += 1
            val encodeMillis = elapsedMillis(encodeStartedAt)
            if (emittedFrames % METRICS_INTERVAL_FRAMES == 0) {
                Log.i(
                    TAG,
                    "HNMF capture=${frame.captureMillis}ms encode=${encodeMillis}ms " +
                        "size=${jpeg.size}B ${TARGET_WIDTH}x$TARGET_HEIGHT " +
                        "source=${frame.sourceWidth}x${frame.sourceHeight}",
                )
            }

            sender.offer(
                EncodedFrame(
                    sequence = frame.sequence,
                    captureTimestampMs = frame.captureTimestampMs,
                    jpeg = jpeg,
                    captureMillis = frame.captureMillis,
                    encodeMillis = encodeMillis,
                ),
            )
        } finally {
            frame.bitmap.recycle()
            output.close()
        }
    }

    private fun nextSequence(): Int {
        sequence = if (sequence == Int.MAX_VALUE) 1 else sequence + 1
        return sequence
    }

    /**
     * Match the physical QNX MapView aspect before encoding. The vertical
     * anchor preserves the lower-centre vehicle placement of driving mode.
     */
    private fun cropToTargetAspect(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Rect {
        val sourceWidth = right - left
        val sourceHeight = bottom - top
        val sourceAspect = sourceWidth.toDouble() / sourceHeight
        val targetAspect = TARGET_WIDTH.toDouble() / TARGET_HEIGHT

        return if (sourceAspect > targetAspect) {
            val croppedWidth = (sourceHeight * targetAspect).toInt()
            val cropLeft = left + (sourceWidth - croppedWidth) / 2
            Rect(cropLeft, top, cropLeft + croppedWidth, bottom)
        } else {
            val croppedHeight = (sourceWidth / targetAspect).toInt()
            val extraHeight = sourceHeight - croppedHeight
            val cropTop = top + (extraHeight * CAPTURE_VERTICAL_ANCHOR).toInt()
            Rect(left, cropTop, right, cropTop + croppedHeight)
        }
    }

    private fun elapsedMillis(startNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000L

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "NavigationMapFrameStreamer must be controlled on the main thread"
        }
    }

    private data class PendingBitmap(
        val bitmap: Bitmap,
        val sequence: Int,
        val captureTimestampMs: Long,
        val captureMillis: Long,
        val sourceWidth: Int,
        val sourceHeight: Int,
    )

    private data class EncodedFrame(
        val sequence: Int,
        val captureTimestampMs: Long,
        val jpeg: ByteArray,
        val captureMillis: Long,
        val encodeMillis: Long,
    )

    private class LatestFrameSender(
        private val host: String,
        private val port: Int,
    ) : AutoCloseable {

        private val executor: ExecutorService = Executors.newSingleThreadExecutor()
        private val lock = Any()

        private var pending: EncodedFrame? = null
        private var sending = false
        private var closed = false
        private var socket: Socket? = null
        private var output: DataOutputStream? = null
        private var sentFrames = 0

        fun offer(frame: EncodedFrame) {
            synchronized(lock) {
                if (closed) return
                pending = frame
                if (sending) return
                sending = true
            }
            executor.execute(::sendLatestFrames)
        }

        override fun close() {
            synchronized(lock) {
                closed = true
                pending = null
            }
            closeSocket()
            executor.shutdownNow()
        }

        private fun sendLatestFrames() {
            while (!Thread.currentThread().isInterrupted) {
                val frame = synchronized(lock) {
                    val item = pending
                    pending = null
                    if (item == null) sending = false
                    item
                } ?: return

                val sendStartedAt = SystemClock.elapsedRealtimeNanos()
                try {
                    val stream = ensureOutput()
                    writeHeader(stream, frame)
                    stream.write(frame.jpeg)
                    stream.flush()

                    sentFrames += 1
                    if (sentFrames % METRICS_INTERVAL_FRAMES == 0) {
                        val sendMillis =
                            (SystemClock.elapsedRealtimeNanos() - sendStartedAt) /
                                1_000_000L
                        Log.i(
                            TAG,
                            "HNMF send=${sendMillis}ms sequence=${frame.sequence} " +
                                "capture=${frame.captureMillis}ms " +
                                "encode=${frame.encodeMillis}ms",
                        )
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "HNMF send failed; reconnecting", error)
                    closeSocket()
                    Thread.sleep(RECONNECT_DELAY_MS)
                }
            }
        }

        private fun ensureOutput(): DataOutputStream {
            output?.let { return it }

            val connected = Socket()
            connected.tcpNoDelay = true
            connected.connect(
                InetSocketAddress(host, port),
                CONNECT_TIMEOUT_MS,
            )
            val stream = DataOutputStream(
                BufferedOutputStream(connected.getOutputStream()),
            )
            socket = connected
            output = stream
            Log.i(TAG, "HNMF connected to $host:$port")
            return stream
        }

        private fun writeHeader(
            stream: DataOutputStream,
            frame: EncodedFrame,
        ) {
            stream.writeInt(HNMF_MAGIC)
            stream.writeShort(HNMF_VERSION)
            stream.writeShort(HNMF_HEADER_SIZE)
            stream.writeInt(frame.sequence)
            stream.writeLong(frame.captureTimestampMs)
            stream.writeShort(TARGET_WIDTH)
            stream.writeShort(TARGET_HEIGHT)
            stream.writeByte(HNMF_ENCODING_JPEG)
            stream.writeByte(0)
            stream.writeInt(frame.jpeg.size)
            stream.writeShort(0)
        }

        private fun closeSocket() {
            try {
                output?.close()
            } catch (_: Exception) {
            }
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            output = null
            socket = null
        }
    }

    private companion object {
        private const val TAG = "HN-MapFrame"
        private const val CAPTURE_INTERVAL_MS = 100L
        // Match the QNX navigation panel between the speed and RPM gauges
        // (345x315 at runtime). This avoids stretching while retaining the
        // already-proven 10 FPS JPEG/decode budget.
        private const val TARGET_WIDTH = 414
        private const val TARGET_HEIGHT = 378
        private const val JPEG_QUALITY = 75
        private const val CAPTURE_VERTICAL_ANCHOR = 0.70
        private const val MAX_PAYLOAD_BYTES = 1_024 * 1_024
        private const val INITIAL_JPEG_BUFFER_BYTES = 64 * 1_024
        private const val METRICS_INTERVAL_FRAMES = 50
        private const val CONNECT_TIMEOUT_MS = 1_000
        private const val RECONNECT_DELAY_MS = 500L

        // HNMF / version 1 / 32-byte fixed big-endian frame header.
        private const val HNMF_MAGIC = 0x484E4D46
        private const val HNMF_VERSION = 1
        private const val HNMF_HEADER_SIZE = 32
        private const val HNMF_ENCODING_JPEG = 1
    }
}
