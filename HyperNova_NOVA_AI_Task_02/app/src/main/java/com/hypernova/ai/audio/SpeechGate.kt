package com.hypernova.ai.audio

import java.util.ArrayDeque
import kotlin.math.sqrt

data class SpeechGateConfig(
    val sampleRate: Int = 16_000,
    val frameMs: Int = 20,
    val preRollMs: Int = 600,
    val hangoverMs: Int = 800,
    val maxBurstMs: Int = 12_000,
    val minimumRms: Float = 325f,
    val noiseMultiplier: Float = 3f,
    val maximumRms: Float = 1_500f,
)

sealed interface SpeechGateEvent {
    data class Start(val frames: List<ByteArray>) : SpeechGateEvent
    data class Pcm(val frame: ByteArray) : SpeechGateEvent
    data object End : SpeechGateEvent
}

/**
 * Lightweight energy VAD. Silence remains inside Android; only Start/Pcm/End output is sent.
 * This class is platform-independent so its timing behavior can be unit-tested on the laptop.
 */
class SpeechGate(private val config: SpeechGateConfig = SpeechGateConfig()) {
    private val preRoll = ArrayDeque<ByteArray>()
    private val preRollFrames = (config.preRollMs / config.frameMs).coerceAtLeast(1)
    private var noiseFloor = 0f
    private var noiseSamples = 0
    private var active = false
    private var silenceMs = 0
    private var burstMs = 0

    val isActive: Boolean get() = active
    val threshold: Float
        get() = maxOf(config.minimumRms, noiseFloor * config.noiseMultiplier)
            .coerceAtMost(config.maximumRms)

    fun accept(frame: ByteArray): List<SpeechGateEvent> {
        require(frame.size % 2 == 0) { "PCM S16LE frames must contain complete samples" }
        val rms = rms(frame)

        if (!active) {
            val triggerThreshold = threshold
            preRoll.addLast(frame.copyOf())
            while (preRoll.size > preRollFrames) preRoll.removeFirst()

            if (rms <= triggerThreshold) {
                updateNoiseFloor(rms)
                return emptyList()
            }

            active = true
            burstMs = config.frameMs
            silenceMs = 0
            val bufferedFrames = preRoll.toList()
            preRoll.clear()
            return listOf(SpeechGateEvent.Start(bufferedFrames))
        }

        burstMs += config.frameMs
        silenceMs = if (rms > threshold) 0 else silenceMs + config.frameMs
        val output = mutableListOf<SpeechGateEvent>(SpeechGateEvent.Pcm(frame.copyOf()))
        if (silenceMs >= config.hangoverMs || burstMs >= config.maxBurstMs) {
            output += SpeechGateEvent.End
            resetBurst()
        }
        return output
    }

    fun reset() {
        preRoll.clear()
        resetBurst()
    }

    private fun resetBurst() {
        active = false
        silenceMs = 0
        burstMs = 0
    }

    private fun updateNoiseFloor(rms: Float) {
        noiseSamples++
        noiseFloor = if (noiseSamples == 1) rms else (noiseFloor * 0.95f) + (rms * 0.05f)
    }

    private fun rms(frame: ByteArray): Float {
        if (frame.isEmpty()) return 0f
        var sumSquares = 0.0
        var sampleCount = 0
        var index = 0
        while (index + 1 < frame.size) {
            val sample = ((frame[index].toInt() and 0xff) or (frame[index + 1].toInt() shl 8)).toShort().toInt()
            sumSquares += sample.toDouble() * sample.toDouble()
            sampleCount++
            index += 2
        }
        return sqrt(sumSquares / sampleCount).toFloat()
    }
}
