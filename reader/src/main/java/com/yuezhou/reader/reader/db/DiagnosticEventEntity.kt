package com.yuezhou.reader.reader.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.yuezhou.reader.core.cloud.DiagnosticSummary

/**
 * 诊断事件的 Room 实体（对齐方案 §8.2「DiagnosticEvent」表）。
 *
 * 保存单次同步/刷新任务的脱敏摘要（请求数/字节/耗时/缓存命中/错误/确定性），
 * 不含 token、密码、SecretKey、下载 URL 或书籍正文。以自增 id 为主键，
 * 追加式写入；读取时按时间倒序并限制最近 20 条。
 */
@Entity(tableName = "diagnostic_event")
data class DiagnosticEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val at: Long,
    val totalRequests: Long,
    val redirects: Long,
    val retries: Long,
    val bytesUp: Long,
    val bytesDown: Long,
    val cacheHits: Long,
    /** 阶段耗时（毫秒）序列化为「name=ms;name=ms」形式。 */
    val phasesMs: String,
    /** 结果确定性：Failed / Uncertain。 */
    val outcome: String,
    val errorKind: String?,
) {
    fun toSummary(): DiagnosticSummary = DiagnosticSummary(
        totalRequests = totalRequests,
        redirects = redirects,
        retries = retries,
        bytesUp = bytesUp,
        bytesDown = bytesDown,
        cacheHits = cacheHits,
        phasesMs = parsePhases(phasesMs),
        outcome = if (outcome == "Uncertain")
            com.yuezhou.reader.core.cloud.Outcome.Uncertain
        else
            com.yuezhou.reader.core.cloud.Outcome.Failed,
        errorKind = errorKind,
    )

    companion object {
        fun fromDomain(kind: String, at: Long, s: DiagnosticSummary): DiagnosticEventEntity =
            DiagnosticEventEntity(
                kind = kind,
                at = at,
                totalRequests = s.totalRequests,
                redirects = s.redirects,
                retries = s.retries,
                bytesUp = s.bytesUp,
                bytesDown = s.bytesDown,
                cacheHits = s.cacheHits,
                phasesMs = s.phasesMs.entries.joinToString(";") { "${it.key}=${it.value}" },
                outcome = s.outcome.name,
                errorKind = s.errorKind,
            )

        private fun parsePhases(s: String): Map<String, Long> {
            if (s.isEmpty()) return emptyMap()
            val map = LinkedHashMap<String, Long>()
            for (part in s.split(';')) {
                val idx = part.indexOf('=')
                if (idx <= 0) continue
                val k = part.substring(0, idx)
                val v = part.substring(idx + 1).toLongOrNull() ?: continue
                map[k] = v
            }
            return map
        }
    }
}
