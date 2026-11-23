package com.yuezhou.reader.tts

import com.yuezhou.reader.ReaderApplication
import com.yuezhou.reader.speech.SpeechRuntime

/** 测试版应用入口：在系统 TTS 之外注入 Kokoro 引擎。 */
class ReaderTtsApplication : ReaderApplication() {

    override fun installSpeechEngines() {
        SpeechRuntime.installKokoro(SherpaKokoroProvider(this))
    }
}
