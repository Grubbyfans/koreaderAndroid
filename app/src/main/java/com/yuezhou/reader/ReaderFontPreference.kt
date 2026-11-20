package com.yuezhou.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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

private val fontWeights = mapOf(
    0 to "书籍默认",
    400 to "常规",
    700 to "加粗",
)

@Composable
internal fun ReaderFontPreference(selectedId: String, selectedWeight: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { ReaderFontStore(context) }
    var installed by remember(store) { mutableStateOf(store.installed()) }
    var selecting by remember { mutableStateOf(false) }
    var showWeights by remember { mutableStateOf(false) }
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("字体", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(enabled = !importing, onClick = { selecting = true }) {
            Text(
                if (importing) "导入中…" else selected.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("字重", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            TextButton(onClick = { showWeights = true }) {
                Text(fontWeights[selectedWeight] ?: fontWeights.getValue(0))
            }
            DropdownMenu(expanded = showWeights, onDismissRequest = { showWeights = false }) {
                for ((weight, label) in fontWeights) {
                    DropdownMenuItem(
                        text = { Text(if (weight == selectedWeight) "✓ $label" else label) },
                        onClick = {
                            showWeights = false
                            scope.launch { ReaderSettings.selectFontWeight(context, weight) }
                        },
                    )
                }
            }
        }
    }
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

    if (selecting) {
        AlertDialog(
            onDismissRequest = { selecting = false },
            title = { Text("阅读字体") },
            text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(choices.size) { index ->
                        val font = choices[index]
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                selecting = false
                                error = null
                                scope.launch { ReaderSettings.selectFont(context, font.id) }
                            },
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(if (font.id == selected.id) "✓ ${font.label}" else font.label)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    selecting = false
                    importFont.launch(arrayOf("*/*"))
                }) { Text("导入 TTF / OTF") }
            },
            dismissButton = { TextButton(onClick = { selecting = false }) { Text("关闭") } },
        )
    }
}
