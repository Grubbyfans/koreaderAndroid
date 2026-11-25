package com.yuezhou.reader.web

import android.content.Context
import android.content.Intent
import java.net.URLEncoder

/**
 * Z-Library 网页入口（阶段 D）。
 *
 * 只把用户已配置的服务器地址交给内置容器；有关键词时按第一阶段验证过的 `/s/<query>` 形态
 * 打开搜索页，否则打开站点首页。不构造任何隐式搜索或结果抽取。
 */
object ZLibraryWebEntry {

    fun searchUrl(baseUrl: String?, keyword: String?): String? {
        val base = baseUrl?.trim()?.trimEnd('/').orEmpty()
        if (!base.startsWith("https://", ignoreCase = true)) return null
        val query = keyword?.trim().orEmpty()
        if (query.isEmpty()) return base
        return "$base/s/${encodePathSegment(query)}"
    }

    /** 打开内置网页容器；返回 false 表示没有可用服务器地址。 */
    fun launch(context: Context, baseUrl: String?, keyword: String?): Boolean {
        val url = searchUrl(baseUrl, keyword) ?: return false
        context.startActivity(
            Intent(context, ZLibraryWebActivity::class.java)
                .putExtra(ZLibraryWebActivity.EXTRA_INITIAL_URL, url),
        )
        return true
    }

    /** URLEncoder 面向表单，路径段需要把空格还原为 %20。 */
    private fun encodePathSegment(raw: String): String =
        URLEncoder.encode(raw, Charsets.UTF_8.name()).replace("+", "%20")
}
