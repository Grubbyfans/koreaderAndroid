package com.yuezhou.reader.animation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RippleTurnSpecTest {
    private val width = 1080f
    private val band = RippleTurnSpec.band(width)

    @Test
    fun endpointsShowOnlyOldOrOnlyNewPage() {
        for (x in listOf(-500f, 0f, 540f, 1080f, 2000f)) {
            assertEquals(0f, RippleTurnSpec.maskAlpha(0f, x, width, band), 0f)
            assertEquals(1f, RippleTurnSpec.maskAlpha(1f, x, width, band), 0f)
        }
    }

    @Test
    fun maskStaysStableOutsideTheMovingBand() {
        val progress = 0.5f
        assertEquals(1f, RippleTurnSpec.maskAlpha(progress, 0f, width, band), 0f)
        assertEquals(0f, RippleTurnSpec.maskAlpha(progress, width, width, band), 0f)
    }

    @Test
    fun replacementIsMonotonicInProgress() {
        val x = width / 2
        var previous = 0f
        var progress = 0f
        while (progress <= 1f) {
            val alpha = RippleTurnSpec.maskAlpha(progress, x, width, band)
            assertTrue("alpha must not decrease at progress=$progress", alpha >= previous - 1e-6f)
            previous = alpha
            progress += 0.02f
        }
    }

    @Test
    fun nextTurnMirrorsTheSweepLeftwards() {
        // 下一页：遮罩坐标换成 W - screenX，新页从右缘进入。
        val progress = 0.5f
        assertEquals(1f, RippleTurnSpec.maskAlpha(progress, width - width, width, band), 1e-6f)
        assertEquals(0f, RippleTurnSpec.maskAlpha(progress, width - 0f, width, band), 1e-6f)
        // 上一页沿用原始坐标，新页从左缘进入。
        assertEquals(1f, RippleTurnSpec.maskAlpha(progress, 0f, width, band), 1e-6f)
        assertEquals(0f, RippleTurnSpec.maskAlpha(progress, width, width, band), 1e-6f)
    }

    @Test
    fun gradientStopsDescendFromOpaqueToTransparent() {
        var previous = 256
        for (i in 0 until RippleTurnSpec.STOP_COUNT) {
            val t = i.toFloat() / (RippleTurnSpec.STOP_COUNT - 1)
            val alpha = ((1f - RippleTurnSpec.smoothstep(t)) * 255).toInt().coerceIn(0, 255)
            assertTrue("stop alpha must not increase", alpha <= previous)
            previous = alpha
        }
        assertEquals(255, ((1f - RippleTurnSpec.smoothstep(0f)) * 255).toInt())
        assertEquals(0, ((1f - RippleTurnSpec.smoothstep(1f)) * 255).toInt())
        assertEquals(0.5f, RippleTurnSpec.smoothstep(0.5f), 1e-6f)
    }

    @Test
    fun bandFollowsConfiguredRatio() {
        assertEquals(width * RippleTurnSpec.DEFAULT_RATIO, band, 1e-3f)
        assertEquals(width * 0.5f, RippleTurnSpec.band(width, 0.5f), 1e-3f)
    }

    @Test
    fun defaultsMatchSpecifiedPreset() {
        assertEquals(280, RippleTurnSpec.DEFAULT_DURATION_MS)
        assertEquals(35, RippleTurnSpec.DEFAULT_RATIO_PERCENT)
        assertEquals(0.35f, RippleTurnSpec.DEFAULT_RATIO, 1e-6f)
    }
}
