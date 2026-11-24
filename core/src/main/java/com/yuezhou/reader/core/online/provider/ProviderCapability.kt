package com.yuezhou.reader.core.online.provider

/** 书源能力声明；UI 仅显示书源支持的操作。 */
enum class ProviderCapability {
    BROWSE,
    SEARCH,
    FILTER,
    BOOK_DETAIL,
    DOWNLOAD,
    UPLOAD,
    DELETE,
    PROGRESS_SYNC,
    DOWNLOAD_QUOTA,
    CUSTOM_SERVER,
    IMPORT_CONFIG,
}
