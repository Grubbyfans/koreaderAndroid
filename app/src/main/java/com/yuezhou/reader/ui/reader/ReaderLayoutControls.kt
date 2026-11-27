package com.yuezhou.reader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.ReaderFontPreference
import com.yuezhou.reader.ReaderPreferences

/** 面板滑块保留触控范围，用细轨道和圆形滑块减少视觉干扰。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderPanelSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceVariant
    Slider(value = value, onValueChange = onValueChange, modifier = modifier,
        onValueChangeFinished = onValueChangeFinished, valueRange = valueRange, steps = steps,
        thumb = { Box(Modifier.size(18.dp).background(active, CircleShape)) },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val fraction = ((state.value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
                val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
                val start = if (rtl) size.width else 0f
                val end = if (rtl) 0f else size.width
                drawLine(inactive, Offset(start, center.y), Offset(end, center.y), size.height, StrokeCap.Round)
                drawLine(active, Offset(start, center.y), Offset(start + (end - start) * fraction, center.y), size.height, StrokeCap.Round)
            }
        })
}

@Composable
internal fun ReaderValueSlider(
    label: String, displayValue: String, value: Float, valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit, onValueChangeFinished: () -> Unit, steps: Int = 0,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
        ReaderPanelSlider(value, onValueChange, Modifier.weight(1f), onValueChangeFinished, valueRange, steps)
        Text(displayValue, Modifier.width(64.dp).padding(start = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderLayoutControls(prefs: ReaderPreferences, onChange: (ReaderPreferences) -> Unit, outlinedFont: Boolean = false) {
    var fontSize by remember { mutableStateOf<Int?>(null) }
    var lineHeight by remember { mutableStateOf<Double?>(null) }
    var margin by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        LayoutSectionTitle("文字排版")
        ReaderFontPreference(prefs.fontId, prefs.fontWeight, outlinedSelection = outlinedFont, onWeightChange = { onChange(prefs.copy(fontWeight = it)) })
        ReaderValueSlider("字号", "${fontSize ?: prefs.fontSize}", (fontSize ?: prefs.fontSize).toFloat(), 12f..30f,
            { fontSize = it.toInt() }, { fontSize?.let { onChange(prefs.copy(fontSize = it)) }; fontSize = null })
        ReaderValueSlider("行距", String.format("%.1f", lineHeight ?: prefs.lineHeight), (lineHeight ?: prefs.lineHeight).toFloat(), 1.2f..2.5f,
            { lineHeight = it.toDouble() }, { lineHeight?.let { onChange(prefs.copy(lineHeight = it)) }; lineHeight = null })
        ReaderValueSlider("页边距", "${margin ?: prefs.pageMargin}", (margin ?: prefs.pageMargin).toFloat(), 0f..48f,
            { margin = it.toInt() }, { margin?.let { onChange(prefs.copy(pageMargin = it)) }; margin = null })
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        LayoutSectionTitle("翻页设置")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("方向", Modifier.width(56.dp), style = MaterialTheme.typography.bodyMedium)
            FilterChip(!prefs.vertical, { onChange(prefs.copy(vertical = false)) }, label = { Text("左右") })
            FilterChip(prefs.vertical, { onChange(prefs.copy(vertical = true)) }, label = { Text("上下") })
        }
        Text("翻页动画", Modifier.padding(top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("none" to "无动画", "slide" to "滑动", "fade" to "渐变", "ios_curl" to "iOS 仿真", "ripple" to "水波纹").forEach { (value, label) ->
                FilterChip(prefs.pageAnimation == value, { onChange(prefs.copy(pageAnimation = value)) }, label = { Text(label) })
            }
        }
        if (prefs.pageAnimation == "ripple") {
            var duration by remember { mutableStateOf<Int?>(null) }
            var ratio by remember { mutableStateOf<Int?>(null) }
            Text("水波纹参数", Modifier.padding(top = 12.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ReaderValueSlider("动画时长", "${duration ?: prefs.rippleDurationMs}ms", (duration ?: prefs.rippleDurationMs).toFloat(), 100f..600f,
                { duration = it.toInt() }, { duration?.let { onChange(prefs.copy(rippleDurationMs = it)) }; duration = null }, steps = 50)
            ReaderValueSlider("过渡范围", "${ratio ?: prefs.rippleRatio}%", (ratio ?: prefs.rippleRatio).toFloat(), 10f..80f,
                { ratio = it.toInt() }, { ratio?.let { onChange(prefs.copy(rippleRatio = it)) }; ratio = null }, steps = 70)
            TextButton(onClick = { onChange(prefs.copy(rippleDurationMs = 280, rippleRatio = 35)) },
                modifier = Modifier.align(Alignment.End)) { Text("重置水波纹参数") }
        }
    }
}

@Composable
private fun LayoutSectionTitle(title: String) {
    Text(title, Modifier.padding(top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}
