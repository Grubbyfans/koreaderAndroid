package com.yuezhou.reader.reader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 下载/导入过程产生的临时文件残留清理。
 *
 * 背景：下载（files/epub/download-*.part）、字体导入（reader_fonts/font-*.part）、
 * 以及旧版可能遗留的 *.part 文件，在用户主动取消或进程被强制终止时会残留下来，
 * 因为取消/杀进程时 `finally` 里的 `tmp.delete()` 可能不执行或删除失败（文件流仍占用）。
 *
 * 清理策略：应用启动时（冷启动）与每次下载开始前各扫一遍私有目录，删除所有 `.part` 临时文件。
 * 相比「每日定时任务」，启动时清理已覆盖绝大多数残留场景，且无需引入 WorkManager 依赖。
 *
 * 仅删除已知临时文件命名模式（*.part），绝不触碰 *.epub / *.json 等正式文件，
 * 目录不存在时静默跳过，清理失败不抛出异常（不影响正常流程）。
 */
object TempFileCleanup {

    /** 需要扫描的目录（相对 filesDir）。 */
    private val SCAN_DIRS = listOf(
        "epub",           // 下载正文临时文件 download-*.part
        "reader_fonts",   // 字体导入临时文件 font-*.part
        "library/covers", // 封面可能遗留的临时文件（防御性）
    )

    /**
     * 清理所有已知目录下的 `.part` 临时文件，返回清理数量。
     * 挂到 IO 线程执行，避免阻塞主线程。
     */
    suspend fun clean(context: Context): Int = withContext(Dispatchers.IO) {
        var removed = 0
        for (dir in SCAN_DIRS) {
            val f = File(context.filesDir, dir)
            if (!f.isDirectory) continue
            f.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".part")) {
                    if (file.delete()) removed++
                }
            }
        }
        removed
    }
}
