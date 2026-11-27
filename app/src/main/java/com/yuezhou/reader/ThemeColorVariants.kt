package com.yuezhou.reader

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/** 复用纸墨主题的中性色、错误色和层级，仅替换成套品牌配色。 */
internal fun literaryColorVariants(light: ColorScheme, dark: ColorScheme): List<YuezhouThemeDefinition> {
    fun variant(id: String, name: String, primary: Color, nightPrimary: Color,
        container: Color, nightContainer: Color, ink: Color, nightInk: Color): YuezhouThemeDefinition {
        fun palette(base: ColorScheme, night: Boolean): ColorScheme {
            val brand = if (night) nightPrimary else primary
            val fill = if (night) nightContainer else container
            val onFill = if (night) nightInk else ink
            val onBrand = if (night) Color(0xFF1C2024) else Color.White
            return base.copy(
                primary = brand, onPrimary = onBrand,
                primaryContainer = fill, onPrimaryContainer = onFill,
                secondary = brand, onSecondary = onBrand,
                secondaryContainer = fill, onSecondaryContainer = onFill,
                surfaceVariant = if (night) nightContainer else container,
                onSurfaceVariant = if (night) Color(0xFFBEC3BC) else Color(0xFF555B55),
                surfaceTint = brand,
                inversePrimary = if (night) primary else nightPrimary,
            )
        }
        return YuezhouThemeDefinition(id, palette(light, false), palette(dark, true), name = name)
    }
    return listOf(
        variant("distant-blue", "远山蓝", Color(0xFF365E7C), Color(0xFFA4C9E9),
            Color(0xFFDCE9F3), Color(0xFF283E50), Color(0xFF233F55), Color(0xFFDCEAF6)),
        variant("evening-purple", "暮山紫", Color(0xFF6B4F78), Color(0xFFD3B7DF),
            Color(0xFFEDE1F1), Color(0xFF49334F), Color(0xFF4C3158), Color(0xFFF0DFF6)),
        variant("warm-sand", "暖砂棕", Color(0xFF79543B), Color(0xFFE1BC9B),
            Color(0xFFF0E3D6), Color(0xFF4C3829), Color(0xFF553822), Color(0xFFF3E2D1)),
    )
}
