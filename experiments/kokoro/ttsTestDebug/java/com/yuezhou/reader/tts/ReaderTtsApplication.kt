package com.yuezhou.reader.tts

import com.yuezhou.reader.ReaderApplication
import com.yuezhou.reader.speech.SpeechRuntime

/** 测试版应用入口：仅在主进程注入 Kokoro；网页进程由基类的进程守卫跳过。 */
class ReaderTtsApplication : ReaderApplication() {

    override fun installSpeechEngines() {
        SpeechRuntime.installKokoro(SherpaKokoroProvider(this))
    }
}
