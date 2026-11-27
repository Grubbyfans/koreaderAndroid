package com.yuezhou.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.ReaderPreferences

@Composable
internal fun ReaderThemeControls(prefs: ReaderPreferences, onChange: (ReaderPreferences) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("背景色", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderPalette.themes.forEach { (value, title) ->
                FilterChip(
                    selected = prefs.themeMode == "manual" && prefs.theme == value,
                    onClick = { onChange(prefs.copy(theme = value, themeMode = "manual")) },
                    label = { Text(title) },
                    leadingIcon = { Box(Modifier.size(14.dp).background(ReaderPalette.background(value), RoundedCornerShape(4.dp))) },
                )
            }
        }
        Text("日夜主题切换", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("manual" to "手动", "day" to "日间", "night" to "夜间", "system" to "跟随系统").forEach { (mode, label) ->
                FilterChip(selected = prefs.themeMode == mode, onClick = { onChange(prefs.copy(themeMode = mode)) }, label = { Text(label) })
            }
        }
    }
}
