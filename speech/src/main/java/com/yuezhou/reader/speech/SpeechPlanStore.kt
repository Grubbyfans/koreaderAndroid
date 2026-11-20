package com.yuezhou.reader.speech

import android.content.Context
import android.content.Intent
import com.yuezhou.reader.core.speech.SpeechPlan

/**
 * 朗读引擎运行期配置：语速 + 音色。
 *
 * 由 app 层在启动朗读前注入，服务在每次开始朗读时应用（见 SpeechService.applyConfig）。
 * 与 [SpeechPlan] 解耦——配置是用户偏好，计划是章节文本，二者生命周期不同。
 *
 * @param rate 语速（TextToSpeech 范围约 0.5..2.0，1.0 为正常）；<=0 表示沿用引擎默认。
 * @param voiceName 音色名（TextToSpeech.Voice.name）；空白表示沿用引擎默认音色。
 */
data class SpeechConfig(
    val rate: Float = 1.0f,
    val voiceName: String = "",
)

/**
 * 朗读计划进程内承载 + 服务启动入口。
 *
 * 首版以进程内内存承载当前计划（服务与 Activity 同进程），避免引入 IPC 序列化。
 * 持久化留待 Room（技术方案 §8.2 SpeechPlan 表）——计划按章节持久化、跨章节预生成。
 *
 * 服务被系统终止后，进程内计划随之丢失；恢复位置依赖持久化层（后续实现）。
 */
object SpeechPlanStore {

    @Volatile
    private var current: SpeechPlan? = null

    @Volatile
    private var currentConfig: SpeechConfig = SpeechConfig()

    /** 设置当前计划（app 层在开始朗读前注入）。 */
    fun put(plan: SpeechPlan) {
        current = plan
    }

    /** 读取当前计划（服务消费）。 */
    fun peek(): SpeechPlan? = current

    /** 清空计划（同名覆盖/切书时调用）。 */
    fun clear() {
        current = null
    }

    /** 设置朗读配置（app 层在开始朗读前注入）。 */
    fun putConfig(config: SpeechConfig) {
        currentConfig = config
    }

    /** 读取朗读配置（服务消费）。 */
    fun peekConfig(): SpeechConfig = currentConfig

    /** 启动服务并播放当前计划。 */
    fun startAndPlay(context: Context, plan: SpeechPlan) {
        put(plan)
        context.startForegroundService(Intent(context, SpeechService::class.java).setAction(SpeechCommands.ACTION_PLAY))
    }

    /** 仅加载计划（不立即播放，供「从当前位置开始朗读」前的准备）。 */
    fun load(context: Context, plan: SpeechPlan) {
        put(plan)
    }

    /** 发送控制命令到服务。 */
    fun command(context: Context, action: String) {
        context.startForegroundService(Intent(context, SpeechService::class.java).setAction(action))
    }

    /** 停止朗读并退出前台。 */
    fun stop(context: Context) {
        clear()
        SpeechProgressTracker.clear()
        context.stopService(Intent(context, SpeechService::class.java))
    }
}
