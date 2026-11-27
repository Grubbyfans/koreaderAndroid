package com.yuezhou.reader.networktts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.yuezhou.reader.reader.networktts.NetworkTtsRuleRepository
import kotlinx.coroutines.CancellationException

/** 原版网络 TTS 每条规则携带对应服务的发音人参数，以规则名称作为音色名。 */
@Composable
internal fun NetworkTtsVoicePreference(selectedId: Long?, onSelect: (Long) -> Unit) {
    val context = LocalContext.current
    var voices by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("正在加载音色…") }
    LaunchedEffect(context) {
        try {
            voices = NetworkTtsRuleRepository(context).list().map { it.id to it.name }
            message = "暂无音色，请在设置中导入网络 TTS 规则"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = "音色列表加载失败，请重新打开面板"
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("网络音色", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            TextButton(onClick = { expanded = true }, enabled = voices.isNotEmpty()) {
                Text(voices.firstOrNull { it.first == selectedId }?.second ?: "请选择")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                voices.forEach { (id, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        onSelect(id)
                        expanded = false
                    })
                }
            }
        }
    }
    if (voices.isEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
}
