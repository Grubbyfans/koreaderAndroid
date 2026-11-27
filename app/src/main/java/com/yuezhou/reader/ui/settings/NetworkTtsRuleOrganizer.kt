package com.yuezhou.reader.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.HttpTTS
import org.json.JSONArray
import org.json.JSONObject

internal data class RuleOrganization(
    val groups: List<String> = emptyList(),
    val memberships: Map<Long, String> = emptyMap(),
    val favorites: Set<Long> = emptySet(),
    val sort: String = "name",
)

/** Local management metadata, deliberately excluded from rule JSON and credentials. */
internal class RuleOrganizer(context: Context) {
    private val preferences = context.getSharedPreferences("network_tts_organization", Context.MODE_PRIVATE)

    fun load(): RuleOrganization {
        val json = JSONObject(preferences.getString("state", null) ?: "{}")
        val groups = json.optJSONArray("groups") ?: JSONArray()
        val memberships = json.optJSONObject("memberships") ?: JSONObject()
        val favorites = json.optJSONArray("favorites") ?: JSONArray()
        return RuleOrganization(
            groups = (0 until groups.length()).map { groups.getString(it) },
            memberships = memberships.keys().asSequence().mapNotNull { key ->
                key.toLongOrNull()?.let { it to memberships.getString(key) }
            }.toMap(),
            favorites = (0 until favorites.length()).map { favorites.getLong(it) }.toSet(),
            sort = json.optString("sort", "name"),
        )
    }

    fun save(value: RuleOrganization) {
        val memberships = JSONObject()
        value.memberships.forEach { (id, group) -> memberships.put(id.toString(), group) }
        val json = JSONObject().put("groups", JSONArray(value.groups))
            .put("memberships", memberships).put("favorites", JSONArray(value.favorites.toList()))
            .put("sort", value.sort)
        preferences.edit().putString("state", json.toString()).apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuleGroupSheet(
    organization: RuleOrganization,
    rules: List<HttpTTS>,
    selectedGroup: String?,
    moving: Boolean,
    onChange: (RuleOrganization) -> Unit,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    importing: Boolean = false,
) {
    var query by remember { mutableStateOf("") }
    var managing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (managing) "管理分组" else if (importing) "选择导入分组" else if (moving) "移动到分组" else "选择分组", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { managing = !managing }) { Text(if (managing) "完成" else "管理") }
            }
            OutlinedTextField(query, { query = it }, label = { Text("搜索分组") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { editing = null; name = ""; creating = true }) { Text("新建分组") }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (!managing) {
                    if (!moving) item {
                        ListItem(headlineContent = { Text("全部分组") },
                            leadingContent = { RadioButton(selected = selectedGroup == null, onClick = null) },
                            trailingContent = { Text("${rules.size}") },
                            modifier = Modifier.selectable(selected = selectedGroup == null, role = Role.RadioButton, onClick = { onSelect(null) }))
                    }
                    item {
                        ListItem(headlineContent = { Text("未分组") },
                            leadingContent = { RadioButton(selected = selectedGroup == "", onClick = null) },
                            trailingContent = { Text("${rules.count { organization.memberships[it.id] == null }}") },
                            modifier = Modifier.selectable(selected = selectedGroup == "", role = Role.RadioButton, onClick = { onSelect("") }))
                    }
                }
                items(organization.groups.filter { it.contains(query.trim(), ignoreCase = true) }, key = { it }) { group ->
                    Column {
                        ListItem(
                            headlineContent = { Text(group, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = if (managing) null else {
                                { RadioButton(selected = selectedGroup == group, onClick = null) }
                            },
                            trailingContent = { Text("${rules.count { organization.memberships[it.id] == group }}") },
                            modifier = if (managing) Modifier else Modifier.selectable(
                                selected = selectedGroup == group, role = Role.RadioButton, onClick = { onSelect(group) }),
                        )
                        if (managing) Row {
                            TextButton(onClick = { editing = group; name = group }) { Text("重命名") }
                            val index = organization.groups.indexOf(group)
                            TextButton(enabled = query.isBlank() && index > 0, onClick = {
                                val groups = organization.groups.toMutableList()
                                java.util.Collections.swap(groups, index, index - 1)
                                onChange(organization.copy(groups = groups))
                            }) { Text("上移") }
                            TextButton(enabled = query.isBlank() && index < organization.groups.lastIndex, onClick = {
                                val groups = organization.groups.toMutableList()
                                java.util.Collections.swap(groups, index, index + 1)
                                onChange(organization.copy(groups = groups))
                            }) { Text("下移") }
                            TextButton(onClick = { deleting = group }) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
    if (creating || editing != null) {
        val trimmed = name.trim()
        val valid = trimmed.isNotEmpty() && trimmed !in listOf("未分组", "全部分组") &&
            organization.groups.none { it != editing && it.equals(trimmed, ignoreCase = true) }
        AlertDialog(onDismissRequest = { creating = false; editing = null },
            title = { Text(if (creating) "新建分组" else "重命名分组") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("分组名称") }, singleLine = true,
                isError = name.isNotBlank() && !valid, supportingText = { Text("名称不能重复，也不能使用保留名称") }) },
            confirmButton = { TextButton(enabled = valid, onClick = {
                val old = editing
                val selectCreated = creating && !managing
                onChange(organization.copy(
                    groups = if (old == null) organization.groups + trimmed else organization.groups.map { if (it == old) trimmed else it },
                    memberships = organization.memberships.mapValues { if (it.value == old) trimmed else it.value },
                ))
                creating = false; editing = null
                query = ""
                if (selectCreated) onSelect(trimmed)
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { creating = false; editing = null }) { Text("取消") } })
    }
    deleting?.let { group ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除分组") },
            text = { Text("删除「$group」后，其中的规则将移到未分组，规则本身会保留。") },
            confirmButton = { TextButton(onClick = {
                onChange(organization.copy(groups = organization.groups - group,
                    memberships = organization.memberships.filterValues { it != group }))
                deleting = null
            }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}
