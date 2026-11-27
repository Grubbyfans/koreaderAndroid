package com.yuezhou.reader.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.Role

/** 设置页统一的选择入口，整行可点击，长选项保持在容器内。 */
@Composable
internal fun SettingsSelectionRow(title: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "选择$title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun <T : Any> SettingsChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), state = listState) {
                items(options.size) { index ->
                    val (value, label) = options[index]
                    val checked = value == selected
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = checked, role = Role.RadioButton,
                            onClick = { onSelect(value) }).heightIn(min = 56.dp).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = checked, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
