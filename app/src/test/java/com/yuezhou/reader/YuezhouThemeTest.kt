package com.yuezhou.reader

import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.yuezhou.reader.ui.reader.ReaderPalette
import org.junit.Assert.*
import org.junit.Test

class YuezhouThemeTest {
    // 临时主题仅位于 test 源集，APK 无主题定义或入口。
    private val temporary = YuezhouThemeDefinition(
        id = "test-ink-blue",
        light = lightColorScheme(primary = Color(0xFF24527A), primaryContainer = Color(0xFFD4E4F5)),
        dark = darkColorScheme(primary = Color(0xFFA7C7EA), primaryContainer = Color(0xFF243B53)),
    )

    @Test fun registrationAndFallbackPreserveModeIndependence() {
        val registry = YuezhouThemeRegistry(listOf(YuezhouThemes.default, temporary), DefaultThemeId)
        assertSame(temporary, registry.resolve(temporary.id))
        assertSame(YuezhouThemes.default, registry.resolve("removed-theme"))
        assertSame(YuezhouThemes.default, registry.resolve(null))
        val appearance = AppAppearance(themeId = temporary.id, mode = AppThemeMode.System)
        assertEquals(temporary.id, appearance.copy(mode = AppThemeMode.Dark).themeId)
        assertEquals(AppThemeMode.System, appearance.copy(themeId = DefaultThemeId).mode)
    }

    @Test fun readerChromeUsesRegisteredColorsWithoutChangingBodyTheme() {
        val body = ReaderPalette.background("sepia")
        val panel = ReaderPalette.panelTheme(temporary.light, "sepia")
        assertEquals(temporary.light.primary, panel.primary)
        assertEquals(temporary.light.onSurface, panel.onSurface)
        assertEquals(body, panel.surface)
        assertEquals(body, ReaderPalette.background("sepia"))
        val night = ReaderPalette.panelTheme(temporary.dark, "dark")
        assertEquals(temporary.dark.primary, night.primary)
        assertEquals(ReaderPalette.background("dark"), night.surface)
    }

    @Test fun registeredThemeTextPairsMeetContrastTarget() {
        for (definition in YuezhouThemes.definitions) {
            for (dark in listOf(false, true)) {
                val colors = definition.colors(dark)
                listOf(
                    colors.onSurface to colors.surface,
                    colors.onSurfaceVariant to colors.surface,
                    colors.onSurfaceVariant to colors.surfaceVariant,
                    colors.primary to colors.surface,
                    colors.onPrimary to colors.primary,
                    colors.onSecondaryContainer to colors.secondaryContainer,
                    colors.error to colors.surface,
                ).forEach { (foreground, background) ->
                    assertTrue("${definition.id} 文字对比度不足：$foreground / $background", contrast(foreground, background) >= 4.5f)
                }
                definition.semantic(dark).let { semantic ->
                    semantic.coverColors.forEach { assertTrue(contrast(semantic.onCover, it) >= 4.5f) }
                    assertEquals(semantic.coverFor("book"), semantic.coverFor("book"))
                }
            }
        }
    }

    @Test fun duplicateIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            YuezhouThemeRegistry(listOf(temporary, temporary), temporary.id)
        }
    }

    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + 0.05f) / (minOf(a.luminance(), b.luminance()) + 0.05f)
}
