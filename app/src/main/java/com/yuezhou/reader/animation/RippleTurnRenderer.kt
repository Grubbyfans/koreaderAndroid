package com.yuezhou.reader.animation

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Shader

/**
 * 水波纹翻页绘制：旧页原位绘制，目标页经 BitmapShader 与柔性渐变遮罩的 DST_IN 混合后覆盖。
 * 借用快照 Bitmap，不拥有或回收；进度与方向只影响遮罩位置，不移动页面纹理。
 */
internal class RippleTurnRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shaderMatrix = Matrix()
    private val colors = IntArray(RippleTurnSpec.STOP_COUNT)
    private val positions = FloatArray(RippleTurnSpec.STOP_COUNT)
    private var bitmap: Bitmap? = null
    private var targetShader: BitmapShader? = null
    private var direction = 1
    private var ratio = RippleTurnSpec.DEFAULT_RATIO

    init {
        for (i in 0 until RippleTurnSpec.STOP_COUNT) {
            val t = i.toFloat() / (RippleTurnSpec.STOP_COUNT - 1)
            val alpha = ((1f - RippleTurnSpec.smoothstep(t)) * 255).toInt().coerceIn(0, 255)
            colors[i] = alpha shl 24
            positions[i] = t
        }
    }

    fun prepare(page: Bitmap, direction: Int, ratio: Float = RippleTurnSpec.DEFAULT_RATIO) {
        this.direction = direction
        this.ratio = ratio
        bitmap = page
        targetShader = BitmapShader(page, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    fun draw(canvas: Canvas, current: Bitmap, bounds: Rect, progress: Float) {
        paint.alpha = 255
        paint.shader = null
        canvas.drawBitmap(current, null, bounds, paint)
        val page = bitmap ?: return
        val shader = targetShader ?: return
        if (page.isRecycled || current.isRecycled) return
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0f) return
        if (p >= 1f) {
            canvas.drawBitmap(page, null, bounds, paint)
            return
        }
        val width = bounds.width().toFloat()
        val band = RippleTurnSpec.band(width, ratio)
        val s = RippleTurnSpec.sweepOffset(p, width, band)
        if (page.width != bounds.width() || page.height != bounds.height()) {
            shaderMatrix.setScale(
                bounds.width() / page.width.toFloat(),
                bounds.height() / page.height.toFloat(),
            )
            shader.setLocalMatrix(shaderMatrix)
        }
        // 渐变坐标就是视口坐标；mask 为 dst、目标页为 src 做 SRC_IN，取目标页颜色乘遮罩 alpha。
        // 用 SRC_IN 而非 DST_IN：DST 系模式在硬件加速下依赖目标像素、部分 GPU 不渲染。
        val mask = if (direction > 0) {
            // 下一页自右向左：镜像遮罩坐标，新页从右缘进入。
            LinearGradient(width - s, 0f, width - s - band, 0f, colors, positions, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(s, 0f, s + band, 0f, colors, positions, Shader.TileMode.CLAMP)
        }
        paint.shader = ComposeShader(mask, shader, PorterDuff.Mode.SRC_IN)
        canvas.drawRect(bounds, paint)
        paint.shader = null
    }

    fun clear() {
        paint.shader = null
        bitmap = null
        targetShader = null
    }
}
