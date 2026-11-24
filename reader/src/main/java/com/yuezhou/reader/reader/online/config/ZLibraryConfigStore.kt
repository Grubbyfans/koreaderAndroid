package com.yuezhou.reader.reader.online.config

import android.content.Context
import com.yuezhou.reader.core.online.config.DiscoveryState
import com.yuezhou.reader.core.online.config.ServerCandidate
import com.yuezhou.reader.core.online.config.ServerImportPreview
import com.yuezhou.reader.core.online.config.ServerUrlPolicy
import com.yuezhou.reader.core.online.config.ZLibraryConfig
import com.yuezhou.reader.core.online.config.ZLibraryConfigMigration
import com.yuezhou.reader.core.online.config.ZLibraryConfigValidator
import com.yuezhou.reader.core.online.config.ZLibraryConnectionTester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Z-Library 配置存储（原方案 §6 + 发现增量 §3/§4）：JSON 文件 + 备份回滚 + 恢复默认。
 *
 * - 用户配置 `files/providers/zlibrary_config.json`，不含任何凭证。
 * - 运行状态 `files/providers/zlibrary_discovery_state.json` 独立保存，不随配置导入导出或回滚。
 * - 导入先预览分类（新增/重复/无效），由用户确认合并或替换；无效配置不覆盖当前配置。
 * - 保存前备份上一份到 `.bak`，临时文件 + 原子重命名。
 */
