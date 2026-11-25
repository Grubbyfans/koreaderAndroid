package com.yuezhou.reader

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 应用浅色语义色。正文配色仍由阅读器的阅读主题管理。 */
object YuezhouColors {
    val Brand = Color(0xFF526B53)
    val BrandDark = Color(0xFF344E36)
    val Background = Color(0xFFF7F3EB)
    val Surface = Color(0xFFFFFCF7)
    val BrandContainer = Color(0xFFDCE3CF)
    val BrandContainerSoft = Color(0xFFF0F1E6)
    val TextPrimary = Color(0xFF252821)
    val TextSecondary = Color(0xFF62665D)
    val Divider = Color(0xFFD8D5CA)
    val Outline = Color(0xFF858879)
    val Error = Color(0xFFA43E35)
}

internal object YuezhouDimens {
    val GapSmall = 4.dp
    val Gap = 8.dp
    val GapMedium = 12.dp
    val PageInset = 16.dp
    val CoverRadius = 8.dp
    val CardRadius = 16.dp
}

private val YuezhouColorScheme = lightColorScheme(
    primary = YuezhouColors.Brand,
    onPrimary = Color.White,
    primaryContainer = YuezhouColors.BrandContainer,
    onPrimaryContainer = YuezhouColors.BrandDark,
    secondary = YuezhouColors.Brand,
    onSecondary = Color.White,
    secondaryContainer = YuezhouColors.BrandContainer,
    onSecondaryContainer = YuezhouColors.BrandDark,
    tertiary = Color(0xFF786348),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF0E4D1),
    onTertiaryContainer = Color(0xFF493722),
    background = YuezhouColors.Background,
    onBackground = YuezhouColors.TextPrimary,
    surface = YuezhouColors.Surface,
    onSurface = YuezhouColors.TextPrimary,
    surfaceVariant = YuezhouColors.BrandContainerSoft,
    onSurfaceVariant = YuezhouColors.TextSecondary,
    surfaceTint = YuezhouColors.Brand,
    surfaceContainerLowest = YuezhouColors.Surface,
    surfaceContainerLow = Color(0xFFFCF8F0),
    surfaceContainer = Color(0xFFF4EFE5),
    surfaceContainerHigh = Color(0xFFF0EADF),
    surfaceContainerHighest = Color(0xFFECE7DB),
    inverseSurface = Color(0xFF31352C),
    inverseOnSurface = Color(0xFFF8F3E9),
    inversePrimary = Color(0xFFB0C5A4),
    outline = YuezhouColors.Outline,
    outlineVariant = YuezhouColors.Divider,
    error = YuezhouColors.Error,
    onError = Color.White,
    errorContainer = Color(0xFFF9E6E0),
    onErrorContainer = Color(0xFF772A24),
)

private val YuezhouShapes = Shapes(
    extraSmall = RoundedCornerShape(YuezhouDimens.GapSmall),
    small = RoundedCornerShape(YuezhouDimens.CoverRadius),
    medium = RoundedCornerShape(YuezhouDimens.GapMedium),
    large = RoundedCornerShape(YuezhouDimens.CardRadius),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun YuezhouTheme(content: @Composable () -> Unit) {
    val assets = LocalContext.current.assets
    val typography = remember(assets) {
        // 复用已随阅读器打包的中文衬线字体，无额外字体资源。
        val serif = FontFamily(Font("reader/fonts/noto-serif-sc.otf", assets))
        val defaults = Typography()
        Typography(
            headlineLarge = defaults.headlineLarge.copy(fontFamily = serif, fontSize = 30.sp, lineHeight = 38.sp),
            headlineMedium = defaults.headlineMedium.copy(fontFamily = serif),
            headlineSmall = defaults.headlineSmall.copy(fontFamily = serif, fontSize = 26.sp, lineHeight = 34.sp),
            titleLarge = defaults.titleLarge.copy(fontFamily = serif, fontSize = 20.sp, lineHeight = 28.sp),
            titleMedium = defaults.titleMedium.copy(fontFamily = serif, fontSize = 18.sp, lineHeight = 26.sp),
        )
    }
    MaterialTheme(colorScheme = YuezhouColorScheme, shapes = YuezhouShapes, typography = typography, content = content)
}
