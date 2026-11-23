package com.yuezhou.reader.speech

/**
 * 朗读服务的控制意图 action 集合。
 *
 * app 层通过 `Intent(this, SpeechService::class.java).setAction(SpeechCommands.XXX)` 控制服务，
 * 避免绑定式跨进程通信，简化首版集成。
 */
object SpeechCommands {
    /** 加载当前计划并从起始（或上次位置）开始朗读。 */
    const val ACTION_PLAY = "com.yuezhou.reader.speech.action.PLAY"

    /** 暂停。 */
    const val ACTION_PAUSE = "com.yuezhou.reader.speech.action.PAUSE"

    /** 继续（从暂停恢复）。 */
    const val ACTION_RESUME = "com.yuezhou.reader.speech.action.RESUME"

    /** 下一句。 */
    const val ACTION_NEXT = "com.yuezhou.reader.speech.action.NEXT"

    /** 上一句。 */
    const val ACTION_PREVIOUS = "com.yuezhou.reader.speech.action.PREVIOUS"

    /** 停止。 */
    const val ACTION_STOP = "com.yuezhou.reader.speech.action.STOP"

    /** 停止并退出前台（服务自毁）。 */
    const val ACTION_STOP_FOREGROUND = "com.yuezhou.reader.speech.action.STOP_FOREGROUND"

    /** 朗读中实时调速（Kokoro 重合成当前句；系统 TTS 应用到后续句）。 */
    const val ACTION_SET_RATE = "com.yuezhou.reader.speech.action.SET_RATE"

    /** [ACTION_SET_RATE] 携带的语速（float，1.0 为正常）。 */
    const val EXTRA_RATE = "com.yuezhou.reader.speech.extra.RATE"
}
