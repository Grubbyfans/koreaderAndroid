package com.yuezhou.reader.core.cloud

import java.io.File

/**
 * 内存版 [CloudBackend]，用于单元测试验证 [CloudService] 的编排不变量，
 * 不发起任何网络请求。
 *
 * 用一个有序 Map 模拟同步根目录的文件系统：
 *   key = 相对路径（如 "books/书名.epub"、"library.json"）；
 *   value = 原始字节或文本内容。
 *
 * 支持在写操作前注入失败（[failOnUpload]、[failOnDelete]），
 * 以验证 [CloudService] 对 uncertain/失败的处理。
 */
class FakeBackend(
    override val kind: BackendKind = BackendKind.WebDav,
    override val backendId: String = "fake",
    override val root: String = "fake://root",
) : CloudBackend {

    /** 内存文件系统：路径 -> 内容字节。 */
    val files = LinkedHashMap<String, ByteArray>()

    /** 记录已创建的目录。 */
    val dirs = LinkedHashSet<String>()

    /** 路径 -> 注入的写入异常（上传小 JSON 时若命中则抛出）。 */
    val uploadFailures = mutableMapOf<String, CloudException>()

    /** 路径 -> 注入的删除异常。 */
    val deleteFailures = mutableMapOf<String, CloudException>()

    /** 记录调用历史，便于断言。 */
    val uploadCalls = mutableListOf<String>()
    val deleteCalls = mutableListOf<String>()
    val mkdirCalls = mutableListOf<String>()

    override fun capabilities(): BackendCapabilities = BackendCapabilities()

    override suspend fun ensureAuthorized() {}

    override suspend fun list(path: String, continuation: String?): ListResult {
        val prefix = path.trimEnd('/') + "/"
        val entries = files.keys.filter { it.startsWith(prefix) }
            .map { CloudEntry(it, EntryType.File, files[it]?.size?.toLong() ?: 0L) }
        return ListResult(entries)
    }

    override suspend fun stat(path: String): CloudStat {
        val data = files[path] ?: throw CloudException(ErrorCode.NOT_FOUND, "不存在: $path")
        return CloudStat(EntryType.File, data.size.toLong())
    }

    override suspend fun mkdir(path: String) {
        mkdirCalls.add(path)
        dirs.add(path)
    }

    override suspend fun downloadBytes(path: String, maxBytes: Int): ByteArray {
        val data = files[path] ?: throw CloudException(ErrorCode.NOT_FOUND, "不存在: $path")
        if (data.size > maxBytes) throw CloudException(ErrorCode.INVALID_INPUT, "超过 $maxBytes 字节")
        return data
    }

    override suspend fun downloadTo(
        path: String,
        destination: File,
        maxBytes: Long,
        onProgress: ((Long, Long) -> Unit)?,
    ) {
        val data = files[path] ?: throw CloudException(ErrorCode.NOT_FOUND, "不存在: $path")
        if (data.size > maxBytes) throw CloudException(ErrorCode.INVALID_INPUT, "超过 $maxBytes 字节")
        destination.writeBytes(data)
        onProgress?.invoke(data.size.toLong(), data.size.toLong())
    }

    override suspend fun uploadSmallJson(path: String, raw: String, precondition: Precondition) {
        uploadFailures[path]?.let { throw it }
        uploadCalls.add(path)
        files[path] = raw.toByteArray(Charsets.UTF_8)
    }

    override suspend fun upload(
        path: String,
        localFile: File,
        precondition: Precondition,
        onProgress: ((Long, Long) -> Unit)?,
    ) {
        uploadFailures[path]?.let { throw it }
        uploadCalls.add(path)
        val bytes = localFile.readBytes()
        files[path] = bytes
        onProgress?.invoke(bytes.size.toLong(), bytes.size.toLong())
    }

    override suspend fun delete(path: String, precondition: Precondition) {
        deleteFailures[path]?.let { throw it }
        deleteCalls.add(path)
        files.remove(path)
    }

    // —— 测试辅助 ——

    fun putText(path: String, text: String) {
        files[path] = text.toByteArray(Charsets.UTF_8)
    }

    fun text(path: String): String? = files[path]?.toString(Charsets.UTF_8)
}
