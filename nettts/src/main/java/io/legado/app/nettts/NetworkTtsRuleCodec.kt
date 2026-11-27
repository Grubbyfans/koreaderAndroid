package io.legado.app.nettts

import io.legado.app.data.entities.HttpTTS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 网络 TTS 规则的导入/导出。
 *
 * 来源：Legado 原版 `ImportHttpTtsViewModel` + `HttpTTS.fromJson*`（GPLv3），
 * 但改为"保留原始 JSON"的实现，以满足既定要求：
 *  - 导入时保存整条原始 JSON（[HttpTTS.rawJson]），未知字段不丢失；
 *  - 导出时以原始 JSON 为基底，用当前编辑后的已知字段覆盖；
 *  - 默认剔除独立登录凭证类字段（登录信息/登录头/cookie 等），凭证永不写进导出的规则文本。
 */
object NetworkTtsRuleCodec {

    /** 导出时禁止携带的凭证字段（导入的原始 JSON 里若存在也会被剔除）。 */
    private val CREDENTIAL_KEYS = setOf(
        "logininfo", "loginheader", "userinfo", "cookie", "cookies",
        "authorization", "accesstoken", "refreshtoken", "password",
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    data class ParseResult(
        val rules: List<HttpTTS>,
        val errors: List<String>,
    )

    /** 解析单条或数组；数组里单条失败不影响其他条。 */
    fun parse(text: String): ParseResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ParseResult(emptyList(), listOf("内容为空"))
        val element = runCatching { json.parseToJsonElement(trimmed) }.getOrElse {
            return ParseResult(emptyList(), listOf("JSON 解析失败：${it.message}"))
        }
        return when (element) {
            is JsonArray -> {
                val rules = mutableListOf<HttpTTS>()
                val errors = mutableListOf<String>()
                element.forEachIndexed { index, item ->
                    parseOne(item).onSuccess(rules::add).onFailure {
                        errors.add("第 ${index + 1} 条：${it.message}")
                    }
                }
                ParseResult(rules, errors)
            }

            is JsonObject -> parseOne(element).fold(
                onSuccess = { ParseResult(listOf(it), emptyList()) },
                onFailure = { ParseResult(emptyList(), listOf(it.message.orEmpty())) },
            )

            else -> ParseResult(emptyList(), listOf("不是规则对象或数组"))
        }
    }

    private fun parseOne(element: JsonElement): Result<HttpTTS> = runCatching {
        val obj = element.jsonObject
        val name = obj.string("name")?.trim().orEmpty()
        val url = obj.string("url")?.trim().orEmpty()
        require(name.isNotEmpty()) { "缺少 name" }
        require(url.isNotEmpty()) { "缺少 url" }
        HttpTTS(
            id = obj.long("id") ?: System.currentTimeMillis(),
            name = name,
            url = url,
            contentType = obj.string("contentType"),
            pauseDuration = (obj.long("pauseDuration") ?: 0L).coerceIn(0L, 10_000L).toInt(),
            concurrentRate = obj.string("concurrentRate") ?: "0",
            loginUrl = obj.string("loginUrl"),
            loginUi = obj.string("loginUi"),
            header = obj.string("header"),
            jsLib = obj.string("jsLib"),
            enabledCookieJar = obj.bool("enabledCookieJar") ?: false,
            loginCheckJs = obj.string("loginCheckJs"),
            lastUpdateTime = obj.long("lastUpdateTime") ?: System.currentTimeMillis(),
            rawJson = element.toString(),
        )
    }

    /** 导出单条规则：原始 JSON 为底，已知字段覆盖，凭证字段剔除。 */
    fun exportRule(rule: HttpTTS): JsonObject {
        val base = rule.rawJson?.let { raw ->
            runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        } ?: JsonObject(emptyMap())
        return buildJsonObject {
            base.forEach { (key, value) ->
                if (key.lowercase() !in CREDENTIAL_KEYS) put(key, value)
            }
            put("id", rule.id)
            put("name", rule.name)
            put("url", rule.url)
            put("contentType", rule.contentType.orEmpty())
            put("pauseDuration", rule.pauseDuration)
            put("concurrentRate", rule.concurrentRate.orEmpty())
            put("loginUrl", rule.loginUrl.orEmpty())
            put("loginUi", rule.loginUi.orEmpty())
            put("header", rule.header.orEmpty())
            put("jsLib", rule.jsLib.orEmpty())
            put("enabledCookieJar", rule.enabledCookieJar == true)
            put("loginCheckJs", rule.loginCheckJs.orEmpty())
            put("lastUpdateTime", rule.lastUpdateTime)
        }
    }

    fun exportRules(rules: List<HttpTTS>): String {
        val array = buildJsonArray { rules.forEach { add(exportRule(it)) } }
        return json.encodeToString(JsonElement.serializer(), array)
    }

    fun exportRuleText(rule: HttpTTS): String =
        json.encodeToString(JsonElement.serializer(), exportRule(rule))

    private fun JsonObject.string(key: String): String? = this[key].asStringOrNull()

    private fun JsonObject.long(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.contentOrNull?.trim()?.toLongOrNull()
    }

    private fun JsonObject.bool(key: String): Boolean? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.booleanOrNull ?: when (primitive.contentOrNull?.trim()?.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }

    private fun JsonElement?.asStringOrNull(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.contentOrNull
    }
}
