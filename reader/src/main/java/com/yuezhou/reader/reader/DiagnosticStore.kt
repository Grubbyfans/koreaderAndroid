package com.yuezhou.reader.reader

import android.content.Context
import com.yuezhou.reader.core.cloud.DiagnosticSummary
import com.yuezhou.reader.reader.db.AppDatabase
import com.yuezhou.reader.reader.db.DiagnosticEventEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 诊断摘要的持久化存储（对齐方案 §13.2「保留最近 20 次摘要，可主动导出脱敏 JSON」）。
 *
 * 存储改用 Room（`diagnostic_event` 表，方案 §8.2）：追加式写入，读取时按时间倒序
 * 并限制最近 20 条。仅保存脱敏字段（请求数/字节/耗时/缓存命中/错误类别/确定性），
 * 不含 token、密码、SecretKey、下载 URL、Authorization 或书籍正文。
 */
class DiagnosticStore(context: Context) {

    private val dao = AppDatabase.get(context).diagnosticEventDao()

    /** 保留的最大摘要数。 */
    companion object {
        const val MAX_ENTRIES = 20
    }

    /**
     * 追加一条任务摘要，超出上限时淘汰最旧条目。
     * @param kind 任务类型（如「同步」「刷新书库」「下载」）。
     */
    suspend fun append(kind: String, summary: DiagnosticSummary) {
        try {
            dao.insert(DiagnosticEventEntity.fromDomain(kind, System.currentTimeMillis(), summary))
            // 上限淘汰：超过 MAX_ENTRIES 时删除最旧的超出部分
            val total = dao.count()
            if (total > MAX_ENTRIES) {
                dao.deleteOldest(total - MAX_ENTRIES)
            }
        } catch (_: Exception) {
            // 写失败不影响主流程
        }
    }

    /** 读取全部摘要（按时间升序），返回 (时间戳, (任务类型, 摘要))。 */
    suspend fun all(): List<Pair<Long, Pair<String, DiagnosticSummary>>> {
        return dao.allAsc().map { it.at to (it.kind to it.toSummary()) }
    }

    /** 导出脱敏 JSON 字符串（供分享/诊断）。 */
    suspend fun export(): String {
        val entries = dao.allAsc().map {
            ExportEntry(it.at, it.kind, it.toSummary())
        }
        return json.encodeToString(ExportLog(entries))
    }

    @Serializable
    private data class ExportEntry(val at: Long, val kind: String, val summary: DiagnosticSummary)

    @Serializable
    private data class ExportLog(val entries: List<ExportEntry>)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}
