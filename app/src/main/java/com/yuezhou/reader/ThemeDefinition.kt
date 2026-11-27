package com.yuezhou.reader

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Material3 以外的少量语义角色；控件交互状态继续由 Material3 管理。 */
@Immutable
internal data class YuezhouSemanticColors(
    val success: Color,
    val warning: Color,
    val coverColors: List<Color>,
    val onCover: Color,
) {
    init { require(coverColors.isNotEmpty()) { "封面占位色板不能为空" } }

    fun coverFor(key: String): Color = coverColors[(key.hashCode() and Int.MAX_VALUE) % coverColors.size]

    companion object {
        fun from(scheme: ColorScheme) = YuezhouSemanticColors(
            success = scheme.primary,
            warning = scheme.tertiary,
            coverColors = listOf(scheme.primaryContainer),
            onCover = scheme.onPrimaryContainer,
        )
    }
}

@Immutable
internal data class YuezhouThemeDefinition(
    val id: String,
    val light: ColorScheme,
    val dark: ColorScheme,
    val lightSemantic: YuezhouSemanticColors = YuezhouSemanticColors.from(light),
    val darkSemantic: YuezhouSemanticColors = YuezhouSemanticColors.from(dark),
    val name: String = id,
) {
    fun colors(isDark: Boolean) = if (isDark) dark else light
    fun semantic(isDark: Boolean) = if (isDark) darkSemantic else lightSemantic
}

/** 注册仅提供定义，不能执行业务操作。未知 ID 回到默认主题，旧配置无需迁移。 */
internal class YuezhouThemeRegistry(
    definitions: List<YuezhouThemeDefinition>,
    defaultId: String,
) {
    val definitions: List<YuezhouThemeDefinition> = definitions.toList()
    private val themes = this.definitions.associateBy { it.id }
    val default: YuezhouThemeDefinition

    init {
        require(definitions.all { it.id.isNotBlank() })
        require(themes.size == definitions.size) { "主题 ID 不得重复" }
        default = requireNotNull(themes[defaultId]) { "缺少默认主题" }
    }

    fun resolve(id: String?): YuezhouThemeDefinition = themes[id] ?: default
}

internal const val DefaultThemeId = "paper-forest"
internal val LocalYuezhouThemeRegistry = staticCompositionLocalOf { YuezhouThemes }
internal val LocalYuezhouThemeDefinition = staticCompositionLocalOf { YuezhouThemes.default }
internal val LocalYuezhouSemanticColors = staticCompositionLocalOf { YuezhouThemes.default.lightSemantic }

/** 同一 Material3 分发路径供应用使用及主题注入验证，不依赖 Activity 或阅读引擎。 */
@Composable
internal fun YuezhouThemeStyle(
    definition: YuezhouThemeDefinition,
    dark: Boolean,
    shapes: Shapes,
    typography: Typography,
    colorScheme: ColorScheme = definition.colors(dark),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalYuezhouThemeDefinition provides definition,
        LocalYuezhouSemanticColors provides definition.semantic(dark),
    ) {
        MaterialTheme(colorScheme = colorScheme, shapes = shapes, typography = typography, content = content)
    }
}

internal object YuezhouMotion {
    const val Selection = 150
    const val Page = 220
    const val Sheet = 240
}