class ZLibraryConfigStore(private val context: Context) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file: File get() = File(context.filesDir, "providers/zlibrary_config.json")
    private val backup: File get() = File(context.filesDir, "providers/zlibrary_config.json.bak")
    private val stateFile: File get() = File(context.filesDir, "providers/zlibrary_discovery_state.json")

    fun load(): ZLibraryConfig {
        val text = try {
            file.takeIf { it.exists() }?.readText() ?: return ZLibraryConfig.DEFAULT
        } catch (e: Exception) {
            return ZLibraryConfig.DEFAULT
        }
        val parsed = runCatching { json.decodeFromString<ZLibraryConfig>(text) }.getOrNull()
            ?: return ZLibraryConfig.DEFAULT
        // 迁移后再校验：旧版 v1 配置升级为候选列表后应能通过校验。
        val migrated = ZLibraryConfigMigration.normalize(parsed)
        return migrated.takeIf { ZLibraryConfigValidator.validate(it).isEmpty() }
            ?: ZLibraryConfig.DEFAULT
    }

    fun hasCustomConfig(): Boolean = file.exists()

    fun hasBackup(): Boolean = backup.exists()

    fun exportJson(config: ZLibraryConfig = load()): String = json.encodeToString(config)

    /** 解析导入文本为预览，不写盘。支持完整配置 JSON 与每行一个地址的纯文本。 */
    fun previewImport(text: String): Result<ServerImportPreview> = runCatching {
        require(text.length <= MAX_IMPORT_CHARS) { "配置文件不能超过 ${MAX_IMPORT_CHARS / 1024} KiB" }
        val trimmed = text.trim()
        val current = load()
        if (trimmed.startsWith("{")) {
            require(trimmed.contains("schemaVersion")) { "缺少 schemaVersion 字段" }
            require(trimmed.contains("providerId")) { "缺少 providerId 字段" }
            val parsed = json.decodeFromString<ZLibraryConfig>(trimmed)
            val errors = ZLibraryConfigValidator.validate(parsed)
            require(errors.isEmpty()) { errors.joinToString("；") }
            val normalized = ZLibraryConfigMigration.normalize(parsed)
            val existing = current.servers.candidates.map { normalize(it.baseUrl) }.toSet()
            val added = normalized.servers.candidates
                .filter { normalize(it.baseUrl) !in existing }
                .map { it.baseUrl }
            val duplicates = normalized.servers.candidates
                .filter { normalize(it.baseUrl) in existing }
                .map { it.baseUrl }
            ServerImportPreview(
                candidates = normalized.servers.candidates,
                added = added,
                duplicates = duplicates,
                invalid = emptyList(),
                fullConfig = normalized,
            )
        } else {
            val existing = current.servers.candidates.map { normalize(it.baseUrl) }.toSet()
            val added = mutableListOf<String>()
            val duplicates = mutableListOf<String>()
            val invalid = mutableListOf<Pair<String, String>>()
            val candidates = mutableListOf<ServerCandidate>()
            val seen = HashSet<String>()
            trimmed.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { raw ->
                val error = ServerUrlPolicy.validate(raw)
                if (error != null) {
                    invalid.add(raw to error)
                    return@forEach
                }
                val key = normalize(raw)
                if (key in seen) return@forEach
                seen.add(key)
                if (key in existing) {
                    duplicates.add(raw)
                } else {
                    added.add(raw)
                    candidates.add(
                        ServerCandidate(
                            id = "imported-${candidates.size + 1}",
                            baseUrl = raw.trimEnd('/'),
                            enabled = true,
                            priority = current.servers.candidates.size + candidates.size + 1,
                        ),
                    )
                }
            }
            require(candidates.isNotEmpty() || invalid.isNotEmpty() || duplicates.isNotEmpty()) {
                "导入内容为空"
            }
            ServerImportPreview(candidates, added, duplicates, invalid)
        }
    }

    /** 兼容旧调用：解析并校验完整配置；不写盘。 */
    fun importText(text: String): Result<ZLibraryConfig> = runCatching {
        val preview = previewImport(text).getOrThrow()
        preview.fullConfig ?: throw IllegalArgumentException("该内容为候选地址列表，请使用列表导入")
    }

    /** 应用导入预览：replace=true 覆盖现有列表，false 合并（保留现有项设置）。 */
    suspend fun applyImport(preview: ServerImportPreview, replace: Boolean): Result<ZLibraryConfig> {
        val full = preview.fullConfig
        val base = if (replace && full != null) full else load()
        val merged: ZLibraryConfig = when {
            full != null && replace -> full
            else -> {
                val existing = if (replace) emptyList() else base.servers.candidates
                val existingKeys = existing.map { normalize(it.baseUrl) }.toSet()
                val appended = preview.candidates.filter { normalize(it.baseUrl) !in existingKeys }
                base.copy(servers = base.servers.copy(candidates = existing + appended))
            }
        }
        return save(ZLibraryConfigMigration.normalize(merged))
    }

    suspend fun save(config: ZLibraryConfig): Result<ZLibraryConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = ZLibraryConfigMigration.normalize(config)
            val errors = ZLibraryConfigValidator.validate(normalized)
            require(errors.isEmpty()) { errors.joinToString("；") }
            file.parentFile?.mkdirs()
            if (file.exists()) {
                val tempBackup = File(file.path + ".bak.tmp")
                tempBackup.writeText(file.readText())
                check(tempBackup.renameTo(backup)) { "备份上一份配置失败" }
            }
            val temp = File(file.path + ".tmp")
            temp.writeText(json.encodeToString(normalized))
            check(temp.renameTo(file)) { "写入配置失败" }
            // 配置变更：探测结果作废，避免旧缓存误用新地址。
            saveState(DiscoveryState())
            normalized
        }
    }

    /** 回滚到上一份有效配置（运行状态不回滚，由调用方按需清理）。 */
    suspend fun rollback(): Result<ZLibraryConfig> = withContext(Dispatchers.IO) {
        runCatching {
            check(backup.exists()) { "没有可回滚的历史配置" }
            val config = json.decodeFromString<ZLibraryConfig>(backup.readText())
            val normalized = ZLibraryConfigMigration.normalize(config)
            val errors = ZLibraryConfigValidator.validate(normalized)
            require(errors.isEmpty()) { "历史配置无效：${errors.joinToString("；")}" }
            val temp = File(file.path + ".tmp")
            temp.writeText(json.encodeToString(normalized))
            check(temp.renameTo(file)) { "回滚失败" }
            saveState(DiscoveryState())
            normalized
        }
    }

    /** 恢复内置默认服务器列表；保留端点、偏好、模式等其他设置。 */
    suspend fun resetServers(): Result<ZLibraryConfig> = withContext(Dispatchers.IO) {
        val current = load()
        save(
            current.copy(
                servers = current.servers.copy(
                    candidates = listOf(ZLibraryConfig.DEFAULT_CANDIDATE),
                    manualBaseUrl = ZLibraryConfig.DEFAULT_CANDIDATE.baseUrl,
                ),
            ),
        )
    }

    /** 恢复全部内置默认配置（保留兼容旧调用）。 */
    suspend fun reset(): Result<ZLibraryConfig> = save(ZLibraryConfig.DEFAULT)

    fun loadState(): DiscoveryState = try {
        stateFile.takeIf { it.exists() }?.readText()?.let { json.decodeFromString<DiscoveryState>(it) }
            ?: DiscoveryState()
    } catch (e: Exception) {
        DiscoveryState()
    }

    suspend fun saveState(state: DiscoveryState) = withContext(Dispatchers.IO) {
        runCatching {
            stateFile.parentFile?.mkdirs()
            val temp = File(stateFile.path + ".tmp")
            temp.writeText(json.encodeToString(state))
            check(temp.renameTo(stateFile)) { "写入运行状态失败" }
        }
    }

    suspend fun testConnection(config: ZLibraryConfig = load()): Result<String> = withContext(Dispatchers.IO) {
        ZLibraryConnectionTester.test(config)
    }

    private fun normalize(raw: String): String =
        runCatching { ServerUrlPolicy.normalize(raw) }.getOrNull() ?: raw.trim().trimEnd('/')

    companion object {
        const val MAX_IMPORT_CHARS = 256 * 1024
    }
}
