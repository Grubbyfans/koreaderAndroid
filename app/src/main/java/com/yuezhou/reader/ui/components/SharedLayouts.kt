package com.yuezhou.reader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.YuezhouColors
import com.yuezhou.reader.YuezhouDimens

/** 只负责视觉分组，不持有设置或业务状态。 */
@Composable
internal fun SectionCard(
    title: String? = null,
    highlighted: Boolean = false,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (highlighted) YuezhouColors.SelectedSurface else YuezhouColors.Surface,
    ) {
        Column(Modifier.padding(YuezhouDimens.PageInset)) {
            if (title != null) {
                Row(
                    Modifier.fillMaxWidth().then(
                        if (onToggle != null) Modifier.clickable(onClick = onToggle).heightIn(min = YuezhouDimens.TouchTarget)
                        else Modifier,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (onToggle != null) Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelMedium)
                }
                if (expanded) Spacer(Modifier.height(YuezhouDimens.GapMedium))
            }
            if (expanded) Column(verticalArrangement = Arrangement.spacedBy(YuezhouDimens.Gap), content = content)
        }
    }
}

@Composable
internal fun SettingNavigationRow(title: String, description: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        trailingContent = { Text("›", color = YuezhouColors.TextSecondary) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = YuezhouColors.Surface),
    )
}

@Composable
internal fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    description: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = YuezhouDimens.TouchTarget)
        .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(YuezhouDimens.GapMedium)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = YuezhouColors.TextSecondary)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun PageBackHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppBottomSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = YuezhouDimens.PageInset),
            verticalArrangement = Arrangement.spacedBy(YuezhouDimens.GapMedium)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            content()
            Spacer(Modifier.height(YuezhouDimens.PageInset))
        }
    }
}

/** 简洁摘要与可展开的完整详情，原始错误不会被截断丢弃。 */
@Composable
internal fun StatusDetail(summary: String, detail: String?, isError: Boolean, expanded: Boolean, isSuccess: Boolean = false, onToggle: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(YuezhouDimens.GapSmall)) {
        Text(summary, style = MaterialTheme.typography.bodySmall,
            color = if (isError) YuezhouColors.Error else if (isSuccess) YuezhouColors.Success else YuezhouColors.TextSecondary)
        if (!detail.isNullOrBlank()) {
            TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(if (expanded) "收起详情" else "查看详情")
            }
            if (expanded) Text(detail, style = MaterialTheme.typography.bodySmall, color = YuezhouColors.TextSecondary)
        }
    }
}
