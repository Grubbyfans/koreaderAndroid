package io.legado.app.constant

/**
 * 最小常量集。
 *
 * 来源：Legado 原版 `app/src/main/java/io/legado/app/constant/AppConst.kt`（GPLv3）。
 * 仅保留网络朗读链路需要的 UA 常量；其余（版本号、路径、正则等）无关内容未引入。
 */
object AppConst {

    const val UA_NAME = "User-Agent"

    /**
     * 与原版一致的默认 UA。
     *
     * 原版从 AppConfig.userAgent 读取（可在设置里改）；静读侧暂不暴露该设置，
     * 需要时由 [io.legado.app.nettts.NetworkTtsConfig.userAgent] 覆盖。
     */
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"
}
