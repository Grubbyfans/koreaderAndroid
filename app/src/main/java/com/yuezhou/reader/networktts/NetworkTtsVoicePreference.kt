package com.yuezhou.reader.networktts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.reader.networktts.NetworkTtsRuleRepository
import com.yuezhou.reader.ui.settings.RuleOrganizer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 原版网络 TTS 每条规则携带对应服务的发音人参数，以规则名称作为音色名。 */
@Composable
internal fun NetworkTtsVoicePreference(
    selectedId: Long?,
    voiceScope: String = "all",
    voiceGroup: String = "",
    onSelect: (Long) -> Unit,
) {
    val context = LocalContext.current
    var voices by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("正在加载音色…") }
    var refreshKey by remember { mutableStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(context, voiceScope, voiceGroup, refreshKey, expanded) {
        try {
            voices = withContext(Dispatchers.IO) {
                val organization = RuleOrganizer(context).load()
                val checks = NetworkTtsRuleChecker(context).load()
                NetworkTtsRuleRepository(context).list().filter { rule ->
                    val check = checks[rule.id]
                    val failed = check != null && !check.success && check.matches(rule)
                    !failed && when (voiceScope) {
                        "group" -> organization.memberships[rule.id].orEmpty() == voiceGroup
                        "favorites" -> rule.id in organization.favorites
                        else -> true
                    }
                }.map { it.id to it.name }
            }
            message = "当前展示范围内没有可选音色，请在语音朗读设置中调整范围或管理规则；已检测失败的音色已隐藏。"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            voices = emptyList()
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
                    val selected = id == selectedId
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onSelect(id)
                            expanded = false
                        },
                        trailingIcon = if (selected) {
                            {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "当前音色",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else null,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent,
                            ),
                    )
                }
            }
        }
    }
    if (voices.isEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
}

/** 只控制阅读界面的列表范围，不更改当前朗读音色。 */
@Composable
internal fun NetworkTtsVoiceScopePreference(
    voiceScope: String,
    voiceGroup: String,
    onSelect: (String, String) -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf(RuleOrganizer(context).load().groups) }
    val label = when (voiceScope) {
        "favorites" -> "收藏"
        "group" -> "分组：${voiceGroup.ifEmpty { "未分组" }}"
        else -> "全部"
    }
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth()) {
                com.yuezhou.reader.ui.components.SettingsSelectionRow("阅读界面展示音色", label, onClick = {
                    groups = RuleOrganizer(context).load().groups
                    expanded = true
                })
                if (expanded) {
                    val options = listOf(("all" to "") to "全部", ("favorites" to "") to "收藏",
                        ("group" to "") to "分组：未分组") + groups.map { ("group" to it) to "分组：$it" }
                    com.yuezhou.reader.ui.components.SettingsChoiceDialog(
                        title = "阅读界面展示音色", options = options,
                        selected = voiceScope to if (voiceScope == "group") voiceGroup else "",
                        onSelect = { (mode, group) ->
                            onSelect(mode, group)
                            expanded = false
                        }, onDismiss = { expanded = false })
                }
            }
        Text("自动隐藏已检测失败的音色，未检测的音色仍会显示。", style = MaterialTheme.typography.bodySmall)
        if (voiceScope == "group" && voiceGroup.isNotEmpty() && voiceGroup !in groups) {
            Text("所选分组已不存在，请重新选择。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}
