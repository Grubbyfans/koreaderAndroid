package com.yuezhou.reader.reader.online.config

import android.content.Context
import com.yuezhou.reader.core.online.config.ZLibraryConfig
import com.yuezhou.reader.core.online.config.ZLibraryConfigValidator
import com.yuezhou.reader.core.online.config.ZLibraryConnectionTester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Z-Library 配置存储（方案 §6）：JSON 文件 + 备份回滚 + 恢复默认。
 *
 * - 文件位于应用私有 `files/providers/zlibrary_config.json`，不含任何凭证。
 * - 导入无效配置不覆盖当前可用配置。
 * - 保存前备份上一份到 `.bak`，支持回滚。
 */
class ZLibraryConfigStore(private val context: Context) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file: File get() = File(context.filesDir, "providers/zlibrary_config.json")
    private val backup: File get() = File(context.filesDir, "providers/zlibrary_config.json.bak")

    fun load(): ZLibraryConfig {
        val text = try {
            file.takeIf { it.exists() }?.readText() ?: return ZLibraryConfig.DEFAULT
        } catch (e: Exception) {
            return ZLibraryConfig.DEFAULT
        }
        return runCatching { json.decodeFromString<ZLibraryConfig>(text) }
            .getOrNull()
            ?.takeIf { ZLibraryConfigValidator.validate(it).isEmpty() }
            ?: ZLibraryConfig.DEFAULT
    }

    fun hasCustomConfig(): Boolean = file.exists()

    fun hasBackup(): Boolean = backup.exists()

    fun exportJson(config: ZLibraryConfig = load()): String = json.encodeToString(config)

    /** 解析并校验导入文本；不写盘，由调用方确认后再 save。 */
    fun importText(text: String): Result<ZLibraryConfig> = runCatching {
        require(text.contains("schemaVersion")) { "缺少 schemaVersion 字段" }
        require(text.contains("providerId")) { "缺少 providerId 字段" }
        val config = json.decodeFromString<ZLibraryConfig>(text)
        val errors = ZLibraryConfigValidator.validate(config)
        require(errors.isEmpty()) { errors.joinToString("；") }
        config
    }

    suspend fun save(config: ZLibraryConfig): Result<ZLibraryConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val errors = ZLibraryConfigValidator.validate(config)
            require(errors.isEmpty()) { errors.joinToString("；") }
            file.parentFile?.mkdirs()
            if (file.exists()) {
                val tempBackup = File(file.path + ".bak.tmp")
                tempBackup.writeText(file.readText())
                check(tempBackup.renameTo(backup)) { "备份上一份配置失败" }
            }
            val temp = File(file.path + ".tmp")
            temp.writeText(json.encodeToString(config))
            check(temp.renameTo(file)) { "写入配置失败" }
            config
        }
    }

    /** 回滚到上一份有效配置。 */
    suspend fun rollback(): Result<ZLibraryConfig> = withContext(Dispatchers.IO) {
        runCatching {
            check(backup.exists()) { "没有可回滚的历史配置" }
            val config = json.decodeFromString<ZLibraryConfig>(backup.readText())
            val errors = ZLibraryConfigValidator.validate(config)
            require(errors.isEmpty()) { "历史配置无效：${errors.joinToString("；")}" }
            val temp = File(file.path + ".tmp")
            temp.writeText(backup.readText())
            check(temp.renameTo(file)) { "回滚失败" }
            config
        }
    }

    /** 恢复内置默认配置（当前配置会备份，可回滚）。 */
    suspend fun reset(): Result<ZLibraryConfig> = withContext(Dispatchers.IO) {
        save(ZLibraryConfig.DEFAULT)
    }

    suspend fun testConnection(config: ZLibraryConfig = load()): Result<String> = withContext(Dispatchers.IO) {
        ZLibraryConnectionTester.test(config)
    }
}
