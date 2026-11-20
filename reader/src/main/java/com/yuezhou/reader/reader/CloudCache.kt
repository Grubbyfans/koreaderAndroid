package com.yuezhou.reader.reader

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 云端书库的持久化缓存，对齐方案 §9「书库列表与封面缓存」。
 *
 * 设计目标：让「当天重复进入书库」与「封面版本未变」都不重复走网络，且跨进程重启仍生效。
 *
 * ## 命名空间隔离（§9.1）
 * 以 [CloudBackend.backendId]（后端类型 + 账户标识 + 规范化根目录）作为缓存命名空间，
 * 不同百度账户、不同 WebDAV 根目录不混用。
 *
 * ## 列表读取（§9.2）
 * - 有缓存先展示缓存；在设备本地日历日内，每天首次进入获取一次最新 library.json；
 * - 同一天再次进入不重新取列表；
 * - 主动更新（force）不受每日缓存限制；
 * - 更新失败保留旧列表，不更新最后成功时间。
 *
 * ## 封面缓存（§9.3）
 * 缓存键包含「后端 + bookId + coverVersion」；版本不变不重复下载；单个封面失败只记占位、
 * 不随重组反复请求（防抖）。
 *
 * 本类只负责磁盘读写，不做网络请求；网络由 [CloudSync] 完成后再写回本缓存。
 */
class CloudCache(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 缓存根目录：files/cloud_cache/<namespace>/。 */
    private fun dir(namespace: String): File =
        File(context.filesDir, "cloud_cache").resolve(sanitize(namespace)).apply { mkdirs() }

    /** 把命名空间中不适合做目录名的字符替换为安全字符。 */
    private fun sanitize(s: String): String =
        s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    // region 列表缓存

    @Serializable
    data class ListCacheEntry(
        /** 已缓存的列表 JSON 文本（library.json 原文）。 */
        val raw: String,
        /** 最后成功获取时间（epoch millis）。 */
        val fetchedAt: Long,
    )

    /** 读取某命名空间的列表缓存；无缓存或跨天返回 null。 */
    fun readList(namespace: String): ListCacheEntry? {
        val f = listFile(namespace)
        if (!f.exists()) return null
        return try {
            val e = json.decodeFromString<ListCacheEntry>(f.readText())
            if (isSameDay(e.fetchedAt, System.currentTimeMillis())) e else null
        } catch (e: Exception) {
            null
        }
    }

    /** 写入列表缓存（含成功时间）。 */
    fun writeList(namespace: String, raw: String, fetchedAt: Long) {
        try {
            listFile(namespace).writeText(json.encodeToString(ListCacheEntry(raw, fetchedAt)))
        } catch (e: Exception) {
            // 缓存写失败不影响主流程
        }
    }

    /** 清除列表缓存（写操作或主动更新后调用）。 */
    fun clearList(namespace: String) {
        try { listFile(namespace).delete() } catch (e: Exception) { /* 忽略 */ }
    }

    private fun listFile(namespace: String): File = File(dir(namespace), "library.cache.json")

    /** 判断两个 epoch millis 是否属于同一本地日历日（按系统默认时区）。 */
    private fun isSameDay(a: Long, b: Long): Boolean {
        val zone = java.time.ZoneId.systemDefault()
        val da = java.time.Instant.ofEpochMilli(a).atZone(zone).toLocalDate()
        val db = java.time.Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
        return da == db
    }

    // endregion

    // region 封面缓存

    /**
     * 读取封面 PNG bytes。
     * 返回 [CoverResult]：
     * - Hit(bytes)：命中，直接用；
     * - Miss：无缓存（含版本变化），需联网拉取；
     * - Failed：已确认拉取失败（占位），本轮不再重试（防抖）。
     */
    sealed interface CoverResult {
        data class Hit(val pngBytes: ByteArray?) : CoverResult
        object Miss : CoverResult
        object Failed : CoverResult
    }

    fun readCover(namespace: String, bookId: String, coverVersion: String): CoverResult {
        val key = coverKey(namespace, bookId)
        val meta = File(dir(namespace), "$key.meta")
        if (!meta.exists()) return CoverResult.Miss
        return try {
            val m = json.decodeFromString<CoverMeta>(meta.readText())
            if (m.coverVersion != coverVersion) {
                // 版本变化：旧缓存失效，需重新拉取
                CoverResult.Miss
            } else if (m.failed) {
                CoverResult.Failed
            } else {
                val data = File(dir(namespace), "$key.png")
                val bytes = if (data.exists()) data.readBytes() else null
                CoverResult.Hit(bytes)
            }
        } catch (e: Exception) {
            CoverResult.Miss
        }
    }

    /** 写入成功拉取的封面 PNG。 */
    fun writeCover(namespace: String, bookId: String, coverVersion: String, pngBytes: ByteArray?) {
        val key = coverKey(namespace, bookId)
        try {
            val meta = CoverMeta(coverVersion, failed = pngBytes == null)
            File(dir(namespace), "$key.meta").writeText(json.encodeToString(meta))
            if (pngBytes != null) {
                File(dir(namespace), "$key.png").writeBytes(pngBytes)
            } else {
                File(dir(namespace), "$key.png").delete()
            }
        } catch (e: Exception) {
            // 忽略
        }
    }

    /** 记录封面拉取失败（防抖：本轮/下次进入不再反复请求）。 */
    fun markCoverFailed(namespace: String, bookId: String, coverVersion: String) {
        writeCover(namespace, bookId, coverVersion, null)
    }

    @Serializable
    private data class CoverMeta(val coverVersion: String, val failed: Boolean)

    private fun coverKey(namespace: String, bookId: String): String =
        "$namespace/$bookId"

    // endregion
}
