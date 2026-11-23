package com.yuezhou.reader.speech

import android.content.Context
import android.content.Intent
import com.yuezhou.reader.core.speech.SpeechChapterSource
import com.yuezhou.reader.core.speech.SpeechEngineType
import com.yuezhou.reader.core.speech.SpeechPlan

/**
 * 朗读引擎运行期配置：引擎、语速与音色。
 *
 * 由 app 层在启动朗读前注入，服务在每次开始朗读时应用（见 SpeechService.applyConfig）。
 * 与 [SpeechPlan] 解耦——配置是用户偏好，计划是章节文本，二者生命周期不同。
 *
 * @param rate 语速（系统 TTS 约 0.5..2.0；Kokoro 为合成层 speed）；<=0 表示沿用引擎默认。
 * @param voiceName 系统 TTS 音色名；空白表示沿用引擎默认音色。
 * @param engine 朗读引擎；Kokoro 未装配时服务回退系统 TTS。
 * @param kokoroModelId Kokoro 模型 ID；空白表示用提供者的默认模型。
 * @param kokoroSpeakerId Kokoro 中文音色 ID。
 */
data class SpeechConfig(
    val rate: Float = 1.0f,
    val voiceName: String = "",
    val engine: SpeechEngineType = SpeechEngineType.SYSTEM_TTS,
    val kokoroModelId: String = "",
    val kokoroSpeakerId: Int = 3,
)

/**
 * 朗读计划进程内承载 + 服务启动入口。
 *
 * 首版以进程内内存承载当前计划（服务与 Activity 同进程），避免引入 IPC 序列化。
 * 服务在播放期间持有章节来源，按需生成后续章节。
 *
 * 服务被系统终止后，进程内计划随之丢失；恢复位置依赖持久化层（后续实现）。
 */
object SpeechPlanStore {

    internal data class Request(
        val plan: SpeechPlan,
        val chapters: SpeechChapterSource,
        val startSegment: Int = 0,
    )

    @Volatile
    private var current: Request? = null

    @Volatile
    private var currentConfig: SpeechConfig = SpeechConfig()

    internal fun peek(): Request? = current

    @Synchronized
    internal fun clear(expected: Request? = null) {
        val previous = current ?: return
        if (expected != null && previous !== expected) return
        current = null
        previous.chapters.close()
    }

    /** 设置朗读配置（app 层在开始朗读前注入）。 */
    fun putConfig(config: SpeechConfig) {
        currentConfig = config
    }

    /** 读取朗读配置（服务消费）。 */
    fun peekConfig(): SpeechConfig = currentConfig

    /** Starts a session whose chapter source is owned by the service until playback stops. */
    @Synchronized
    fun startAndPlay(
        context: Context,
        plan: SpeechPlan,
        chapters: SpeechChapterSource,
        startSegment: Int = 0,
    ) {
        clear()
        val request = Request(plan, chapters, startSegment)
        current = request
        try {
            context.startForegroundService(Intent(context, SpeechService::class.java).setAction(SpeechCommands.ACTION_PLAY))
        } catch (e: Exception) {
            clear(request)
            throw e
        }
    }

    /** 朗读中实时调速；服务不在运行时安静忽略。 */
    fun setRate(context: Context, rate: Float) {
        if (rate <= 0f) return
        currentConfig = currentConfig.copy(rate = rate)
        runCatching {
            context.startService(
                Intent(context, SpeechService::class.java)
                    .setAction(SpeechCommands.ACTION_SET_RATE)
                    .putExtra(SpeechCommands.EXTRA_RATE, rate)
            )
        }
    }

    /** 停止朗读并退出前台。 */
    fun stop(context: Context) {
        clear()
        SpeechProgressTracker.clear()
        context.stopService(Intent(context, SpeechService::class.java))
    }
}
