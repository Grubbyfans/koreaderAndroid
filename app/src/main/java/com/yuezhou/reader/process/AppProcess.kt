package com.yuezhou.reader.process

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File

/**
 * 进程角色判定（阶段 C）。
 *
 * 网页容器运行在独立进程 [WEB_PROCESS_SUFFIX]；Application 只应在主进程装配 Room、同步、TTS
 * 等重依赖。API 28+ 使用 [Application.getProcessName]；minSdk 26 的旧系统回退读取
 * `/proc/self/cmdline`，避免为了取进程名而提前初始化任何框架组件。
 */
object AppProcess {

    const val WEB_PROCESS_SUFFIX = ":zlibraryweb"

    enum class Role { MAIN, WEB, OTHER }

    @Volatile
    private var cachedName: String? = null

    fun currentName(context: Context): String =
        cachedName ?: resolveName(context).also { cachedName = it }

    fun role(context: Context): Role =
        roleOf(context.packageName, currentName(context))

    fun isMainProcess(context: Context): Boolean = role(context) == Role.MAIN

    fun isWebProcess(context: Context): Boolean = role(context) == Role.WEB

    /** 纯函数：按完整进程名判定角色，便于单元测试。 */
    fun roleOf(packageName: String, processName: String?): Role {
        val name = processName?.takeIf { it.isNotBlank() } ?: packageName
        return when (name) {
            packageName -> Role.MAIN
            "$packageName$WEB_PROCESS_SUFFIX" -> Role.WEB
            else -> Role.OTHER
        }
    }

    private fun resolveName(context: Context): String {
        val fromApi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { Application.getProcessName() }.getOrNull()
        } else {
            null
        }
        return fromApi?.takeIf { it.isNotBlank() }
            ?: readProcCmdline()
            ?: context.packageName
    }

    private fun readProcCmdline(): String? = runCatching {
        File("/proc/self/cmdline")
            .readBytes()
            .toString(Charsets.UTF_8)
            .trim('\u0000', ' ', '\n')
            .takeIf { it.isNotEmpty() }
    }.getOrNull()
}
