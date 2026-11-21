package com.yuezhou.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 静读全局主题：配色向微信读书靠齐。
 *
 * 微信读书视觉特征：以白色/极浅灰为底，天蓝色（#37A7FF 系）作为品牌点缀，
 * 冷色系、大面积留白、低饱和、去重边框，营造简洁轻盈的阅读氛围。
 */
object YuezhouColors {
    /** 品牌主色：微信读书标志性的天蓝色。 */
    val Brand = Color(0xFF37A7FF)

    /** 主色深色变体（文字/按压/强调）。 */
    val BrandDark = Color(0xFF1E8AE8)

    /** 背景：微信读书的极浅灰底色。 */
    val Background = Color(0xFFF7F8FA)

    /** 卡片/表面：纯白。 */
    val Surface = Color(0xFFFFFFFF)

    /** 浅蓝容器（选中态/强调背景）。 */
    val BrandContainer = Color(0xFFE8F4FF)

    /** 更浅的蓝（次级选中背景，几乎无存在感）。 */
    val BrandContainerSoft = Color(0xFFF2F8FF)

    /** 主文字：近黑。 */
    val TextPrimary = Color(0xFF1A1A1A)

    /** 次要文字：中灰。 */
    val TextSecondary = Color(0xFF8A8A8A)

    /** 分隔线/描边：浅灰。 */
    val Divider = Color(0xFFEBEBEB)
}

private val YuezhouColorScheme = lightColorScheme(
    primary = YuezhouColors.Brand,
    onPrimary = Color.White,
    primaryContainer = YuezhouColors.BrandContainer,
    onPrimaryContainer = YuezhouColors.BrandDark,
    secondary = YuezhouColors.Brand,
    background = YuezhouColors.Background,
    onBackground = YuezhouColors.TextPrimary,
    surface = YuezhouColors.Surface,
    onSurface = YuezhouColors.TextPrimary,
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = YuezhouColors.TextSecondary,
    outline = YuezhouColors.Divider,
    outlineVariant = YuezhouColors.Divider,
    error = Color(0xFFFA5151),
    onError = Color.White,
    errorContainer = Color(0xFFFFE7E7),
    onErrorContainer = Color(0xFFC43D3D),
)

/** 阅舟统一主题。所有界面共用，保证书架/阅读页/面板/抽屉配色一致。 */
@Composable
fun YuezhouTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = YuezhouColorScheme,
        content = content,
    )
}
