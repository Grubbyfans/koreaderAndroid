package com.yuezhou.reader

import android.app.Application
import com.yuezhou.reader.speech.SpeechRuntime

/**
 * 应用级装配：在进程启动时为朗读服务注入与 Activity/WebView 无关的依赖（方案 §9.1）。
 *
 * 正式版只装配系统 TTS；测试版子类会额外注入 Kokoro 引擎提供者。
 */
open class ReaderApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        SpeechRuntime.installProgressSink(RoomSpeechProgressSink(this))
        installSpeechEngines()
    }

    /** 变体扩展点：测试版在此注入 Kokoro 提供者。 */
    protected open fun installSpeechEngines() {}
}
