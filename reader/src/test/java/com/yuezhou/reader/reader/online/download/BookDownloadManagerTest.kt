package com.yuezhou.reader.reader.online.download

import com.yuezhou.reader.core.online.download.DownloadStatus
import com.yuezhou.reader.core.online.download.OnlineDownloadTask
import com.yuezhou.reader.core.online.provider.ProviderRegistry
import com.yuezhou.reader.reader.online.import.OnlineImportResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStream

/**
 * 阶段 B 下载管理器测试：全部使用假存储/假导入/假外部来源与临时目录，
 * 不依赖 Android、Room、Robolectric，覆盖单例幂等、外部交接、恢复、命名与覆盖。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BookDownloadManagerTest {

    @TempDir
    lateinit var root: File

    // —— 1. 单例语义：稳定 transferId → 确定性 taskId，重复恢复不改状态 ——

    @Test
    fun `稳定 transferId 生成确定性 taskId`() {
        val transferId = "6f1f6c2e-6f1d-4b5a-9d0e-1e2f3a4b5c6d"
        assertEquals(
            BookDownloadManager.taskIdFor(transferId),
            BookDownloadManager.taskIdFor(transferId),
        )
        assertFalse(
            BookDownloadManager.taskIdFor(transferId) ==
                BookDownloadManager.taskIdFor("6f1f6c2e-6f1d-4b5a-9d0e-1e2f3a4b5c6e"),
        )
    }

    @Test
    fun `恢复幂等且不重复导入已完成任务`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        val receipt = f.receipt(TRANSFER_A, name = "三体.epub")
        f.manager.acceptCompletedDownload(receipt)
        val task = f.store.all().single()
        assertEquals(DownloadStatus.COMPLETED, task.status)
        assertEquals(1, f.importer.importCalls.size)

        repeat(2) { f.manager.recoverInterrupted() }

        assertEquals(1, f.store.all().size)
        assertEquals(DownloadStatus.COMPLETED, f.store.find(task.taskId)?.status)
        assertEquals(1, f.importer.importCalls.size)
    }

    // —— 2. 外部任务重复交接 ——

    @Test
    fun `同一 transferId 重复交接只保留一条任务`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        val receipt = f.receipt(TRANSFER_B, name = "三体.epub")

        val first = f.manager.acceptCompletedDownload(receipt)
        val second = f.manager.acceptCompletedDownload(receipt)

        assertTrue(first is ExternalAcceptResult.Accepted)
        assertTrue(second is ExternalAcceptResult.Duplicate)
        assertEquals(1, f.store.all().size)
        assertEquals(1, f.importer.importCalls.size)
        assertFalse(File(f.files.handoffDir(), "${TRANSFER_B}.receipt").exists())
    }

    @Test
    fun `并发重复交接只产生一次导入`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        val receipt = f.receipt(TRANSFER_C, name = "三体.epub")

        val results = listOf(
            async(Dispatchers.Default) { f.manager.acceptCompletedDownload(receipt) },
            async(Dispatchers.Default) { f.manager.acceptCompletedDownload(receipt) },
        ).awaitAll()

        assertEquals(1, results.count { it is ExternalAcceptResult.Accepted })
        assertEquals(1, results.count { it is ExternalAcceptResult.Duplicate })
        assertEquals(1, f.store.all().size)
        assertEquals(1, f.importer.importCalls.size)
    }

    // —— 3. 非法 URI 与任务归属校验 ——

    @Test
    fun `非 content URI 被拒绝`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        val result = f.manager.acceptCompletedDownload(
            f.receipt(TRANSFER_D, uri = "file:///sdcard/Download/book.epub", name = "三体.epub"),
        )
        assertTrue(result is ExternalAcceptResult.Rejected)
        assertTrue(f.store.all().isEmpty())
        assertEquals(0, f.importer.importCalls.size)
    }

    @Test
    fun `不受信任的 content authority 被拒绝`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        val result = f.manager.acceptCompletedDownload(
            f.receipt(TRANSFER_E, uri = "content://com.evil.provider/book/1", name = "三体.epub"),
        )
        assertTrue(result is ExternalAcceptResult.Rejected)
        assertTrue(f.store.all().isEmpty())
    }

    @Test
    fun `下载任务归属校验失败被拒绝`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        // 不把 URI 标记为本应用下载：模拟任意第三方 URI。
        val result = f.manager.acceptCompletedDownload(
            f.receipt(TRANSFER_F, name = "三体.epub", owned = false),
        )
        assertTrue(result is ExternalAcceptResult.Rejected)
        assertEquals("下载任务不属于当前应用", (result as ExternalAcceptResult.Rejected).reason)
        assertTrue(f.store.all().isEmpty())
    }

    // —— 4. 文件超限与长度不一致 ——

    @Test
    fun `声明大小超过上限被拒绝`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        val result = f.manager.acceptCompletedDownload(
            f.receipt(TRANSFER_G, name = "三体.epub", expected = MAX_EXTERNAL_DOWNLOAD_BYTES + 1),
        )
        assertTrue(result is ExternalAcceptResult.Rejected)
        assertTrue(f.store.all().isEmpty())
    }

    @Test
    fun `实际长度与交接信息不一致被拒绝`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        val result = f.manager.acceptCompletedDownload(
            f.receipt(TRANSFER_H, name = "三体.epub", expected = 5),
        )
        assertTrue(result is ExternalAcceptResult.Rejected)
        assertEquals("文件大小与交接信息不一致", (result as ExternalAcceptResult.Rejected).reason)
    }

    // —— 5. 损坏 EPUB ——

    @Test
    fun `损坏 EPUB 失败且清理暂存文件`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        f.importer.corrupt = true

        val result = f.manager.acceptCompletedDownload(f.receipt(TRANSFER_I, name = "三体.epub"))

        assertTrue(result is ExternalAcceptResult.Failed)
        val task = f.store.all().single()
        assertEquals(DownloadStatus.FAILED, task.status)
        assertEquals(0, f.importer.importCalls.size)
        assertTrue(f.files.pendingDir().listFiles()!!.isEmpty())
        assertFalse(File(f.files.handoffDir(), "${TRANSFER_I}.receipt").exists())
    }

    // —— 6. 占位文件名处理 ——

    @Test
    fun `占位文件名回退到 OPF 标题`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        f.importer.title = "三体"

        val result = f.manager.acceptCompletedDownload(
            f.receipt(TRANSFER_J, name = "download.epub"),
        )

        assertTrue(result is ExternalAcceptResult.Accepted)
        assertFalse((result as ExternalAcceptResult.Accepted).awaitingName)
        val task = f.store.all().single()
        assertEquals("三体.epub", task.fileName)
        assertEquals(DownloadStatus.COMPLETED, task.status)
    }

    @Test
    fun `占位文件名且无有效标题进入待命名`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        f.importer.title = "download"

        val result = f.manager.acceptCompletedDownload(
            f.receipt(TRANSFER_K, name = "download.epub"),
        )

        assertTrue(result is ExternalAcceptResult.Accepted)
        assertTrue((result as ExternalAcceptResult.Accepted).awaitingName)
        val task = f.store.all().single()
        assertEquals(DownloadStatus.AWAITING_NAME, task.status)
        assertTrue(File(task.localPath!!).isFile)
        assertEquals(0, f.importer.importCalls.size)
    }

    @Test
    fun `待命名确认后继续导入`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        f.importer.title = null
        val accepted = f.manager.acceptCompletedDownload(f.receipt(TRANSFER_L, name = "file.epub"))
        val taskId = (accepted as ExternalAcceptResult.Accepted).taskId

        val ok = f.manager.confirmName(taskId, "三体")

        assertTrue(ok)
        val task = f.store.find(taskId)!!
        assertEquals(DownloadStatus.COMPLETED, task.status)
        assertEquals("三体.epub", task.fileName)
        assertEquals(1, f.importer.importCalls.size)
        assertFalse(File(f.files.pendingDir(), "$taskId.epub").exists())
    }

    // —— 7. 待命名状态恢复 ——

    @Test
    fun `待命名任务恢复后保留有效暂存文件`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        val pending = pendingFile(f, "task-await")
        val task = task("task-await", DownloadStatus.AWAITING_NAME, pending.path, fileName = "book.epub")
        f.store.save(task)

        f.manager.recoverInterrupted()

        assertEquals(DownloadStatus.AWAITING_NAME, f.store.find("task-await")?.status)
        assertTrue(pending.isFile)
    }

    @Test
    fun `待命名任务暂存文件失效时标记失败`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        f.store.save(task("task-await-lost", DownloadStatus.AWAITING_NAME, null, fileName = "book.epub"))

        f.manager.recoverInterrupted()

        val restored = f.store.find("task-await-lost")!!
        assertEquals(DownloadStatus.FAILED, restored.status)
        assertEquals("暂存文件已失效，请重新下载", restored.errorMessage)
    }

    // —— 8. 同名覆盖与取消 ——

    @Test
    fun `同名交接进入覆盖确认且不自动导入`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        f.localNames = setOf("三体.epub")

        val result = f.manager.acceptCompletedDownload(f.receipt(TRANSFER_M, name = "三体.epub"))

        assertTrue(result is ExternalAcceptResult.Accepted)
        val task = f.store.all().single()
        assertEquals(DownloadStatus.IMPORT_FAILED, task.status)
        assertEquals("本地已存在同名书籍，请确认覆盖", task.errorMessage)
        assertEquals(0, f.importer.importCalls.size)
        assertTrue(File(task.localPath!!).isFile)
    }

    @Test
    fun `确认覆盖后复用暂存文件导入`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        f.localNames = setOf("三体.epub")
        val accepted = f.manager.acceptCompletedDownload(f.receipt(TRANSFER_N, name = "三体.epub"))
        val taskId = (accepted as ExternalAcceptResult.Accepted).taskId

        f.manager.retry(taskId, overwriteApproved = true)

        val task = f.store.find(taskId)!!
        assertEquals(DownloadStatus.COMPLETED, task.status)
        assertEquals(1, f.importer.importCalls.size)
        assertTrue(f.importer.importCalls.single().overwriteApproved)
        assertEquals("三体.epub", f.importer.importCalls.single().fileName)
    }

    @Test
    fun `取消同名任务清理暂存与交接文件`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        f.localNames = setOf("三体.epub")
        val accepted = f.manager.acceptCompletedDownload(f.receipt(TRANSFER_O, name = "三体.epub"))
        val taskId = (accepted as ExternalAcceptResult.Accepted).taskId
        val pending = File(f.files.pendingDir(), "$taskId.epub")
        assertTrue(pending.isFile)
        // 恢复场景下仍可能残留回执，取消时必须一并清理。
        assertTrue(f.handoff.write(f.receipt(TRANSFER_O, name = "三体.epub")))

        f.manager.cancel(taskId)

        val task = f.store.find(taskId)!!
        assertEquals(DownloadStatus.CANCELLED, task.status)
        assertFalse(pending.exists())
        assertFalse(File(f.files.handoffDir(), "${TRANSFER_O}.receipt").exists())
    }

    @Test
    fun `并发同名交接不会静默覆盖`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        f.localNames = setOf("三体.epub")

        val results = listOf(
            async(Dispatchers.Default) {
                f.manager.acceptCompletedDownload(f.receipt(TRANSFER_P, name = "三体.epub"))
            },
            async(Dispatchers.Default) {
                f.manager.acceptCompletedDownload(f.receipt(TRANSFER_Q, name = "三体.epub"))
            },
        ).awaitAll()

        assertTrue(results.all { it is ExternalAcceptResult.Accepted })
        assertEquals(2, f.store.all().size)
        assertTrue(f.store.all().all { it.status == DownloadStatus.IMPORT_FAILED })
        assertEquals(0, f.importer.importCalls.size)
    }

    // —— 9. 进程死亡后的 pending 恢复 ——

    @Test
    fun `中断任务保留有效暂存并允许重试导入`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        val pending = pendingFile(f, "task-interrupted")
        f.store.save(task("task-interrupted", DownloadStatus.DOWNLOADING, pending.path, fileName = "三体.epub"))

        f.manager.recoverInterrupted()

        val restored = f.store.find("task-interrupted")!!
        assertEquals(DownloadStatus.IMPORT_FAILED, restored.status)
        assertEquals("任务被中断，可重试导入（不会重新下载）", restored.errorMessage)
        assertTrue(pending.isFile)

        f.manager.retry("task-interrupted")

        assertEquals(DownloadStatus.COMPLETED, f.store.find("task-interrupted")?.status)
        assertEquals(1, f.importer.importCalls.size)
        assertFalse(pending.exists())
    }

    @Test
    fun `中断任务无有效暂存标记失败且不删除受控目录外的文件`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        val outside = File(root, "outside.epub").apply { writeText("EPUB") }
        f.store.save(task("task-lost", DownloadStatus.DOWNLOADING, outside.path, fileName = "三体.epub"))

        f.manager.recoverInterrupted()

        val restored = f.store.find("task-lost")!!
        assertEquals(DownloadStatus.FAILED, restored.status)
        assertEquals("任务被中断，请重新下载", restored.errorMessage)
        // 任意外部路径不在受控目录内，既不复用也不删除。
        assertTrue(outside.isFile)
    }

    @Test
    fun `交接回执可在进程死亡后重建任务`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        val receipt = f.receipt(TRANSFER_R, name = "三体.epub")
        assertTrue(f.handoff.write(receipt))
        f.source.payload["content://downloads/my_downloads/1"] = "EPUB".toByteArray()

        f.manager.recoverInterrupted()

        val task = f.store.find(BookDownloadManager.taskIdFor(TRANSFER_R))
        assertNotNull(task)
        assertEquals(DownloadStatus.COMPLETED, task!!.status)
        assertEquals(1, f.importer.importCalls.size)
        assertFalse(File(f.files.handoffDir(), "${TRANSFER_R}.receipt").exists())
    }

    // —— 10. 导入失败重试复用暂存 ——

    @Test
    fun `导入失败重试复用暂存且不重新下载`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        f.importer.enqueueResult(OnlineImportResult.Failed("写入失败"))
        f.importer.enqueueResult(OnlineImportResult.Success("三体", "三体.epub"))
        val accepted = f.manager.acceptCompletedDownload(f.receipt(TRANSFER_S, name = "三体.epub"))
        val taskId = (accepted as ExternalAcceptResult.Accepted).taskId

        val failed = f.store.find(taskId)!!
        assertEquals(DownloadStatus.IMPORT_FAILED, failed.status)
        assertTrue(File(failed.localPath!!).isFile)

        f.manager.retry(taskId)

        assertEquals(DownloadStatus.COMPLETED, f.store.find(taskId)?.status)
        assertEquals(2, f.importer.importCalls.size)
    }

    // —— 11. 已完成任务防止重复导入 ——

    @Test
    fun `已完成任务的重复交接不再次导入`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        val receipt = f.receipt(TRANSFER_T, name = "三体.epub")
        f.manager.acceptCompletedDownload(receipt)
        val taskId = BookDownloadManager.taskIdFor(TRANSFER_T)
        assertEquals(DownloadStatus.COMPLETED, f.store.find(taskId)?.status)

        val again = f.manager.acceptCompletedDownload(receipt)

        assertTrue(again is ExternalAcceptResult.Duplicate)
        assertEquals(1, f.importer.importCalls.size)
    }

    @Test
    fun `网页任务无暂存时不重新申请下载链接`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        f.store.save(
            task("web-lost", DownloadStatus.IMPORT_FAILED, null, fileName = "三体.epub").copy(
                providerId = ZLIBRARY_WEB_PROVIDER_ID,
            ),
        )

        f.manager.retry("web-lost")

        val restored = f.store.find("web-lost")!!
        assertEquals(DownloadStatus.FAILED, restored.status)
        assertEquals("暂存文件已失效，请重新下载", restored.errorMessage)
        assertEquals(0, f.importer.importCalls.size)
    }

    // —— 12. 孤儿暂存文件安全清理 ——

    @Test
    fun `孤儿清理只删除超时且未被引用的受控文件`() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        val old = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
        val orphan = pendingFile(f, "orphan").apply { setLastModified(old) }
        val referenced = pendingFile(f, "referenced").apply { setLastModified(old) }
        val recent = pendingFile(f, "recent")
        f.store.save(task("referenced", DownloadStatus.IMPORT_FAILED, referenced.path, fileName = "三体.epub"))

        val deleted = f.manager.cleanupOrphanPending()

        assertEquals(1, deleted)
        assertFalse(orphan.exists())
        assertTrue(referenced.isFile)
        assertTrue(recent.isFile)
    }

    // —— 测试装配 ——

    private fun fixture(dispatcher: CoroutineDispatcher): Fixture {
        val f = Fixture(root)
        f.manager = BookDownloadManager(
            registry = ProviderRegistry(),
            repository = f.store,
            files = f.files,
            importer = f.importer,
            externalSource = f.source,
            handoffStore = f.handoff,
            localBookNames = { f.localNames },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        return f
    }

    private fun pendingFile(f: Fixture, taskId: String): File =
        File(f.files.pendingDir(), "$taskId.epub").apply { writeText("EPUB") }

    private fun task(
        taskId: String,
        status: DownloadStatus,
        localPath: String?,
        fileName: String,
        providerId: String = ZLIBRARY_WEB_PROVIDER_ID,
    ) = OnlineDownloadTask(
        taskId = taskId,
        providerId = providerId,
        accountScope = "acc",
        remoteId = fileName,
        bookTitle = fileName.removeSuffix(".epub"),
        fileName = fileName,
        status = status,
        localPath = localPath,
        totalBytes = 4,
    )

    private fun Fixture.receipt(
        transferId: String,
        uri: String = DEFAULT_URI,
        name: String? = null,
        expected: Long? = 4,
        owned: Boolean = true,
    ): ExternalDownloadReceipt {
        source.payload.putIfAbsent(uri, "EPUB".toByteArray())
        if (owned) source.owned += uri else source.owned -= uri
        return ExternalDownloadReceipt(
            transferId = transferId,
            contentUri = uri,
            expectedBytes = expected,
            suggestedFileName = name,
            remoteRef = "https://example.com/book/1?token=secret",
            accountScope = "acc",
            downloadId = 1,
        )
    }

    private class ImportCall(
        val fileName: String,
        val remoteId: String,
        val overwriteApproved: Boolean,
    )

    private class FakeImporter : DownloadImporter {
        var corrupt = false
        var title: String? = null
        val importCalls = mutableListOf<ImportCall>()
        private val queuedResults = ArrayDeque<OnlineImportResult>()

        fun enqueueResult(result: OnlineImportResult) {
            queuedResults.addLast(result)
        }

        override suspend fun validate(file: File): String? =
            if (corrupt || file.readText().contains("CORRUPT")) "EPUB 文件损坏" else null

        override suspend fun extractTitle(file: File): String? = title

        override suspend fun import(
            pending: File,
            fileName: String,
            providerId: String,
            accountScope: String,
            remoteId: String,
            overwriteApproved: Boolean,
            isBookOpen: (String) -> Boolean,
        ): OnlineImportResult {
            importCalls += ImportCall(fileName, remoteId, overwriteApproved)
            return queuedResults.removeFirstOrNull()
                ?: OnlineImportResult.Success("id-$fileName", fileName)
        }
    }

    private class FakeSource : ExternalDownloadSource {
        val payload = mutableMapOf<String, ByteArray>()
        val owned = mutableSetOf<String>()

        override fun verifyOwnership(uri: String, downloadId: Long?): Boolean = uri in owned

        override fun length(uri: String): Long? = payload[uri]?.size?.toLong()

        override fun open(uri: String): InputStream =
            payload[uri]?.inputStream() ?: error("缺少测试数据：$uri")
    }

    private class FakeTaskStore : DownloadTaskStore {
        private val state = MutableStateFlow<List<OnlineDownloadTask>>(emptyList())

        fun all(): List<OnlineDownloadTask> = state.value

        override fun observeAll(): Flow<List<OnlineDownloadTask>> = state

        override fun observeActiveRemoteIds(): Flow<Set<String>> = flowOf(emptySet())

        override suspend fun find(taskId: String): OnlineDownloadTask? =
            state.value.firstOrNull { it.taskId == taskId }

        override suspend fun unfinished(): List<OnlineDownloadTask> = state.value.filter {
            it.status == DownloadStatus.QUEUED ||
                it.status == DownloadStatus.PREPARING ||
                it.status == DownloadStatus.DOWNLOADING ||
                it.status == DownloadStatus.VERIFYING ||
                it.status == DownloadStatus.IMPORTING ||
                it.status == DownloadStatus.AWAITING_NAME ||
                it.status == DownloadStatus.IMPORT_FAILED
        }

        override suspend fun save(task: OnlineDownloadTask) {
            state.update { list -> list.filterNot { it.taskId == task.taskId } + task }
        }

        override suspend fun delete(taskId: String) {
            state.update { list -> list.filterNot { it.taskId == taskId } }
        }

        override suspend fun clearFinished() {
            state.update { list ->
                list.filter {
                    it.status == DownloadStatus.QUEUED ||
                        it.status == DownloadStatus.PREPARING ||
                        it.status == DownloadStatus.DOWNLOADING ||
                        it.status == DownloadStatus.VERIFYING ||
                        it.status == DownloadStatus.IMPORTING ||
                        it.status == DownloadStatus.AWAITING_NAME
                }
            }
        }
    }

    private class Fixture(root: File) {
        val store = FakeTaskStore()
        val importer = FakeImporter()
        val source = FakeSource()
        val files = object : DownloadFileStore {
            private val pending = File(root, "pending").apply { mkdirs() }
            private val temporary = File(root, "temporary").apply { mkdirs() }
            private val handoff = File(root, "handoff").apply { mkdirs() }

            override fun pendingDir(): File = pending

            override fun temporaryDir(): File = temporary

            override fun handoffDir(): File = handoff
        }
        val handoff = DownloadHandoffStore(files.handoffDir())
        var localNames: Set<String> = emptySet()
        lateinit var manager: BookDownloadManager
    }

    companion object {
        private const val DEFAULT_URI = "content://downloads/my_downloads/1"
        private const val TRANSFER_A = "00000000-0000-4000-8000-00000000000a"
        private const val TRANSFER_B = "00000000-0000-4000-8000-00000000000b"
        private const val TRANSFER_C = "00000000-0000-4000-8000-00000000000c"
        private const val TRANSFER_D = "00000000-0000-4000-8000-00000000000d"
        private const val TRANSFER_E = "00000000-0000-4000-8000-00000000000e"
        private const val TRANSFER_F = "00000000-0000-4000-8000-00000000000f"
        private const val TRANSFER_G = "00000000-0000-4000-8000-000000000010"
        private const val TRANSFER_H = "00000000-0000-4000-8000-000000000011"
        private const val TRANSFER_I = "00000000-0000-4000-8000-000000000012"
        private const val TRANSFER_J = "00000000-0000-4000-8000-000000000013"
        private const val TRANSFER_K = "00000000-0000-4000-8000-000000000014"
        private const val TRANSFER_L = "00000000-0000-4000-8000-000000000015"
        private const val TRANSFER_M = "00000000-0000-4000-8000-000000000016"
        private const val TRANSFER_N = "00000000-0000-4000-8000-000000000017"
        private const val TRANSFER_O = "00000000-0000-4000-8000-000000000018"
        private const val TRANSFER_P = "00000000-0000-4000-8000-000000000019"
        private const val TRANSFER_Q = "00000000-0000-4000-8000-00000000001a"
        private const val TRANSFER_R = "00000000-0000-4000-8000-00000000001b"
        private const val TRANSFER_S = "00000000-0000-4000-8000-00000000001c"
        private const val TRANSFER_T = "00000000-0000-4000-8000-00000000001d"
    }
}
