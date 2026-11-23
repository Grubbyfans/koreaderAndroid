package com.yuezhou.reader.core.speech

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 合成音频健康检查（技术方案 §6 / §15.1）。
 *
 * 硬失败（[Report.ok] = false）：
 *   - 空音频；
 *   - 含 NaN / Inf；
 *   - 连续 ≥ [clipMinMs] 的 |sample| ≥ [clipThreshold]（钉死爆音）；
 *   - 整句 RMS < [minRms]（空音频）。
 *
 * 频谱诊断（[Report.narrowband] 非空，但不判失败）：
 *   阶段 B 实测 int8 模型在 4.8 kHz / 9.6 kHz 存在持续窄带能量（相对邻频最高 196×/1995×），
 *   fp32 约 3.7×/79×。这里用 Goertzel 逐窗计算目标频点与邻频的功率比，
 *   当连续 ≥ [spectralMinMs] 且比值 ≥ [spectralRatioThreshold] 时记为一条预警，
 *   用于阶段 D/E 切换模型与故障上报；是否拒绝由调用方决定，避免把正常齿音误判为失败。
 *
 * 纯 Kotlin，无 Android 依赖，可在 JVM 单测。
 */
class AudioHealthCheck(
    private val clipThreshold: Float = 0.999f,
    private val clipMinMs: Float = 50f,
    private val minRms: Float = 1e-4f,
    private val spectralFrequencies: IntArray = intArrayOf(4800, 9600),
    private val spectralRatioThreshold: Float = 100f,
    private val spectralMinMs: Float = 50f,
    private val spectralFloorRms: Float = 1e-3f,
) {

    /** 一条窄带能量预警。 */
    data class Narrowband(
        val frequencyHz: Int,
        val ratio: Float,
        val durationMs: Float,
    )

    /** 检查报告。 */
    data class Report(
        val ok: Boolean,
        val reason: String,
        val nanCount: Int,
        val infCount: Int,
        val peak: Float,
        val rms: Float,
        val clippedMs: Float,
        val narrowband: List<Narrowband>,
    ) {
        /** 是否存在频谱预警（非失败）。 */
        val hasSpectralWarning: Boolean get() = narrowband.isNotEmpty()

        fun summary(): String = buildString {
            append("ok=").append(ok)
            append(" nan=").append(nanCount)
            append(" inf=").append(infCount)
            append(" peak=").append("%.3f".format(peak))
            append(" rms=").append("%.4f".format(rms))
            append(" clipMs=").append("%.0f".format(clippedMs))
            if (reason.isNotEmpty()) append(" reason=").append(reason)
            for (n in narrowband) {
                append(" narrowband@").append(n.frequencyHz)
                append("=").append("%.0f".format(n.ratio)).append("x/")
                append("%.0f".format(n.durationMs)).append("ms")
            }
        }
    }

    fun check(samples: FloatArray, sampleRate: Int): Report {
        if (samples.isEmpty()) {
            return Report(false, "empty", 0, 0, 0f, 0f, 0f, emptyList())
        }
        if (sampleRate <= 0) {
            return Report(false, "bad_sample_rate", 0, 0, 0f, 0f, 0f, emptyList())
        }

        var nan = 0
        var inf = 0
        var peak = 0f
        var sum = 0.0
        var run = 0
        var maxRun = 0
        for (s in samples) {
            if (s.isNaN()) {
                nan++
                continue
            }
            if (s.isInfinite()) {
                inf++
                continue
            }
            val a = abs(s)
            if (a > peak) peak = a
            sum += s.toDouble() * s
            if (a >= clipThreshold) {
                run++
                if (run > maxRun) maxRun = run
            } else {
                run = 0
            }
        }
        val clippedMs = maxRun * 1000f / sampleRate
        val rms = sqrt(sum / samples.size).toFloat()
        val reason = when {
            nan > 0 -> "nan"
            inf > 0 -> "inf"
            clippedMs >= clipMinMs -> "clipped"
            rms < minRms -> "silent"
            else -> ""
        }
        val narrowband = if (reason.isEmpty()) analyzeNarrowband(samples, sampleRate) else emptyList()
        return Report(
            ok = reason.isEmpty(),
            reason = reason,
            nanCount = nan,
            infCount = inf,
            peak = peak,
            rms = rms,
            clippedMs = clippedMs,
            narrowband = narrowband,
        )
    }

    private fun analyzeNarrowband(samples: FloatArray, sampleRate: Int): List<Narrowband> {
        var frame = 2048
        while (frame > samples.size && frame > 256) frame /= 2
        if (frame < 256) return emptyList()
        val hop = frame / 2
        val hopMs = hop * 1000f / sampleRate
        val window = FloatArray(frame) { i ->
            0.5f - 0.5f * cos(2.0 * PI * i / (frame - 1)).toFloat()
        }

        data class Accum(var runFrames: Int, var bestRatio: Float, var bestRun: Int)

        val acc = spectralFrequencies.associateWith { Accum(0, 0f, 0) }
        var start = 0
        while (start + frame <= samples.size) {
            var energy = 0.0
            for (i in 0 until frame) {
                val v = samples[start + i].toDouble() * window[i]
                energy += v * v
            }
            val frameRms = sqrt(energy / frame).toFloat()
            for ((freq, a) in acc) {
                if (freq * 2 >= sampleRate) continue
                val ratio = if (frameRms < spectralFloorRms) 0f else ratioAt(samples, start, freq, sampleRate, window)
                if (ratio >= spectralRatioThreshold) {
                    a.runFrames++
                    if (ratio > a.bestRatio) a.bestRatio = ratio
                    if (a.runFrames > a.bestRun) a.bestRun = a.runFrames
                } else {
                    a.runFrames = 0
                }
            }
            start += hop
        }

        val minFrames = maxOf(1, kotlin.math.ceil(spectralMinMs / hopMs).toInt())
        return acc.mapNotNull { (freq, a) ->
            if (a.bestRun >= minFrames) {
                Narrowband(freq, a.bestRatio, a.bestRun * hopMs)
            } else {
                null
            }
        }.sortedBy { it.frequencyHz }
    }

    private fun ratioAt(
        samples: FloatArray,
        offset: Int,
        freq: Int,
        sampleRate: Int,
        window: FloatArray,
    ): Float {        val target = goertzelPower(samples, offset, freq, sampleRate, window)
        var neighbor = 0f
        var count = 0
        // 邻频取 ±60 Hz 以上，避开 Hann 主瓣（2048 窗 @24 kHz 时 1 bin≈11.7 Hz）。
        for (deltaHz in NEIGHBOR_OFFSETS_HZ) {
            val f = freq + deltaHz
            if (f <= 0 || f * 2 >= sampleRate) continue
            neighbor += goertzelPower(samples, offset, f, sampleRate, window)
            count++
        }
        if (count == 0) return 0f
        val avg = neighbor / count
        return target / (avg + 1e-9f)
    }

    private fun goertzelPower(
        samples: FloatArray,
        offset: Int,
        freq: Int,
        sampleRate: Int,
        window: FloatArray,
    ): Float {
        val coeff = 2.0 * cos(2.0 * PI * freq / sampleRate)
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        for (i in window.indices) {
            s0 = samples[offset + i] * window[i] + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return power.toFloat()
    }

    private companion object {
        val NEIGHBOR_OFFSETS_HZ = intArrayOf(-300, -180, -120, -60, 60, 120, 180, 300)
    }
}
