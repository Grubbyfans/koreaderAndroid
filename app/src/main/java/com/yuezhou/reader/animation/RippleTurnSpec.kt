package com.yuezhou.reader.animation

/**
 * 水波纹翻页的遮罩曲线与默认参数，纯 Kotlin 计算以便 JVM 单测直接验证生产实现。
 * 时长与过渡带宽度由用户配置传入，不在此处持久化。
 */
internal object RippleTurnSpec {
    /** 用多色标 LinearGradient 分段近似 Smoothstep 的等距色标数。 */
    const val STOP_COUNT = 33

    /** 默认动画时长（ms）。 */
    const val DEFAULT_DURATION_MS = 280

    /** 默认过渡区域宽度（页宽百分比）。 */
    const val DEFAULT_RATIO_PERCENT = 35

    /** 默认过渡区域宽度（页宽比例，0..1）。 */
    const val DEFAULT_RATIO = DEFAULT_RATIO_PERCENT / 100f

    /** 柔性过渡带宽度（像素）；ratio 为过渡带占页宽的比例。 */
    fun band(width: Float, ratio: Float = DEFAULT_RATIO): Float =
        (width * ratio).coerceAtLeast(1f)

    /** 过渡带左（旧页侧）边界；0 时在页面之外，1 时覆盖整页。 */
    fun sweepOffset(progress: Float, width: Float, band: Float): Float =
        progress.coerceIn(0f, 1f) * (width + band) - band

    /**
     * 页面坐标 x 处的目标页混合权重：0 表示旧页，1 表示目标页。
     * 下一页翻页时调用方应将 x 换成镜像坐标 `W - screenX`。
     */
    fun maskAlpha(progress: Float, x: Float, width: Float, band: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0f) return 0f
        if (p >= 1f) return 1f
        val s = sweepOffset(p, width, band)
        val u = ((s + band - x) / band).coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    /** 三次 Smoothstep；色标 alpha 取其补值，使 0 为完全目标页。 */
    fun smoothstep(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }
}
