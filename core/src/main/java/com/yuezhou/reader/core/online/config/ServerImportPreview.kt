package com.yuezhou.reader.core.online.config

/** 候选列表导入预览：先解析分类，再由用户确认合并或替换。 */
data class ServerImportPreview(
    /** 解析出的有效候选（已去重、重排优先级）。 */
    val candidates: List<ServerCandidate>,
    /** 相对当前列表属于新增的地址。 */
    val added: List<String>,
    /** 与当前列表规范化后重复的地址。 */
    val duplicates: List<String>,
    /** 无效地址及原因。 */
    val invalid: List<Pair<String, String>>,
    /** 完整配置导入时携带的其他配置变更；纯文本导入为 null。 */
    val fullConfig: ZLibraryConfig? = null,
)
