package com.yuezhou.reader.reader

import android.content.Context
import com.yuezhou.reader.core.cloud.CloudBackend
import com.yuezhou.reader.core.cloud.CloudException
import com.yuezhou.reader.core.cloud.CloudService
import com.yuezhou.reader.core.cloud.ErrorCode
import com.yuezhou.reader.core.cloud.ProgressPayload
import com.yuezhou.reader.core.cloud.baidu.BaiduBackend
import com.yuezhou.reader.core.cloud.webdav.WebDavBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 云同步桥接层：把本地 [CloudCredentials] 转成具体的 [CloudBackend]，
 * 再交给 core 的 [CloudService] 执行同步编排。app 层只依赖本类，不直接触碰后端工厂。
 *
 * 对齐方案 §13：凭据由 [CloudCredentialStore] 加密落盘，本层只读取、不缓存明文。
 * 后端二选一：优先百度（已授权时），否则 WebDAV（已配置时）。
 */
class CloudSync(private val context: Context) {

    private val credentialStore = CloudCredentialStore(context)

    /** 同步结果：成功与否 + 用户可读消息。 */
    data class SyncOutcome(val success: Boolean, val message: String)

    /**
     * 从凭据构造后端。返回 null 表示尚未配置任何后端。
     *
     * @throws CloudException 凭据格式非法（如应用目录不是 /apps/xxx、token 缺失）。
     */
    suspend fun backend(): CloudBackend? {
        val creds = credentialStore.load()
        return when {
            creds.baiduAccessToken != null && creds.baiduAppDir != null -> {
                BaiduBackend.create(
                    appDir = creds.baiduAppDir,
                    accessToken = creds.baiduAccessToken,
                    expiresAt = creds.baiduExpiresAt ?: 0L,
                    userId = creds.baiduUserId,
                )
            }
            creds.webdavRootUrl != null && creds.webdavUsername != null -> {
                WebDavBackend.create(
                    rootUrl = creds.webdavRootUrl,
                    username = creds.webdavUsername,
                    password = creds.webdavPassword ?: "",
                )
            }
            else -> null
        }
    }

    /**
     * 执行一次「本地 → 云端」同步：
     * 1. 上传书库所有正文（同名覆盖，保留封面与进度）；
     * 2. 上传每本书的阅读进度。
     *
     * 首版为单向推送，不做远端拉取合并（方案 §4 的完整双向同步依赖 Room 索引，后续实现）。
     *
     * @param onBookProgress 单本书上传进度的回调（用于 UI 展示进度），可为 null。
     */
    suspend fun pushLocal(onBookProgress: ((done: Int, total: Int) -> Unit)? = null): SyncOutcome =
        withContext(Dispatchers.IO) {
            try {
                val backend = backend()
                    ?: return@withContext SyncOutcome(false, "尚未配置云后端，请先在设置页配置 WebDAV 或百度网盘")
                // 授权就绪检查（百度校验 token 未过期、WebDAV 校验凭据可用）
                backend.ensureAuthorized()
                val service = CloudService(backend)

                val store = LibraryStore(context)
                val snapshot = store.snapshot()
                if (snapshot.books.isEmpty()) {
                    return@withContext SyncOutcome(true, "本地书库为空，无需同步")
                }

                val total = snapshot.books.size
                var done = 0
                for (entry in snapshot.books) {
                    val epub = java.io.File(EpubImporter.epubDir(context), entry.fileName)
                    if (!epub.exists()) continue
                    // 上传正文（同名覆盖），封面用本地已提取的（如有则回传）
                    service.uploadBook(epub, entry.fileName, makeCover = null)
                    // 上传进度（有进度才推）
                    if (entry.location != null) {
                        service.uploadProgress(
                            bookId = entry.bookId,
                            progress = ProgressPayload(
                                xpointer = entry.location,
                                percent = entry.percent,
                                size = entry.size,
                                deviceId = deviceId(),
                            ),
                        )
                    }
                    done++
                    onBookProgress?.invoke(done, total)
                }

                SyncOutcome(true, "同步完成：$done/$total 本")
            } catch (e: CloudException) {
                SyncOutcome(false, "同步失败：${e.message}")
            } catch (e: Exception) {
                SyncOutcome(false, "同步失败：${e.message ?: e.toString()}")
            }
        }

    private fun deviceId(): String {
        val sp = context.getSharedPreferences("library", Context.MODE_PRIVATE)
        var id = sp.getString("deviceId", null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString()
            sp.edit().putString("deviceId", id).apply()
        }
        return id
    }
}
