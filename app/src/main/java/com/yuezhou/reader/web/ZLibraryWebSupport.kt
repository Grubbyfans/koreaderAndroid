package com.yuezhou.reader.web

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri

/**
 * 内置网页入口的能力判定（阶段 C）。
 *
 * API 28 起支持按进程设置 WebView 数据目录；更低版本多进程无法安全隔离 Cookie/存储，
 * 首版关闭内置网页入口，只提供外部浏览器访问，避免共享数据目录。
 */
object ZLibraryWebSupport {

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** 仅允许把 HTTPS 地址交给系统浏览器，避免把认证信息带向异常 scheme。 */
    fun externalBrowserIntent(rawUrl: String): Intent? {
        val decision = WebNavigationPolicy.classify(rawUrl, emptySet())
        if (decision == WebNavigationPolicy.Decision.BLOCKED) return null
        return Intent(Intent.ACTION_VIEW, rawUrl.trim().toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
    }

    fun openExternally(context: Context, rawUrl: String): Boolean {
        val intent = externalBrowserIntent(rawUrl) ?: return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
