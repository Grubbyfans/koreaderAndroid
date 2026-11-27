package com.yuezhou.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.reader.ReaderFontStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val defaultFonts = listOf(
    ReaderFontStore.Font("book", "书籍默认"),
    ReaderFontStore.Font("noto-serif-sc", "思源宋体风格 · Noto Serif SC"),
    ReaderFontStore.Font("noto-sans-sc", "思源黑体风格 · Noto Sans SC"),
    ReaderFontStore.Font("wenkai-lite", "霞鹜文楷（轻便版）"),
    ReaderFontStore.Font("sans", "系统无衬线"),
    ReaderFontStore.Font("serif", "系统衬线"),
    ReaderFontStore.Font("mono", "系统等宽"),
)

// 字重显示名：0 表示书籍默认，其余为 CSS font-weight 数值（100~900）。
private fun weightLabel(weight: Int): String = when (weight) {
    0 -> "书籍默认"
    400 -> "常规"
    700 -> "加粗"
    else -> weight.toString()
}

@Composable
internal fun ReaderFontPreference(selectedId: String, selectedWeight: Int, outlinedSelection: Boolean = false, onWeightChange: (Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { ReaderFontStore(context) }
    var installed by remember(store) { mutableStateOf(store.installed()) }
    var selecting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val importFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            error = null
            scope.launch {
                try {
                    val font = store.importFont(uri)
                    installed = store.installed()
                    ReaderSettings.selectFont(context, font.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: "字体导入失败"
                } finally {
                    importing = false
                }
            }
        }
    }

    val choices = defaultFonts + installed
    val selected = choices.firstOrNull { it.id == selectedId } ?: defaultFonts.first()
    if (outlinedSelection) {
        com.yuezhou.reader.ui.components.SettingsSelectionRow("字体",
            if (importing) "导入中…" else selected.label, enabled = !importing, onClick = { selecting = true })
    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("字体", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(enabled = !importing, onClick = { selecting = true }) {
            Text(
                if (importing) "导入中…" else selected.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    // 字重：连续滑块（100~900 步进 100），0 表示书籍默认。
    // 拖动中用本地预览值，松手才回调 onWeightChange，避免每次拖动都重排全文导致滑块卡顿拖不动。
    var weightPreview by remember { mutableStateOf<Int?>(null) }
    com.yuezhou.reader.ui.reader.ReaderValueSlider(
        label = "字重",
        displayValue = weightLabel(weightPreview ?: selectedWeight),
        value = (weightPreview ?: if (selectedWeight == 0) 400 else selectedWeight).toFloat(),
        valueRange = 100f..900f,
        onValueChange = { weightPreview = (it.toInt() / 100 * 100).coerceAtLeast(100) },
        onValueChangeFinished = {
            weightPreview?.let(onWeightChange)
            weightPreview = null
        },
        steps = 7,
    )
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

    if (selecting) {
        com.yuezhou.reader.ui.components.AppBottomSheet("阅读字体", onDismiss = { selecting = false }) {
            TextButton(onClick = {
                selecting = false
                importFont.launch(arrayOf("*/*"))
            }) { Text("导入 TTF / OTF") }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(choices.size) { index ->
                    val font = choices[index]
                    Row(Modifier.fillMaxWidth().selectable(selected = font.id == selected.id,
                        role = androidx.compose.ui.semantics.Role.RadioButton, onClick = {
                            selecting = false
                            error = null
                            scope.launch { ReaderSettings.selectFont(context, font.id) }
                        }).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = font.id == selected.id, onClick = null)
                        Text(font.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
