package io.legado.app.data.entities

/**
 * 在线朗读规则。
 *
 * 来源：Legado 原版 `app/src/main/java/io/legado/app/data/entities/HttpTTS.kt`（GPLv3）。
 * 裁剪：去掉 Room 注解与 json-path 反序列化（导入/导出改由
 * [io.legado.app.nettts.NetworkTtsRuleCodec] 负责，以便保留未知字段）。
 * 新增 [rawJson]：保存导入时的原始 JSON 文本，导出时以当前编辑值覆盖已知字段。
 */
data class HttpTTS(
    var id: Long = System.currentTimeMillis(),
    var name: String = "",
    var url: String = "",
    var contentType: String? = null,
    var pauseDuration: Int = 0,
    override var concurrentRate: String? = "0",
    override var loginUrl: String? = null,
    override var loginUi: String? = null,
    override var header: String? = null,
    override var jsLib: String? = null,
    override var enabledCookieJar: Boolean? = false,
    var loginCheckJs: String? = null,
    var lastUpdateTime: Long = System.currentTimeMillis(),
    /** 导入时的原始 JSON；为 null 表示由静读侧新建。 */
    var rawJson: String? = null,
) : BaseSource {

    override fun getTag(): String = name

    override fun getKey(): String = "httpTts:$id"

    fun equal(source: HttpTTS): Boolean {
        return name == source.name &&
            url == source.url &&
            contentType == source.contentType &&
            pauseDuration == source.pauseDuration &&
            concurrentRate == source.concurrentRate &&
            loginUrl == source.loginUrl &&
            loginUi == source.loginUi &&
            header == source.header &&
            jsLib == source.jsLib &&
            enabledCookieJar == source.enabledCookieJar &&
            loginCheckJs == source.loginCheckJs
    }
}
