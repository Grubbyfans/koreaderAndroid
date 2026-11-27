package com.yuezhou.reader.reader.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 自动同步记录的 Room 实体。
 *
 * 记录每次「拉取云端进度」或「上传本机进度」的动作与结果，供设置页查看最近 20 次。
 * 仅保存脱敏信息：方向、书名、时间戳、结果类别、结果消息，不含 token、密码或正文。
 */
@Entity(tableName = "sync_log")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 方向：pull=拉取云端进度，push=上传本机进度。 */
    val direction: String,
    /** 书名（书籍标题）。 */
    val bookTitle: String,
    /** 发生时间（epoch millis）。 */
    val at: Long,
    /** 是否成功；仅 [Outcome.FAILED] 为 false。 */
    val success: Boolean,
    /** 结果消息（成功提示或失败原因）。 */
    val message: String,
    /**
     * 结果类别：成功 / 待用户确认（含主动跳过）/ 失败。
     * 旧版本记录没有该字段，迁移时按 [success] 回填。
     */
    @ColumnInfo(defaultValue = Outcome.SUCCESS)
    val outcome: String = Outcome.SUCCESS,
) {
    object Outcome {
        const val SUCCESS = "SUCCESS"
        const val OFFER = "OFFER"
        const val FAILED = "FAILED"
    }
}
