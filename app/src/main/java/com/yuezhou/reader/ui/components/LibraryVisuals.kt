package com.yuezhou.reader.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.YuezhouColors
import com.yuezhou.reader.YuezhouDimens

@Composable
internal fun LibraryPageHeading(title: String, supportingText: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(YuezhouDimens.GapSmall)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (supportingText != null) {
            Text(
                supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = YuezhouColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun BookReadingProgress(progress: Double, modifier: Modifier = Modifier) {
    val fraction = progress.coerceIn(0.0, 1.0)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(YuezhouDimens.Gap),
    ) {
        LinearProgressIndicator(
            progress = { fraction.toFloat() },
            modifier = Modifier.weight(1f).height(4.dp),
            trackColor = YuezhouColors.Divider,
        )
        Text(
            if (fraction == 1.0) "已读" else "${(fraction * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = if (fraction == 1.0) YuezhouColors.BrandDark else YuezhouColors.TextSecondary,
        )
    }
}
