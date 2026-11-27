package com.yuezhou.reader

import android.app.Application
import com.yuezhou.reader.networktts.NetworkTtsRuntimeBridge
import com.yuezhou.reader.process.AppProcess
import com.yuezhou.reader.reader.networktts.NetworkTtsRuleRepository
import com.yuezhou.reader.speech.SpeechRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 应用级装配：在进程启动时为朗读服务注入与 Activity/WebView 无关的依赖（方案 §9.1）。
 *
 * 测试版和正式版均使用系统 TTS。
 *
 * 阶段 C 进程守卫：`:zlibraryweb` 网页进程只承载 WebView，不初始化 Room（进度端口）、
 * 同步服务、阅读器或 TTS/Kokoro。应用未声明 ContentProvider / AndroidX Startup，
 * 因此网页进程启动时不存在其它提前初始化 WebView 的入口。
 */
open class ReaderApplication : Application() {

    /** 进程级后台作用域：用于启动期的非阻塞装配任务。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (!AppProcess.isMainProcess(this)) return
        SpeechRuntime.installProgressSink(RoomSpeechProgressSink(this))
        installSpeechEngines()
        NetworkTtsRuntimeBridge.install(this)

        val repository = NetworkTtsRuleRepository(this)
        val cacheRoot = NetworkTtsRuntimeBridge.cacheRoot(this)

        // 网络 TTS：ruleProvider 直接调用 suspend Room 函数，不 runBlocking，避免协程嵌套阻塞 ANR。
        SpeechRuntime.networkTtsBackendFactory = { ctx, ruleId ->
            val repo = NetworkTtsRuleRepository(ctx)
            io.legado.app.nettts.NetworkTtsPlaybackBackend(
                context = ctx,
                cacheDir = cacheRoot,
                ruleProvider = { id: Long -> repo.find(id) },
            )
        }

        // 默认网络 TTS 规则选择：取已保存规则，否则第一条。异步执行，避免阻塞冷启动主线程。
        appScope.launch {
            runCatching {
                val rules = repository.list()
                val savedId = ReaderSettings.flow(this@ReaderApplication).first().networkTtsRuleId
                SpeechRuntime.networkTtsRuleId =
                    rules.firstOrNull { it.id == savedId }?.id ?: rules.firstOrNull()?.id
            }
        }
    }

    /** 实验构建可在主进程注入自定义引擎。 */
    protected open fun installSpeechEngines() {}
}
