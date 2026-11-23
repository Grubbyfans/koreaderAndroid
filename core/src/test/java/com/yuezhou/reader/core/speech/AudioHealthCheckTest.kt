package com.yuezhou.reader.core.speech

import kotlin.math.PI
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AudioHealthCheckTest {

    private val check = AudioHealthCheck()
    private val rate = 24_000

    private fun tone(freq: Int, amplitude: Float, ms: Int): FloatArray {
        val n = rate * ms / 1000
        return FloatArray(n) { (amplitude * sin(2.0 * PI * freq * it / rate)).toFloat() }
    }

    private fun noise(amplitude: Float, ms: Int, seed: Long = 42): FloatArray {
        var x = seed
        val n = rate * ms / 1000
        return FloatArray(n) {
            x = x * 6364136223846793005L + 1442695040888963407L
            val unit = ((x ushr 40) and 0xFFFF).toFloat() / 65535f * 2f - 1f
            unit * amplitude
        }
    }

    @Test
    fun `正常语音频段音频通过`() {
        val report = check.check(tone(440, 0.5f, 600), rate)
        assertTrue(report.ok, report.summary())
        assertFalse(report.hasSpectralWarning, report.summary())
    }

    @Test
    fun `空音频失败`() {
        val report = check.check(FloatArray(0), rate)
        assertFalse(report.ok)
        assertEquals("empty", report.reason)
    }

    @Test
    fun `含 NaN 失败`() {
        val samples = tone(440, 0.5f, 200)
        samples[100] = Float.NaN
        val report = check.check(samples, rate)
        assertFalse(report.ok)
        assertEquals("nan", report.reason)
        assertEquals(1, report.nanCount)
    }

    @Test
    fun `连续削波超过 50ms 失败`() {
        val report = check.check(FloatArray(rate * 100 / 1000) { 1.0f }, rate)
        assertFalse(report.ok)
        assertEquals("clipped", report.reason)
        assertTrue(report.clippedMs >= 50f)
    }

    @Test
    fun `短暂削波不失败`() {
        val samples = tone(440, 0.5f, 300)
        for (i in 100 until 140) samples[i] = 1.0f
        val report = check.check(samples, rate)
        assertTrue(report.ok, report.summary())
    }

    @Test
    fun `全静音失败`() {
        val report = check.check(FloatArray(rate) { 0f }, rate)
        assertFalse(report.ok)
        assertEquals("silent", report.reason)
    }

    @Test
    fun `int8 型 4_8k 稳态窄带能量给出预警`() {
        val samples = tone(4800, 0.3f, 600)
        val report = check.check(samples, rate)
        assertTrue(report.ok, report.summary())
        assertTrue(report.hasSpectralWarning, report.summary())
        assertEquals(4800, report.narrowband.first().frequencyHz)
        assertTrue(report.narrowband.first().durationMs >= 50f, report.summary())
    }

    @Test
    fun `fp32 型宽带噪声不误报窄带`() {
        val samples = noise(0.2f, 600)
        val report = check.check(samples, rate)
        assertTrue(report.ok, report.summary())
        assertFalse(report.hasSpectralWarning, report.summary())
    }
}
