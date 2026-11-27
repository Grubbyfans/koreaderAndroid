package com.yuezhou.reader.animation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.view.View
import io.legado.app.feature.reader.core.transition.ioscurl.layout.CurlLayout
import io.legado.app.feature.reader.core.transition.ioscurl.mesh.CurlColor
import io.legado.app.feature.reader.core.transition.ioscurl.mesh.CurlMaterial
import io.legado.app.feature.reader.core.transition.ioscurl.mesh.CurlMesh
import io.legado.app.feature.reader.core.transition.ioscurl.mesh.CurlMeshInput
import io.legado.app.feature.reader.core.transition.ioscurl.model.CurlConfig
import io.legado.app.feature.reader.core.transition.ioscurl.model.CurlValues
import io.legado.app.feature.reader.platform.ioscurl.IosCurlRenderer
import io.legado.app.feature.reader.platform.ioscurl.IosCurlCanvasRenderer

/** Borrows page images until clear(); never updates reader navigation or persistence. */
internal class PageTurnOverlay(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = Rect()
    private var current: Bitmap? = null
    private var target: Bitmap? = null
    private val mesh = CurlMesh()
    private var renderer: IosCurlCanvasRenderer? = null
    private var generation = 0L
    private var nativeFailed = false
    private var gl: IosCurlRenderer? = null
    private var output: Bitmap? = null
    private var config: CurlConfig? = null
    private var values: CurlValues? = null
    private var material = CurlMaterial()
    private val ripple = RippleTurnRenderer()
    private var rippleActive = false
    var fadeProgress = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    var rippleProgress = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    var onFirstMotionDraw: (() -> Unit)? = null
    var onRenderFailure: ((Throwable) -> Unit)? = null

    init { visibility = GONE; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun prepare(first: Bitmap, second: Bitmap, pageBackground: Int, mode: String = "fade", direction: Int = 1, rippleRatio: Float = RippleTurnSpec.DEFAULT_RATIO) {
        require(first.width == second.width && first.height == second.height)
        generation++
        current = first; target = second
        material = CurlMaterial().copy(
            back = CurlColor(Color.red(pageBackground) / 255.0, Color.green(pageBackground) / 255.0, Color.blue(pageBackground) / 255.0, .8),
            shadow = CurlColor(0.0, 0.0, 0.0, .55),
        )
        nativeFailed = false
        renderer = null
        config = null; values = null; fadeProgress = 0f; rippleProgress = 0f
        rippleActive = mode == "ripple"
        if (rippleActive) ripple.prepare(second, direction, rippleRatio) else ripple.clear()
        visibility = VISIBLE
    }

    /** Retain the destination while WebView navigation catches up after an interrupted visual. */
    fun showPage(committed: Boolean) {
        config = null; values = null
        fadeProgress = if (committed) 1f else 0f
        rippleProgress = if (committed) 1f else 0f
    }

    fun curl(configuration: CurlConfig, pose: CurlValues) {
        config = configuration; values = pose; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val first = current ?: return
        val second = target ?: return
        if (first.isRecycled || second.isRecycled) return
        if (config != null || fadeProgress > 0f || (rippleActive && rippleProgress > 0f)) onFirstMotionDraw?.invoke()
        bounds.set(0, 0, width, height)
        val c = config
        val pose = values
        if (c == null || pose == null) {
            if (rippleActive) {
                try {
                    ripple.draw(canvas, first, bounds, rippleProgress)
                } catch (error: RuntimeException) {
                    notifyFailure(error)
                } catch (error: OutOfMemoryError) {
                    notifyFailure(error)
                }
                return
            }
            // Legado FADE: the complete incoming page is above the opaque current page.
            paint.alpha = 255; paint.shader = null
            canvas.drawBitmap(first, null, bounds, paint)
            paint.alpha = (255 * fadeProgress).toInt()
            canvas.drawBitmap(second, null, bounds, paint)
            paint.alpha = 255
            return
        }
        paint.alpha = 255; paint.shader = null
        canvas.drawBitmap(first, null, bounds, paint)
        try {
            val layout = CurlLayout.build(c)
            val texture = if (layout.passes.single().source.nativeValue < 2) 0 else 1
            val frame = mesh.build(CurlMeshInput(pose.renderValues(), c.width, c.height,
                (first.width / c.width).coerceIn(.1, 4.0), material = material), coalesceTriangleStrips = true)
            val underlays = layout.underlays.map { (if (it.source.nativeValue < 2) 0 else 1) to it.frame }
            if (!nativeFailed && Build.VERSION.SDK_INT >= 34 && canvas.isHardwareAccelerated) {
                try {
                    val native = renderer ?: IosCurlCanvasRenderer(first, second).also { renderer = it }
                    native.draw(canvas, frame, c.width, c.height, texture, 1 - texture, underlays, opaquePageBackground = true)
                    return
                } catch (_: RuntimeException) { renderer = null; nativeFailed = true }
            }
            val fallback = gl ?: IosCurlRenderer().also {
                gl = it
                it.upload(first, second)
                val bitmap = Bitmap.createBitmap(first.width, first.height, Bitmap.Config.ARGB_8888)
                output = bitmap
                it.bindOutput(bitmap)
            }
            fallback.render(frame, c.width.toFloat(), c.height.toFloat(), texture, 1 - texture, underlays)
            val saved = canvas.save()
            try {
                canvas.translate(0f, height.toFloat()); canvas.scale(1f, -1f)
                canvas.drawBitmap(requireNotNull(output), null, bounds, paint)
            } finally { canvas.restoreToCount(saved) }
        } catch (error: RuntimeException) {
            notifyFailure(error)
        } catch (error: OutOfMemoryError) {
            notifyFailure(error)
        }
    }

    /** Avoid invoking navigation or removing views during a draw traversal. */
    private fun notifyFailure(error: Throwable) {
        val failedGeneration = generation
        post { if (generation == failedGeneration) onRenderFailure?.invoke(error) }
    }

    fun clear() {
        onFirstMotionDraw = null
        generation++
        visibility = GONE
        renderer = null; config = null; values = null
        ripple.clear(); rippleActive = false; rippleProgress = 0f
        gl?.close(); gl = null
        // Recorded Canvas commands may still reference the last output bitmap.
        output = null
        current = null; target = null
    }
}
