package com.yuezhou.reader.networktts

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import io.legado.app.data.entities.HttpTTS
import io.legado.app.nettts.NetworkTtsLog
import io.legado.app.nettts.NetworkTtsSynthesizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

internal data class RuleCheckResult(
    val fingerprint: String,
    val success: Boolean,
    val detail: String,
    val checkedAt: Long,
) {
    fun matches(rule: HttpTTS) = fingerprint == fingerprint(rule)

    companion object {
        fun fingerprint(rule: HttpTTS): String = MessageDigest.getInstance("SHA-256")
            .digest(rule.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** 检测结果是本机管理信息，不写入规则 JSON；不会播放声音。 */
internal class NetworkTtsRuleChecker(context: Context) {
    private val preferences = context.getSharedPreferences("network_tts_checks", Context.MODE_PRIVATE)
    private val synthesizer = NetworkTtsSynthesizer(
        NetworkTtsRuntimeBridge.audioCache(context), maxRetries = 0, requestTimeoutMs = 20_000L,
    )

    fun load(): Map<Long, RuleCheckResult> = preferences.all.mapNotNull { (key, value) ->
        val id = key.toLongOrNull() ?: return@mapNotNull null
        val json = JSONObject(value as? String ?: return@mapNotNull null)
        id to RuleCheckResult(json.getString("fingerprint"), json.getBoolean("success"),
            json.getString("detail"), json.getLong("checkedAt"))
    }.toMap()

    fun save(id: Long, result: RuleCheckResult) {
        val json = JSONObject().put("fingerprint", result.fingerprint).put("success", result.success)
            .put("detail", result.detail).put("checkedAt", result.checkedAt)
        preferences.edit().putString(id.toString(), json.toString()).apply()
    }

    suspend fun check(rule: HttpTTS): RuleCheckResult {
        var success = false
        val detail = try {
            withTimeout(35_000L) {
                synthesizer.synthesize(rule, "你好，这是网络朗读规则试听。",
                    NetworkTtsSynthesizer.DEFAULT_SPEECH_RATE, chapterTitle = "试听", forceRefresh = true).use {
                    withContext(Dispatchers.IO) { decode(it.file) }
                }
            }
            success = true
            "音频合成与解码成功"
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            "检测超时"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NetworkTtsLog.sanitize(e.message ?: "音频检测失败").take(700)
        }
        return RuleCheckResult(RuleCheckResult.fingerprint(rule), success, detail, System.currentTimeMillis())
    }

    /** 实际解码出音频数据后才判定可用，不依赖文件后缀或服务端 Content-Type。 */
    private suspend fun decode(file: File) = withTimeout(8_000L) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("返回内容不包含可播放的音轨")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            // WAV 的 PCM 音轨已经是解码后的数据，不要求设备另有 audio/raw 解码器。
            if (mime == "audio/raw") {
                check(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) > 0 &&
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) > 0 &&
                    extractor.readSampleData(ByteBuffer.allocate(64 * 1024), 0) > 0) { "返回 PCM 音频无有效数据" }
                return@withTimeout
            }
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputEnded = false
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!inputEnded) {
                        val index = codec.dequeueInputBuffer(10_000L)
                        if (index >= 0) {
                            val input = requireNotNull(codec.getInputBuffer(index))
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            inputEnded = size < 0
                            codec.queueInputBuffer(index, 0, size.coerceAtLeast(0),
                                if (inputEnded) 0 else extractor.sampleTime,
                                if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            if (!inputEnded) extractor.advance()
                        }
                    }
                    val output = codec.dequeueOutputBuffer(info, 10_000L)
                    if (output >= 0) {
                        codec.releaseOutputBuffer(output, false)
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) break
                        check(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) { "返回音频无法解码出声音数据" }
                    }
                }
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }
}
