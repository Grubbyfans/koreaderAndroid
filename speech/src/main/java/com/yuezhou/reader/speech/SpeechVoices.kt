package com.yuezhou.reader.speech

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 当前 TTS 引擎可用音色桥接：服务在引擎就绪后发布音色列表，UI 收集用于音色选择。
 *
 * 与 [SpeechProgressTracker] 同为进程内单例，服务写入、Activity 收集（同进程）。
 * 首版不持久化音色列表本身，只持久化用户选中的音色名（见 ReaderSettings.speechVoice）；
 * 音色名失效（引擎更换/卸载）时上层应回退默认音色。
 */
object SpeechVoices {

    private val _voices = MutableStateFlow<List<TtsVoice>>(emptyList())

    /** 当前引擎可用音色列表；空列表表示引擎未就绪或尚未查询。 */
    val voices: StateFlow<List<TtsVoice>> = _voices.asStateFlow()

    /** 服务在引擎就绪后发布音色列表。 */
    fun publish(available: List<TtsVoice>) {
        _voices.value = available
    }

    /** 引擎释放/停止时清空。 */
    fun clear() {
        _voices.value = emptyList()
    }
}
