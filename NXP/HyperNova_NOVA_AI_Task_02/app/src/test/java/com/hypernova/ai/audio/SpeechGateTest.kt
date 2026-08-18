package com.hypernova.ai.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechGateTest {
    private val config = SpeechGateConfig(
        frameMs = 20,
        preRollMs = 60,
        hangoverMs = 60,
        minimumRms = 100f,
        noiseMultiplier = 3f,
        maximumRms = 1_000f,
    )

    @Test
    fun `silence is retained locally and trigger includes bounded pre-roll`() {
        val gate = SpeechGate(config)
        repeat(5) { assertTrue(gate.accept(pcm(10)).isEmpty()) }

        val events = gate.accept(pcm(800))

        assertEquals(1, events.size)
        val start = events.single() as SpeechGateEvent.Start
        assertEquals(3, start.frames.size)
        assertTrue(gate.isActive)
    }

    @Test
    fun `active speech ends after configured hangover`() {
        val gate = SpeechGate(config)
        gate.accept(pcm(800))

        assertEquals(listOf(SpeechGateEvent.Pcm::class), gate.accept(pcm(0)).map { it::class })
        assertEquals(listOf(SpeechGateEvent.Pcm::class), gate.accept(pcm(0)).map { it::class })
        val finalEvents = gate.accept(pcm(0))

        assertEquals(listOf(SpeechGateEvent.Pcm::class, SpeechGateEvent.End::class), finalEvents.map { it::class })
        assertFalse(gate.isActive)
    }

    private fun pcm(value: Int, samples: Int = 320): ByteArray = ByteArray(samples * 2).also { bytes ->
        repeat(samples) { index ->
            bytes[index * 2] = (value and 0xff).toByte()
            bytes[index * 2 + 1] = ((value shr 8) and 0xff).toByte()
        }
    }
}
