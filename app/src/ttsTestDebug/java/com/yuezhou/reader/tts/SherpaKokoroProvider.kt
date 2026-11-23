package com.yuezhou.reader.tts

import android.content.Context
import android.app.ActivityManager
import android.net.Uri
import com.yuezhou.reader.core.speech.SessionToken
import com.yuezhou.reader.core.speech.KokoroMemoryPolicy
import com.yuezhou.reader.core.speech.KokoroModelSlot
import com.yuezhou.reader.core.speech.SpeechSynthesizer
import com.yuezhou.reader.core.speech.SynthesizedAudio
import com.yuezhou.reader.speech.AudioTrackPcmPlayer
import com.yuezhou.reader.speech.KokoroEngineProvider
import com.yuezhou.reader.speech.KokoroImportResult
import com.yuezhou.reader.speech.KokoroModel
import com.yuezhou.reader.speech.KokoroModelInfo
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 测试版 Kokoro 引擎提供者：按内部模型目录加载 sherpa 引擎。 */
internal class SherpaKokoroProvider(private val context: Context) : KokoroEngineProvider {

    private val filesDir: File = context.applicationContext.filesDir

    /** 模型 ID → 当前活跃合成器数量；删除模型前必须为 0。 */
    private val activeModels = HashMap<String, Int>()
    private val modelSlot = KokoroModelSlot()

    override fun availableModels(): List<KokoroModel> =
        modelInfos().map { KokoroModel(id = it.id, label = it.label) }

    override fun modelInfos(): List<KokoroModelInfo> =
        KokoroModels.modelDirs(filesDir).map { KokoroModels.modelInfo(it) }

    override val defaultModelId: String
        get() = availableModels().firstOrNull()?.id ?: "int8"

    override suspend fun createSynthesizer(modelId: String, speakerId: Int): SpeechSynthesizer {
        val lease = modelSlot.acquire()
        val caller = currentCoroutineContext()
        return withContext(NonCancellable + Dispatchers.IO) {
            var inner: SherpaKokoroSynthesizer? = null
            retain(modelId)
            try {
                check(KokoroModels.isAvailable(filesDir, modelId)) { "模型 $modelId 未部署或文件不完整" }
                val memory = ActivityManager.MemoryInfo()
                context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
                KokoroMemoryPolicy.requireAvailable(
                    memory.availMem, memory.threshold, memory.lowMemory,
                    KokoroModels.modelFile(KokoroModels.dir(filesDir, modelId))?.length() ?: 0L,
                )
                val engine = SherpaKokoroSynthesizer(KokoroModels.createTts(KokoroModels.dir(filesDir, modelId)), speakerId)
                inner = engine
                caller.ensureActive()
                val released = AtomicBoolean(false)
                object : SpeechSynthesizer {
                    override suspend fun synthesize(
                        text: String, segmentIndex: Int, speed: Float, token: SessionToken,
                    ): SynthesizedAudio = engine.synthesize(text, segmentIndex, speed, token)

                    override suspend fun release() {
                        if (!released.compareAndSet(false, true)) return
                        withContext(NonCancellable + Dispatchers.IO) {
                            try { engine.release() }
                            finally { releaseModel(modelId); lease.release() }
                        }
                    }
                }
            } catch (t: Throwable) {
                try { inner?.release() }
                finally { releaseModel(modelId); lease.release() }
                throw t
            }
        }
    }

    override suspend fun importModel(uri: Uri): KokoroImportResult =
        KokoroModelImporter.import(context, uri)

    override suspend fun deleteModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(activeModels) {
            if ((activeModels[modelId] ?: 0) > 0) return@withContext false
        }
        KokoroModelImporter.delete(filesDir, modelId)
    }

    override suspend fun auditionModel(modelId: String, speakerId: Int, text: String): String? =
        withContext(Dispatchers.IO) {
            synchronized(activeModels) {
                if (activeModels.isNotEmpty()) return@withContext "请先停止朗读，等待模型释放后再试音"
            }
            val synthesizer = try {
                createSynthesizer(modelId, speakerId)
            } catch (e: Exception) {
                return@withContext "无法加载模型：${e.message}"
            }
            try {
                val audio = synthesizer.synthesize(text, 0, 1.0f, SessionToken(0L, 0L))
                if (audio.samples.isEmpty()) return@withContext "合成结果为空"
                playOnce(audio)
            } catch (e: Exception) {
                "试音失败：${e.message}"
            } finally {
                runCatching { synthesizer.release() }
            }
        }

    private suspend fun playOnce(audio: SynthesizedAudio): String? {
        val player = AudioTrackPcmPlayer()
        val done = CompletableDeferred<Unit>()
        try {
            player.setCompletionListener { _, _ -> done.complete(Unit) }
            player.begin(1L)
            if (!player.enqueue(0, audio)) return "播放器拒绝音频"
            val durationMs = audio.samples.size * 1000L / audio.sampleRate
            val finished = withTimeoutOrNull(durationMs + 5_000) { done.await() }
            return if (finished == null) "试音播放超时" else null
        } finally {
            runCatching { player.release() }
        }
    }

    private fun retain(modelId: String) {
        synchronized(activeModels) {
            activeModels[modelId] = (activeModels[modelId] ?: 0) + 1
        }
    }

    private fun releaseModel(modelId: String) {
        synchronized(activeModels) {
            val next = (activeModels[modelId] ?: 1) - 1
            if (next <= 0) activeModels.remove(modelId) else activeModels[modelId] = next
        }
    }
}
