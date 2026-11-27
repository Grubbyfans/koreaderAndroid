package com.yuezhou.reader.networktts

import android.content.Context
import io.legado.app.nettts.NetworkTtsLog
import io.legado.app.nettts.NetworkTtsRuntime
import io.legado.app.nettts.NetworkTtsSynthesizer
import io.legado.app.nettts.NetworkTtsAudioCache
import io.legado.app.nettts.NetworkTtsPlayer
import java.io.File

/**
 * 网络 TTS 运行时装配。
 *
 * nettts 模块刻意不依赖 app/reader：UA、上下文、缓存目录、日志出口都在这里注入。
 * 阶段 A 只装配试听需要的部分（无登录凭证存储、cookie 仅内存），阶段 B 再接入
 * 加密凭证存储与边下边播。
 */
object NetworkTtsRuntimeBridge {

    private var installed = false

    /** 网络朗读缓存根目录（`filesDir/nettts`）。 */
    fun cacheRoot(context: Context): File =
        File(context.filesDir, "nettts").apply { mkdirs() }

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        NetworkTtsRuntime.appContext = appContext
        NetworkTtsLog.sink = { message, throwable ->
            if (throwable != null) {
                android.util.Log.w("NetworkTts", message, throwable)
            } else {
                android.util.Log.i("NetworkTts", message)
            }
        }
    }

    fun audioCache(context: Context): NetworkTtsAudioCache =
        NetworkTtsAudioCache(File(cacheRoot(context), "audio"))

    fun synthesizer(context: Context): NetworkTtsSynthesizer =
        NetworkTtsSynthesizer(audioCache(context))

    fun player(context: Context): NetworkTtsPlayer =
        NetworkTtsPlayer(context, cacheRoot(context))
}
