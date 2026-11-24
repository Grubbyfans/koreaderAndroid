package com.yuezhou.reader.reader.online.upload

import android.content.Context
import com.yuezhou.reader.core.cloud.BackendKind
import com.yuezhou.reader.reader.CloudSync
import com.yuezhou.reader.reader.LibraryStore
import kotlinx.coroutines.CancellationException

/**
 * 阶段 F：下载导入后手动上传云端的独立桥接层。
 *
 * 仅复用 [CloudSync.pushLocal]（正文、封面、library.json 索引与待发布回执），不创建第二套目录，
 * 也不修改 bookId、进度协议。两个云端目标逐个执行、逐个记结果：一侧失败或取消不会回滚另一侧。
 *
 * 依赖以函数注入，便于在 JVM 单测中覆盖「一侧成功一侧失败」「未配置」「异常隔离」等分支。
 */
class CloudUploadBridge(
    private val findEntry: suspend () -> LibraryStore.Entry?,
    private val send: suspend (BackendKind, LibraryStore.Entry) -> SendResult,
) {

    enum class Target(val kind: BackendKind, val label: String) {
        BAIDU(BackendKind.Baidu, "百度网盘"),
        WEBDAV(BackendKind.WebDav, "WebDAV"),
    }

    sealed interface SendResult {
        data class Done(val outcome: CloudSync.SyncOutcome) : SendResult
        data object NotConfigured : SendResult
        data class Error(val message: String) : SendResult
    }

    enum class Status { UPLOADED, FAILED, SKIPPED }

    data class TargetResult(val target: Target, val status: Status, val message: String)

    data class Report(val results: List<TargetResult>) {
        val success: Boolean get() = results.any { it.status == Status.UPLOADED }
        val summary: String get() = results.joinToString("；") { "${it.target.label}：${it.message}" }
    }

    suspend fun upload(targets: List<Target> = Target.entries.toList()): Report {
        val entry = findEntry()
        if (entry == null) {
            return Report(targets.map { TargetResult(it, Status.FAILED, "本地书籍已不存在，无法上传") })
        }
        val results = targets.map { target ->
            val result = try {
                send(target.kind, entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SendResult.Error(e.message ?: "上传失败")
            }
            when (result) {
                is SendResult.Done -> TargetResult(
                    target = target,
                    status = if (result.outcome.success) Status.UPLOADED else Status.FAILED,
                    message = result.outcome.message,
                )
                SendResult.NotConfigured -> TargetResult(target, Status.SKIPPED, "未配置该云端")
                is SendResult.Error -> TargetResult(target, Status.FAILED, result.message)
            }
        }
        return Report(results)
    }

    companion object {
        fun from(context: Context, bookId: String): CloudUploadBridge {
            val sync = CloudSync(context)
            val store = LibraryStore(context)
            return CloudUploadBridge(
                findEntry = { store.snapshot().books.firstOrNull { it.bookId == bookId } },
                send = { kind, entry ->
                    val backend = sync.backend(forced = kind)
                    if (backend == null) {
                        SendResult.NotConfigured
                    } else {
                        SendResult.Done(
                            sync.pushLocal(
                                backendId = backend.backendId,
                                books = listOf(entry),
                                isStopped = { false },
                                onProgress = { _, _ -> Unit },
                                forced = kind,
                            ),
                        )
                    }
                },
            )
        }
    }
}
