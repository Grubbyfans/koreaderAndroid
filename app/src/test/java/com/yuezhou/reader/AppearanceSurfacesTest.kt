package com.yuezhou.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class AppearanceSurfacesTest {
    @Test fun allBackgroundsPreserveBrandAndReadableTextInBothModes() {
        for (definition in YuezhouThemes.definitions) {
            for (dark in listOf(false, true)) {
                for (style in AppSurfaceStyle.entries) {
                    val base = definition.colors(dark)
                    val colors = base.withSurfaceStyle(style, dark)
                    assertEquals(base.primary, colors.primary)
                    assertEquals(base.secondaryContainer, colors.secondaryContainer)
                    assertEquals(base.error, colors.error)
                    for (background in listOf(colors.background, colors.surface, colors.surfaceVariant,
                        colors.surfaceContainerLow, colors.surfaceContainerHigh)) {
                        for (foreground in listOf(colors.onSurface, colors.onSurfaceVariant, colors.primary, colors.error)) {
                            assertTrue("${definition.id}/${style.id}/$dark 对比度不足",
                                contrast(foreground, background) >= 4.5f)
                        }
                    }
                }
            }
        }
    }

    @Test fun oldOrUnknownStyleFallsBackAndSettingsRemainIndependent() {
        assertEquals(AppSurfaceStyle.Paper, AppSurfaceStyle.resolve(null))
        assertEquals(AppSurfaceStyle.Paper, AppSurfaceStyle.resolve("unknown"))
        val settings = AppAppearance(themeId = "distant-blue", mode = AppThemeMode.System)
        val updated = settings.copy(surfaceStyle = AppSurfaceStyle.Green)
        assertEquals(settings.themeId, updated.themeId)
        assertEquals(settings.mode, updated.mode)
        assertSame(YuezhouThemes.default.light,
            YuezhouThemes.default.light.withSurfaceStyle(AppSurfaceStyle.Paper, false))
    }

    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + 0.05f) / (minOf(a.luminance(), b.luminance()) + 0.05f)
}
