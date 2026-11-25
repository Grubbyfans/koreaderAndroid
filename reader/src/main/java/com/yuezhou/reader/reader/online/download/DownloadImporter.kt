package com.yuezhou.reader.reader.online.download

import android.content.Context
import com.yuezhou.reader.reader.CoverExtractor
import com.yuezhou.reader.reader.online.import.EpubValidator
import com.yuezhou.reader.reader.online.import.OnlineImportCoordinator
import com.yuezhou.reader.reader.online.import.OnlineImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 下载文件的校验、书名提取与导入端口；单元测试用假实现替代 EPUB/Android 逻辑。 */
interface DownloadImporter {
    /** 返回 null 表示通过；否则为失败原因。 */
    suspend fun validate(file: File): String?

    suspend fun extractTitle(file: File): String?

    suspend fun import(
        pending: File,
        fileName: String,
        providerId: String,
        accountScope: String,
        remoteId: String,
        overwriteApproved: Boolean,
        isBookOpen: (String) -> Boolean,
    ): OnlineImportResult
}

class AndroidDownloadImporter(context: Context) : DownloadImporter {
    private val coordinator = OnlineImportCoordinator(context)

    override suspend fun validate(file: File): String? = withContext(Dispatchers.IO) {
        val result = EpubValidator.validate(file)
        if (result.valid) null else result.reason ?: "EPUB 校验失败"
    }

    override suspend fun extractTitle(file: File): String? = withContext(Dispatchers.IO) {
        runCatching { CoverExtractor.extractTitle(file) }.getOrNull()
    }

    override suspend fun import(
        pending: File,
        fileName: String,
        providerId: String,
        accountScope: String,
        remoteId: String,
        overwriteApproved: Boolean,
        isBookOpen: (String) -> Boolean,
    ): OnlineImportResult = coordinator.import(
        pending = pending,
        fileName = fileName,
        providerId = providerId,
        accountScope = accountScope,
        remoteId = remoteId,
        overwriteApproved = overwriteApproved,
        isBookOpen = isBookOpen,
    )
}
