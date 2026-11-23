package com.yuezhou.reader.tts

import com.k2fsa.sherpa.onnx.OfflineTts
import android.os.SystemClock
import android.util.Log
import com.yuezhou.reader.core.speech.SessionToken
import com.yuezhou.reader.core.speech.KokoroTextNormalizer
import com.yuezhou.reader.core.speech.SpeechSynthesizer
import com.yuezhou.reader.core.speech.SynthesizedAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 阶段 C 真机验证用 sherpa 合成端口适配器（仅 ttsTest 变体）。
 *
 * 由 [com.yuezhou.reader.speech.SpeechCoordinator] 串行调用；[release] 幂等，
 * 释放后不再接受合成。
 */
class SherpaKokoroSynthesizer(
    private val tts: OfflineTts,
    private val speakerId: Int = 3,
) : SpeechSynthesizer {

    @Volatile
    private var released = false

    override suspend fun synthesize(
        text: String,
        segmentIndex: Int,
        speed: Float,
        token: SessionToken,
    ): SynthesizedAudio = withContext(Dispatchers.Default) {
        check(!released) { "引擎已释放" }
        // 试音直接使用合成器，也执行同样的输入规范化。
        val spokenText = KokoroTextNormalizer.normalize(text)
        if (spokenText.isEmpty()) return@withContext KokoroTextNormalizer.punctuationPause()
        val startedAt = SystemClock.elapsedRealtime()
        val audio = tts.generate(spokenText, speakerId, speed)
        val synthMs = SystemClock.elapsedRealtime() - startedAt
        val audioMs = audio.samples.size * 1000L / audio.sampleRate.coerceAtLeast(1)
        Log.d("KokoroSynth", "segment=$segmentIndex chars=${spokenText.length} speed=$speed synthMs=$synthMs audioMs=$audioMs")
        SynthesizedAudio(audio.samples, audio.sampleRate)
    }

    override suspend fun release() {
        if (released) return
        released = true
        withContext(Dispatchers.Default) { tts.release() }
    }
}
