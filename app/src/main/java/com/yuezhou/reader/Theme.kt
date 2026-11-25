package com.yuezhou.reader

import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.AssetManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 应用浅色语义色。正文配色仍由阅读器的阅读主题管理。 */
private object LiteraryLightColors {
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

object YuezhouColors {
    val Brand: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
    val BrandDark: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onPrimaryContainer
    val Background: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
    val Surface: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface
    val BrandContainer: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primaryContainer
    val BrandContainerSoft: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceVariant
    val TextPrimary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val Divider: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant
    val Outline: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outline
    val Error: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.error
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
    primary = LiteraryLightColors.Brand,
    onPrimary = Color.White,
    primaryContainer = LiteraryLightColors.BrandContainer,
    onPrimaryContainer = LiteraryLightColors.BrandDark,
    secondary = LiteraryLightColors.Brand,
    onSecondary = Color.White,
    secondaryContainer = LiteraryLightColors.BrandContainer,
    onSecondaryContainer = LiteraryLightColors.BrandDark,
    tertiary = Color(0xFF786348),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF0E4D1),
    onTertiaryContainer = Color(0xFF493722),
    background = LiteraryLightColors.Background,
    onBackground = LiteraryLightColors.TextPrimary,
    surface = LiteraryLightColors.Surface,
    onSurface = LiteraryLightColors.TextPrimary,
    surfaceVariant = LiteraryLightColors.BrandContainerSoft,
    onSurfaceVariant = LiteraryLightColors.TextSecondary,
    surfaceTint = LiteraryLightColors.Brand,
    surfaceContainerLowest = LiteraryLightColors.Surface,
    surfaceContainerLow = Color(0xFFFCF8F0),
    surfaceContainer = Color(0xFFF4EFE5),
    surfaceContainerHigh = Color(0xFFF0EADF),
    surfaceContainerHighest = Color(0xFFECE7DB),
    inverseSurface = Color(0xFF31352C),
    inverseOnSurface = Color(0xFFF8F3E9),
    inversePrimary = Color(0xFFB0C5A4),
    outline = LiteraryLightColors.Outline,
    outlineVariant = LiteraryLightColors.Divider,
    error = LiteraryLightColors.Error,
    onError = Color.White,
    errorContainer = Color(0xFFF9E6E0),
    onErrorContainer = Color(0xFF772A24),
)

private val YuezhouDarkScheme = darkColorScheme(
    primary = Color(0xFFB0C5A4),
    onPrimary = Color(0xFF202D1E),
    primaryContainer = Color(0xFF394B36),
    onPrimaryContainer = Color(0xFFDCEACF),
    secondary = Color(0xFFB0C5A4),
    onSecondary = Color(0xFF202D1E),
    secondaryContainer = Color(0xFF394B36),
    onSecondaryContainer = Color(0xFFDCEACF),
    tertiary = Color(0xFFDDC3A0),
    onTertiary = Color(0xFF3F2E19),
    tertiaryContainer = Color(0xFF4B3D2C),
    onTertiaryContainer = Color(0xFFF0DCC1),
    background = Color(0xFF20231F),
    onBackground = Color(0xFFE8E9E1),
    surface = Color(0xFF292D27),
    onSurface = Color(0xFFE8E9E1),
    surfaceVariant = Color(0xFF333831),
    onSurfaceVariant = Color(0xFFB6BBAF),
    surfaceTint = Color(0xFFB0C5A4),
    surfaceContainerLowest = Color(0xFF1B1E1A),
    surfaceContainerLow = Color(0xFF242823),
    surfaceContainer = Color(0xFF2C302A),
    surfaceContainerHigh = Color(0xFF353A32),
    surfaceContainerHighest = Color(0xFF3E443A),
    outline = Color(0xFF899483),
    outlineVariant = Color(0xFF53594E),
    inverseSurface = Color(0xFFE8E9E1),
    inverseOnSurface = Color(0xFF292D27),
    inversePrimary = Color(0xFF526B53),
    error = Color(0xFFFFB4A9),
    onError = Color(0xFF641C15),
    errorContainer = Color(0xFF762D25),
    onErrorContainer = Color(0xFFFFDAD2),
)

private val YuezhouShapes = Shapes(
    extraSmall = RoundedCornerShape(YuezhouDimens.GapSmall),
    small = RoundedCornerShape(YuezhouDimens.CoverRadius),
    medium = RoundedCornerShape(YuezhouDimens.GapMedium),
    large = RoundedCornerShape(YuezhouDimens.CardRadius),
    extraLarge = RoundedCornerShape(20.dp),
)

private object LiteraryHeadingFont {
    private val loadLock = Mutex()
    private var loaded = false
    private val serif = mutableStateOf<FontFamily>(FontFamily.Serif)
    val family: FontFamily get() = serif.value

    suspend fun load(assets: AssetManager) = loadLock.withLock {
        if (loaded) return@withLock
        val font = withContext(Dispatchers.IO) {
            runCatching {
                Typeface(android.graphics.Typeface.createFromAsset(assets, "reader/fonts/noto-serif-sc.otf"))
            }.getOrNull()
        }
        if (font != null) serif.value = FontFamily(font)
        loaded = true
    }
}

@Composable
fun YuezhouTheme(followsAppAppearance: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val assets = context.applicationContext.assets
    val appearance = if (followsAppAppearance) {
        val data = remember(context) { AppearanceSettings.flow(context.applicationContext) }
        val current by data.collectAsState(initial = AppAppearance())
        current
    } else {
        AppAppearance()
    }
    val dark = when (appearance.mode) {
        AppThemeMode.Dark -> true
        AppThemeMode.Light -> false
        AppThemeMode.System -> isSystemInDarkTheme()
    }
    val systemAnimations = remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                systemAnimations.value = ValueAnimator.areAnimatorsEnabled()
            }
        }
        val resolver = context.contentResolver
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    if (followsAppAppearance && context is Activity) {
        SideEffect {
            WindowCompat.getInsetsController(context.window, context.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    LaunchedEffect(assets) { LiteraryHeadingFont.load(assets) }
    val serif = LiteraryHeadingFont.family
    val typography = remember(serif) {
        val defaults = Typography()
        Typography(
            headlineLarge = defaults.headlineLarge.copy(fontFamily = serif, fontSize = 30.sp, lineHeight = 38.sp),
            headlineMedium = defaults.headlineMedium.copy(fontFamily = serif),
            headlineSmall = defaults.headlineSmall.copy(fontFamily = serif, fontSize = 26.sp, lineHeight = 34.sp),
            titleLarge = defaults.titleLarge.copy(fontFamily = serif, fontSize = 20.sp, lineHeight = 28.sp),
            titleMedium = defaults.titleMedium.copy(fontFamily = serif, fontSize = 18.sp, lineHeight = 26.sp),
        )
    }
    CompositionLocalProvider(
        LocalAppAppearance provides appearance,
        LocalAppMotionEnabled provides (systemAnimations.value && !appearance.reduceMotion),
    ) {
        MaterialTheme(
            colorScheme = if (dark) YuezhouDarkScheme else YuezhouColorScheme,
            shapes = YuezhouShapes,
            typography = typography,
            content = content,
        )
    }
}
