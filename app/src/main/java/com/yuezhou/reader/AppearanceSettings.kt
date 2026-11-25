package com.yuezhou.reader

import android.content.Context
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
)

internal val LocalAppAppearance = staticCompositionLocalOf { AppAppearance() }
internal val LocalAppMotionEnabled = staticCompositionLocalOf { true }

private val Context.appearanceDataStore by preferencesDataStore("app_appearance")

internal object AppearanceSettings {
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
            )
        }

    suspend fun setTheme(context: Context, value: AppThemeMode) {
        context.appearanceDataStore.edit { it[theme] = value.name }
    }

    suspend fun setReduceMotion(context: Context, value: Boolean) {
        context.appearanceDataStore.edit { it[reduceMotion] = value }
    }
}

@Composable
internal fun AppearanceOptions() {
    val current = LocalAppAppearance.current
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("应用主题", style = MaterialTheme.typography.bodyMedium)
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
        Row(
            Modifier.fillMaxWidth().toggleable(
                value = current.reduceMotion,
                role = Role.Switch,
                onValueChange = { scope.launch { AppearanceSettings.setReduceMotion(context, it) } },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("减少界面动画", style = MaterialTheme.typography.bodyMedium)
                Text("系统关闭动效时优先使用系统设置", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = current.reduceMotion,
                onCheckedChange = null,
            )
        }
    }
}
