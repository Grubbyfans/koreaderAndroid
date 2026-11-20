package com.yuezhou.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.yuezhou.reader.reader.ReaderFontStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val systemFonts = listOf(
    ReaderFontStore.Font("book", "书籍默认"),
    ReaderFontStore.Font("sans", "系统无衬线"),
    ReaderFontStore.Font("serif", "系统衬线"),
    ReaderFontStore.Font("mono", "系统等宽"),
)

@Composable
internal fun ReaderFontPreference(selectedId: String) {
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

    val choices = systemFonts + installed
    val selected = choices.firstOrNull { it.id == selectedId } ?: systemFonts.first()
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
