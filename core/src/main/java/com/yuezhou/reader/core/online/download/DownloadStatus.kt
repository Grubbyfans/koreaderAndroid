package com.yuezhou.reader.core.online.download

/** 在线下载任务状态机（方案 §7）。 */
enum class DownloadStatus {
    QUEUED,
    PREPARING,
    DOWNLOADING,
    VERIFYING,
    IMPORTING,
    COMPLETED,
    IMPORT_FAILED,
    FAILED,
    CANCELLED;

    val active: Boolean
        get() = this == QUEUED || this == PREPARING || this == DOWNLOADING ||
            this == VERIFYING || this == IMPORTING

    companion object {
        private val allowed: Map<DownloadStatus, Set<DownloadStatus>> = mapOf(
            QUEUED to setOf(PREPARING, FAILED, CANCELLED),
            PREPARING to setOf(DOWNLOADING, FAILED, CANCELLED),
            DOWNLOADING to setOf(VERIFYING, FAILED, CANCELLED),
            VERIFYING to setOf(IMPORTING, IMPORT_FAILED, FAILED, CANCELLED),
            IMPORTING to setOf(COMPLETED, IMPORT_FAILED, FAILED, CANCELLED),
            IMPORT_FAILED to setOf(QUEUED, FAILED, CANCELLED),
            FAILED to setOf(QUEUED, CANCELLED),
            COMPLETED to emptySet(),
            CANCELLED to emptySet(),
        )

        fun canTransit(from: DownloadStatus, to: DownloadStatus): Boolean = to in allowed[from].orEmpty()

        /** 非法跳转直接抛出，避免任务状态在并发下出现不可恢复的组合。 */
        fun requireTransit(from: DownloadStatus, to: DownloadStatus) {
            require(canTransit(from, to)) { "非法下载状态跳转：$from → $to" }
        }
    }
}
