package io.legado.app.constant

import java.util.regex.Pattern

/**
 * 正则常量子集。
 *
 * 来源：Legado 原版 `app/src/main/java/io/legado/app/constant/AppPattern.kt`（GPLv3）。
 * 仅保留网络朗读链路引用的模式，语义与原版一致。
 */
object AppPattern {

    /** `@js:...` 或 `<js>...</js>` */
    val JS_PATTERN: Pattern =
        Pattern.compile("<js>([\\w\\W]*?)</js>|@js:([\\w\\W]*)", Pattern.CASE_INSENSITIVE)

    /** `{{ ... }}` 内嵌 JS */
    val EXP_PATTERN: Pattern = Pattern.compile("\\{\\{([\\w\\W]*?)\\}\\}")

    /** URL 与逗号后 UrlOption JSON 的分隔 */
    val paramPattern: Pattern = Pattern.compile("\\s*,\\s*(?=\\{)")

    /** data URI 的 base64 载荷 */
    val dataUriRegex = Regex("^data:.*?;base64,(.*)")

    /** URL 中的主机名 */
    val domainRegex = Regex("^https?://([^:/]+)", RegexOption.IGNORE_CASE)

    val xmlContentTypeRegex = "(application|text)/\\w*\\+?xml.*".toRegex()

    val semicolonRegex = ";".toRegex()

    val equalsRegex = "=".toRegex()

    val pagePattern: Pattern = Pattern.compile("<(.*?)>")
}
