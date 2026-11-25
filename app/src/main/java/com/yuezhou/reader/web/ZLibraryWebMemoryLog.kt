package com.yuezhou.reader.web

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.util.Log

/**
 * 阶段 F 内存观测：仅调试包输出，记录网页宿主、主进程与朗读进程的 PSS 及系统可用内存。
 *
 * WebView 的沙箱渲染器不属于本应用 UID，应用内无法读取其 PSS；验收时用
 * `adb shell dumpsys meminfo` 补充记录，不在正式包输出任何内存日志。
 */
internal object ZLibraryWebMemoryLog {

    private const val TAG = "ZLibraryWebMem"
    private const val MB = 1024L * 1024L

    fun record(context: Context, stage: String) {
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        runCatching {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return
            val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            @Suppress("DEPRECATION")
            val processes = manager.runningAppProcesses.orEmpty().filter { it.uid == Process.myUid() }
            val pids = processes.map { it.pid }.toIntArray()
            @Suppress("DEPRECATION")
            val infos = if (pids.isEmpty()) emptyArray() else manager.getProcessMemoryInfo(pids)
            val parts = processes.mapIndexed { index, process ->
                val pss = infos.getOrNull(index)?.totalPss?.let { "${it / 1024}MB" } ?: "?"
                "${shortName(context.packageName, process.processName)}=$pss"
            }
            Log.i(
                TAG,
                "stage=$stage avail=${memory.availMem / MB}MB low=${memory.lowMemory} ${parts.joinToString(" ")}",
            )
        }
    }

    private fun shortName(packageName: String, processName: String): String =
        processName.removePrefix(packageName).removePrefix(":").ifEmpty { "main" }
}
