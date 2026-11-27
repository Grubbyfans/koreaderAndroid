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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.YuezhouColors
import com.yuezhou.reader.YuezhouDimens

/** 无封面书籍的占位底色：按 key 稳定取色，保证同一本书跨页面颜色一致。 */
private val CoverPlaceholderPalette = listOf(
    Color(0xFF5B8FF9), Color(0xFF61C0BF), Color(0xFFF6903D), Color(0xFF9D7BD8),
    Color(0xFFE8684A), Color(0xFF5DADEC), Color(0xFF6DC8EC), Color(0xFF78D3A0),
)

internal fun coverPlaceholderColor(key: String): Color =
    CoverPlaceholderPalette[(key.hashCode() and Int.MAX_VALUE) % CoverPlaceholderPalette.size]

internal val LibraryGridIcon = ImageVector.Builder("LibraryGrid", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f) {
        for (x in listOf(3.5f, 13.5f)) {
            for (y in listOf(3.5f, 13.5f)) {
                moveTo(x, y)
                horizontalLineTo(x + 7f)
                verticalLineTo(y + 7f)
                horizontalLineTo(x)
                close()
            }
        }
    }
}.build()

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
