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
 *  - 默认递归剔除独立登录凭证类字段，并遮盖静态 header 与 URL 中的常见敏感值；
 *  - 脚本可能包含无法静态识别的敏感值，导出界面必须提示用户自行检查。
 */
object NetworkTtsRuleCodec {

    /** 键名统一为小写字母数字后比较，覆盖 access_token / access-token 等变体。 */
    private val CREDENTIAL_KEYS = setOf(
        "logininfo", "loginheader", "userinfo", "cookie", "cookies",
        "authorization", "token", "accesstoken", "refreshtoken", "password",
        "secret", "apikey", "accesskey", "accesskeyid", "appkey", "sign", "signature",
        "session", "sessionid",
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    /**
     * 无 ID 规则的兜底主键生成器：进程内单调递增。
     * 直接用 `System.currentTimeMillis()` 会让同一毫秒解析的多条无 ID 规则得到相同主键，
     * 随后被 upsert 互相覆盖；递增值保证批内与进程内唯一。
     */
    private val fallbackId = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

    data class ParseResult(
        val rules: List<HttpTTS>,
        val errors: List<String>,
        val generatedIds: Set<Long> = emptySet(),
    )

    fun nextGeneratedId(): Long = fallbackId.incrementAndGet()

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
                val generatedIds = mutableSetOf<Long>()
                element.forEachIndexed { index, item ->
                    val generated = (item as? JsonObject)?.long("id") == null
                    parseOne(item).onSuccess { rule ->
                        rules.add(rule)
                        if (generated) generatedIds.add(rule.id)
                    }.onFailure {
                        errors.add("第 ${index + 1} 条：${it.message}")
                    }
                }
                ParseResult(rules, errors, generatedIds)
            }

            is JsonObject -> parseOne(element).fold(
                onSuccess = {
                    val generatedIds = if (element.long("id") == null) setOf(it.id) else emptySet()
                    ParseResult(listOf(it), emptyList(), generatedIds)
                },
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
            id = obj.long("id") ?: fallbackId.incrementAndGet(),
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

    /** 导出单条规则：原始 JSON 为底，已知字段覆盖，递归过滤常见凭证。 */
    fun exportRule(rule: HttpTTS): JsonObject {
        val base = rule.rawJson?.let { raw ->
            runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        } ?: JsonObject(emptyMap())
        return buildJsonObject {
            base.forEach { (key, value) ->
                if (normalizeKey(key) !in CREDENTIAL_KEYS) put(key, sanitizeElement(value))
            }
            put("id", rule.id)
            put("name", rule.name)
            put("url", sanitizeText(rule.url))
            put("contentType", rule.contentType.orEmpty())
            put("pauseDuration", rule.pauseDuration)
            put("concurrentRate", rule.concurrentRate.orEmpty())
            put("loginUrl", sanitizeText(rule.loginUrl.orEmpty()))
            put("loginUi", sanitizeText(rule.loginUi.orEmpty()))
            put("header", sanitizeText(rule.header.orEmpty()))
            put("jsLib", sanitizeText(rule.jsLib.orEmpty()))
            put("enabledCookieJar", rule.enabledCookieJar == true)
            put("loginCheckJs", sanitizeText(rule.loginCheckJs.orEmpty()))
            put("lastUpdateTime", rule.lastUpdateTime)
        }
    }

    fun exportRules(rules: List<HttpTTS>): String {
        val array = buildJsonArray { rules.forEach { add(exportRule(it)) } }
        return json.encodeToString(JsonElement.serializer(), array)
    }

    fun exportRuleText(rule: HttpTTS): String =
        json.encodeToString(JsonElement.serializer(), exportRule(rule))

    private fun sanitizeElement(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            element.forEach { (key, value) ->
                if (normalizeKey(key) !in CREDENTIAL_KEYS) put(key, sanitizeElement(value))
            }
        }

        is JsonArray -> buildJsonArray { element.forEach { add(sanitizeElement(it)) } }
        is JsonPrimitive -> if (element.isString) JsonPrimitive(sanitizeText(element.content)) else element
    }

    private fun sanitizeText(text: String): String {
        val queryMasked = SENSITIVE_QUERY_PATTERN.replace(text) { "${it.groupValues[1]}***" }
        val parsed = runCatching { json.parseToJsonElement(queryMasked) }.getOrNull()
        if (parsed is JsonObject || parsed is JsonArray) return sanitizeElement(parsed).toString()
        return QUOTED_CREDENTIAL_PATTERN.replace(queryMasked) {
            "${it.groupValues[1]}${it.groupValues[2]}***${it.groupValues[2]}"
        }
    }

    private fun normalizeKey(key: String): String = key.lowercase().filter { it.isLetterOrDigit() }

    private val SENSITIVE_QUERY_PATTERN = Regex(
        "(?i)([?&](?:token|access_token|refresh_token|authorization|cookie|password|secret|" +
            "api[_-]?key|access[_-]?key(?:id)?|app[_-]?key|sign|signature|session(?:id)?)=)[^&#]*"
    )

    private val QUOTED_CREDENTIAL_PATTERN = Regex(
        "(?i)([\\\"']?(?:authorization|cookie|token|access_token|refresh_token|password|secret|" +
            "api[_-]?key|access[_-]?key(?:id)?|app[_-]?key|sign|signature|session(?:id)?)" +
            "[\\\"']?\\s*[:=]\\s*)([\\\"'])(.*?)\\2"
    )

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
