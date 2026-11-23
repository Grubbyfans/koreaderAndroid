package com.yuezhou.reader.speech

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 朗读诊断快照（方案 §11）：引擎/模型/音色/语速、模型加载耗时、首声延迟、
 * 缓冲次数、最近音频健康摘要与最近错误。仅进程内保存，供设置页展示。
 */
data class SpeechDiagnostics(
    val engine: String = "",
    val modelId: String = "",
    val speakerId: Int = 0,
    val rate: Float = 1f,
    val modelLoadMs: Long = -1L,
    val firstSoundMs: Long = -1L,
    val bufferingCount: Int = 0,
    val healthSummary: String = "",
    val lastError: String = "",
    val textParserEnabled: Boolean = false,
    val plannerVersion: Int = 0,
    val deliveryHint: String = "",
    val emotionHint: String = "",
    val deliveryConfidence: Float = 0f,
    val deliveryRuleVersion: String = "",
    val deliveryRuleIds: String = "",
    val speedMultiplier: Float = 1f,
    val addedPauseMs: Int = 0,
    val deliveryFallback: String = "",
    val updatedAt: Long = 0L,
)

object SpeechDiagnosticsState {

    private val _snapshot = MutableStateFlow(SpeechDiagnostics())
    val snapshot: StateFlow<SpeechDiagnostics> = _snapshot.asStateFlow()

    fun resetSession(
        engine: String,
        modelId: String = "",
        speakerId: Int = 0,
        rate: Float = 1f,
        modelLoadMs: Long = -1L,
    ) {
        _snapshot.value = SpeechDiagnostics(
            engine = engine,
            modelId = modelId,
            speakerId = speakerId,
            rate = rate,
            modelLoadMs = modelLoadMs,
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun update(transform: (SpeechDiagnostics) -> SpeechDiagnostics) {
        _snapshot.update { transform(it).copy(updatedAt = System.currentTimeMillis()) }
    }
}
