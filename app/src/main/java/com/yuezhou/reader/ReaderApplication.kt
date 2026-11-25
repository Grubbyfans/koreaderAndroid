package com.yuezhou.reader

import android.app.Application
import com.yuezhou.reader.process.AppProcess
import com.yuezhou.reader.speech.SpeechRuntime

/**
 * 应用级装配：在进程启动时为朗读服务注入与 Activity/WebView 无关的依赖（方案 §9.1）。
 *
 * 正式版只装配系统 TTS；测试版子类会额外注入 Kokoro 引擎提供者。
 *
 * 阶段 C 进程守卫：`:zlibraryweb` 网页进程只承载 WebView，不初始化 Room（进度端口）、
 * 同步服务、阅读器或 TTS/Kokoro。应用未声明 ContentProvider / AndroidX Startup，
 * 因此网页进程启动时不存在其它提前初始化 WebView 的入口。
 */
open class ReaderApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (!AppProcess.isMainProcess(this)) return
        SpeechRuntime.installProgressSink(RoomSpeechProgressSink(this))
        installSpeechEngines()
    }

    /** 变体扩展点：测试版在此注入 Kokoro 提供者；仅主进程会执行。 */
    protected open fun installSpeechEngines() {}
}
