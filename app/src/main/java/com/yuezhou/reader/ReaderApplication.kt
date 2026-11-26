package com.yuezhou.reader

import android.app.Application
import com.yuezhou.reader.process.AppProcess
import com.yuezhou.reader.speech.SpeechRuntime

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

    override fun onCreate() {
        super.onCreate()
        if (!AppProcess.isMainProcess(this)) return
        SpeechRuntime.installProgressSink(RoomSpeechProgressSink(this))
        installSpeechEngines()
    }

    /** 实验构建可在主进程注入自定义引擎。 */
    protected open fun installSpeechEngines() {}
}
