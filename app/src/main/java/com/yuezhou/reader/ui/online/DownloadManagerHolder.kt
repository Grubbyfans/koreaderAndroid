package com.yuezhou.reader.ui.online

import android.content.Context
import com.yuezhou.reader.ReaderActivity
import com.yuezhou.reader.core.online.provider.ProviderRegistry
import com.yuezhou.reader.reader.CloudSync
import com.yuezhou.reader.reader.online.cloud.BaiduBookProvider
import com.yuezhou.reader.reader.online.cloud.WebDavBookProvider
import com.yuezhou.reader.reader.online.download.BookDownloadManager
import com.yuezhou.reader.reader.online.zlibrary.ZLibraryBookProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主进程唯一的在线下载运行时（阶段 B）。
 *
 * 所有在线书源与外部下载接收共用同一个 [BookDownloadManager]，保证并发 2、任务恢复和同名覆盖
 * 互斥只存在一份。任务恢复只在首次创建时异步执行一次，不再由每个 OnlineLibraryViewModel
 * 各自触发；网页进程（阶段 C 起）不会构造本 Holder，因此不会初始化 Room/同步服务。
 */
object DownloadManagerHolder {

    class Runtime(
        val registry: ProviderRegistry,
        val zLibraryProvider: ZLibraryBookProvider,
        val manager: BookDownloadManager,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recoveryStarted = AtomicBoolean(false)

    @Volatile
    private var runtime: Runtime? = null

    fun runtime(context: Context): Runtime {
        runtime?.let { return it }
        return synchronized(this) {
            runtime ?: build(context.applicationContext).also { created ->
                runtime = created
                if (recoveryStarted.compareAndSet(false, true)) {
                    scope.launch { created.manager.recoverInterrupted() }
                }
            }
        }
    }

    private fun build(app: Context): Runtime {
        val cloudSync = CloudSync(app)
        val zLibrary = ZLibraryBookProvider(app)
        val registry = ProviderRegistry(
            listOf(
                BaiduBookProvider(app, cloudSync),
                WebDavBookProvider(app, cloudSync),
                zLibrary,
            ),
        )
        val manager = BookDownloadManager.create(
            context = app,
            registry = registry,
            notifier = AppDownloadNotifier(app),
            isBookOpen = ReaderActivity::isBookOpen,
        )
        return Runtime(registry = registry, zLibraryProvider = zLibrary, manager = manager)
    }
}
