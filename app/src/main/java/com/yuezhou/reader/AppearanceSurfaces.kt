package com.yuezhou.reader

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** 界面底色与品牌主题、明暗模式分别保存，不应用于阅读正文或阅读面板。 */
internal enum class AppSurfaceStyle(val id: String, val label: String) {
    Paper("paper", "米白"), White("white", "纯白"), Green("green", "护眼绿"),
    Blue("blue", "浅蓝"), Gray("gray", "中性灰");

    companion object {
        fun resolve(id: String?) = entries.firstOrNull { it.id == id } ?: Paper
    }
}

internal fun ColorScheme.withSurfaceStyle(style: AppSurfaceStyle, dark: Boolean): ColorScheme {
    if (style == AppSurfaceStyle.Paper) return this
    val (background, surface) = when (style) {
        AppSurfaceStyle.White -> if (dark) Color(0xFF171717) to Color(0xFF242424)
            else Color(0xFFF5F5F5) to Color.White
        AppSurfaceStyle.Green -> if (dark) Color(0xFF18221B) to Color(0xFF24322A)
            else Color(0xFFEAF2E5) to Color(0xFFF5FAF0)
        AppSurfaceStyle.Blue -> if (dark) Color(0xFF17212B) to Color(0xFF23313F)
            else Color(0xFFEAF1F8) to Color(0xFFF6FAFF)
        AppSurfaceStyle.Gray -> if (dark) Color(0xFF191B1E) to Color(0xFF292C30)
            else Color(0xFFE9EBEE) to Color(0xFFF7F8FA)
        AppSurfaceStyle.Paper -> return this
    }
    return copy(
        background = background, surface = surface,
        surfaceDim = background, surfaceBright = surface,
        surfaceContainerLowest = surface,
        surfaceContainerLow = lerp(background, surface, 0.8f),
        surfaceContainer = lerp(background, surface, 0.6f),
        surfaceContainerHigh = lerp(background, surface, 0.4f),
        surfaceContainerHighest = lerp(background, surface, 0.2f),
        surfaceVariant = lerp(background, surface, 0.4f),
        onSurfaceVariant = if (dark) Color(0xFFBEC3BC) else Color(0xFF555B55),
        outlineVariant = if (dark) lerp(surface, Color.White, 0.15f) else lerp(background, Color.Black, 0.12f),
    )
}
