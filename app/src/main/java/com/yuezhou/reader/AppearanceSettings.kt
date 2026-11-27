package com.yuezhou.reader

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal enum class AppThemeMode(val label: String) {
    Light("浅色"), Dark("深色"), System("跟随系统")
}

internal data class AppAppearance(
    val mode: AppThemeMode = AppThemeMode.Light,
    val reduceMotion: Boolean = false,
    val themeId: String = DefaultThemeId,
)

internal val LocalAppAppearance = staticCompositionLocalOf { AppAppearance() }
internal val LocalAppMotionEnabled = staticCompositionLocalOf { true }

private val Context.appearanceDataStore by preferencesDataStore("app_appearance")

internal object AppearanceSettings {
    private val themeId = stringPreferencesKey("app_theme_id")
    private val theme = stringPreferencesKey("app_theme_mode")
    private val reduceMotion = booleanPreferencesKey("reduce_motion")

    fun flow(context: Context) = context.appearanceDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            AppAppearance(
                mode = AppThemeMode.entries.firstOrNull { it.name == prefs[theme] } ?: AppThemeMode.Light,
                reduceMotion = prefs[reduceMotion] ?: false,
                themeId = prefs[themeId]?.takeIf { it.isNotBlank() } ?: DefaultThemeId,
            )
        }

    suspend fun setTheme(context: Context, value: AppThemeMode) {
        context.appearanceDataStore.edit { it[theme] = value.name }
    }

    // 全局配色与明暗模式分别存储。
    suspend fun setThemeId(context: Context, value: String) {
        require(value.isNotBlank())
        context.appearanceDataStore.edit { it[themeId] = value }
    }

    suspend fun setReduceMotion(context: Context, value: Boolean) {
        context.appearanceDataStore.edit { it[reduceMotion] = value }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun AppearanceOptions() {
    val current = LocalAppAppearance.current
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("应用主题", style = MaterialTheme.typography.bodyMedium)
        val registry = LocalYuezhouThemeRegistry.current
        val selectedTheme = registry.resolve(current.themeId)
        val dark = when (current.mode) {
            AppThemeMode.Dark -> true
            AppThemeMode.Light -> false
            AppThemeMode.System -> androidx.compose.foundation.isSystemInDarkTheme()
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            registry.definitions.forEach { theme ->
                FilterChip(
                    selected = selectedTheme.id == theme.id,
                    onClick = { scope.launch { AppearanceSettings.setThemeId(context, theme.id) } },
                    label = { Text(theme.name) },
                    leadingIcon = {
                        androidx.compose.foundation.layout.Box(Modifier.size(16.dp)
                            .background(theme.colors(dark).primary, androidx.compose.foundation.shape.CircleShape))
                    },
                )
            }
        }
        Text("明暗模式", style = MaterialTheme.typography.bodyMedium)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = current.mode == mode,
                    onClick = { scope.launch { AppearanceSettings.setTheme(context, mode) } },
                    label = { Text(mode.label) },
                )
            }
        }
        com.yuezhou.reader.ui.components.SettingSwitchRow(
            "减少界面动画", current.reduceMotion, "系统关闭动效时优先使用系统设置",
        ) { value -> scope.launch { AppearanceSettings.setReduceMotion(context, value) } }
    }
}
