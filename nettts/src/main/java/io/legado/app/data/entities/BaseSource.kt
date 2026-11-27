package io.legado.app.data.entities

import com.script.ScriptBindings
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppPattern.JS_PATTERN
import io.legado.app.help.CacheManager
import io.legado.app.help.ConcurrentRateLimiter.Companion.updateConcurrentRate
import io.legado.app.help.JsExtensions
import io.legado.app.help.http.CookieStore
import io.legado.app.model.SharedJsScope
import io.legado.app.nettts.NetworkTtsLog
import io.legado.app.nettts.NetworkTtsRuntime
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CancellationException

/**
 * 规则基类：header / 登录 / 变量。
 *
 * 来源：Legado 原版 `app/src/main/java/io/legado/app/data/entities/BaseSource.kt`（GPLv3）。
 * 裁剪说明：
 *  - 登录头、登录信息与自定义变量经 [NetworkTtsRuntime.secretStore] 加密持久化，
 *    同时用 [CacheManager] 做进程内读取缓存。
 */
@Suppress("unused")
interface BaseSource : JsExtensions {

    var concurrentRate: String?
    var loginUrl: String?
    var loginUi: String?
    var header: String?
    var enabledCookieJar: Boolean?
    var jsLib: String?

    override fun getTag(): String

    fun getKey(): String

    override fun getSource(): BaseSource? = this

    fun extractInlineJs(rule: String?): String? {
        val text = rule?.trim().orEmpty()
        if (text.isBlank()) return null
        val matcher = JS_PATTERN.matcher(text)
        if (!matcher.matches()) return null
        return (matcher.group(1) ?: matcher.group(2))?.trim()
    }

    fun getLoginJs(): String? {
        val loginRule = loginUrl?.trim()
        if (loginRule.isNullOrBlank()) return null
        return extractInlineJs(loginRule) ?: loginRule
    }

    fun hasLoginForm(): Boolean {
        val form = loginUi?.trim().orEmpty()
        return form.isNotEmpty() && form.filterNot { it.isWhitespace() } != "[]"
    }

    fun hasLogin(): Boolean = !loginUrl.isNullOrBlank() || hasLoginForm()

    /** JS 内部调用的无参入口，保持与原版规则兼容。 */
    fun login() = loginWithContext(null)

    /** 用户点击登录时传入活跃 Job，使动态 jsLib 与登录脚本都能被超时/取消中断。 */
    fun loginWithContext(coroutineContext: kotlin.coroutines.CoroutineContext?) {
        val loginJs = getLoginJs()
        if (!loginJs.isNullOrBlank()) {
            val js = """$loginJs
                if(typeof login=='function'){
                    login.apply(this);
                } else {
                    throw('Function login not implements!!!')
                }
            """.trimIndent()
            evalJS(js, coroutineContext)
        }
    }

    /** 解析 header 规则（支持 `@js:` 动态生成）。
     *
     * 动态 header 脚本执行失败时**直接抛出**，不静默降级——否则会把脚本错误变成
     * "缺少鉴权头的请求"，既可能打到服务器产生无效请求，也掩盖了规则本身的问题。
     *
     * [coroutineContext] 传入后，Rhino 指令观察器会把 header 脚本（及其首次初始化的
     * jsLib）纳入当前 Job 的取消/超时检查，避免 header/jsLib 死循环时外层 withTimeout
     * 无法中断。
     */
    fun getHeaderMap(
        hasLoginHeader: Boolean = false,
        coroutineContext: kotlin.coroutines.CoroutineContext? = null,
    ) = HashMap<String, String>().apply {
        header?.let {
            try {
                val json = extractInlineJs(it)?.let { js ->
                    normalizeJsResult(evalJS(js, coroutineContext)).orEmpty()
                } ?: it
                GSON.fromJsonObject<Map<String, String>>(json).getOrNull()?.let { map ->
                    putAll(map)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NetworkTtsLog.put("执行请求头规则出错：${e.message}", e)
                throw IllegalStateException("请求头规则执行失败：${e.message}", e)
            }
        }
        if (!containsKey(AppConst.UA_NAME)) {
            put(AppConst.UA_NAME, NetworkTtsRuntime.userAgent)
        }
        if (hasLoginHeader) {
            getLoginHeaderMap()?.let { putAll(it) }
        }
    }

    fun getLoginHeader(): String? {
        CacheManager.get("loginHeader_${getKey()}")?.let { return it }
        return try {
            NetworkTtsRuntime.secretStore?.get("loginHeader_${getKey()}")
        } catch (e: Exception) {
            NetworkTtsLog.put("获取登录头出错", e)
            throw IllegalStateException("登录头读取失败，请重新登录")
        }?.also { CacheManager.put("loginHeader_${getKey()}", it) }
    }

    fun getLoginHeaderMap(): Map<String, String>? {
        val cache = getLoginHeader() ?: return null
        return GSON.fromJsonObject<Map<String, String>>(cache).getOrNull()
    }

    fun putLoginHeader(header: String) {
        val headerMap = GSON.fromJsonObject<Map<String, String>>(header).getOrNull()
        val cookie = headerMap?.get("Cookie") ?: headerMap?.get("cookie")
        cookie?.let { CookieStore.replaceCookie(getKey(), it) }
        val store = NetworkTtsRuntime.secretStore
        if (store == null) {
            NetworkTtsLog.put("未装配凭证存储，登录头不会落盘")
            return
        }
        store.put("loginHeader_${getKey()}", header)
        CacheManager.put("loginHeader_${getKey()}", header)
    }

    fun removeLoginHeader() {
        CacheManager.delete("loginHeader_${getKey()}")
        NetworkTtsRuntime.secretStore?.remove("loginHeader_${getKey()}")
        CookieStore.removeCookie(getKey())
    }

    fun getLoginInfo(): String? {
        return try {
            NetworkTtsRuntime.secretStore?.get("userInfo_${getKey()}")
        } catch (e: Exception) {
            NetworkTtsLog.put("获取登录信息出错", e)
            throw IllegalStateException("登录信息读取失败，请重新登录")
        }
    }

    fun putLoginInfo(info: String): Boolean {
        val store = NetworkTtsRuntime.secretStore
        if (store == null) {
            NetworkTtsLog.put("未装配凭证存储，登录信息不会落盘")
            return false
        }
        return runCatching {
            store.put("userInfo_${getKey()}", info)
            true
        }.onFailure {
            NetworkTtsLog.put("保存登录信息出错", it)
        }.getOrDefault(false)
    }

    fun removeLoginInfo() {
        NetworkTtsRuntime.secretStore?.remove("userInfo_${getKey()}")
    }

    /** 登录表单 schema：v1 直接解析 JSON 数组，v2 先执行 @js:/<js> 脚本再解析。 */
    fun getLoginRows(coroutineContext: kotlin.coroutines.CoroutineContext? = null): List<LoginRow> {
        val rule = loginUi?.trim().takeUnless { it.isNullOrBlank() } ?: return emptyList()
        parseLoginRows(rule)?.let { return it }
        val js = extractInlineJs(rule) ?: return emptyList()
        val generated = normalizeJsResult(evalJS(js, coroutineContext)).orEmpty()
        return parseLoginRows(generated) ?: throw IllegalStateException("登录表单脚本未返回有效 JSON 数组")
    }

    private fun parseLoginRows(json: String): List<LoginRow>? =
        GSON.fromJsonArray<LoginRow>(json).getOrNull()
            ?.filter { it.name.isNotBlank() && it.type != "button" }

    /** 登录表单默认值；无参入口供原版 JS 调用。 */
    fun getLoginInfoMap(): MutableMap<String, String> {
        val saved = getLoginInfo()
        if (!saved.isNullOrBlank()) {
            GSON.fromJsonObject<MutableMap<String, String>>(saved).getOrNull()?.let { return it }
        }
        val rows = loginUi?.trim()?.let { parseLoginRows(it) }.orEmpty()
        return getLoginInfoMap(rows)
    }

    /** UI 已解析 v2 schema 时直接复用，避免在 login() 内再次嵌套执行动态表单脚本。 */
    fun getLoginInfoMap(rows: List<LoginRow>): MutableMap<String, String> {
        val saved = getLoginInfo()
        if (!saved.isNullOrBlank()) {
            GSON.fromJsonObject<MutableMap<String, String>>(saved).getOrNull()?.let { savedMap ->
                val merged = rows.associate { it.name to (it.default.orEmpty()) }.toMutableMap()
                merged.putAll(savedMap)
                return merged
            }
        }
        return rows.associate { it.name to it.default.orEmpty() }
            .toMutableMap()
            .also { if (it.isNotEmpty()) putLoginInfo(GSON.toJson(it)) }
    }

    fun setVariable(variable: String?) {
        putVariable(variable)
    }

    fun putVariable(variable: String?) {
        val key = "sourceVariable_${getKey()}"
        if (variable != null) {
            NetworkTtsRuntime.secretStore?.put(key, variable)
            CacheManager.put(key, variable)
        } else {
            NetworkTtsRuntime.secretStore?.remove(key)
            CacheManager.delete(key)
        }
    }

    fun getVariable(): String {
        val key = "sourceVariable_${getKey()}"
        CacheManager.get(key)?.let { return it }
        return NetworkTtsRuntime.secretStore?.get(key)?.also { CacheManager.put(key, it) } ?: ""
    }

    fun put(key: String, value: String): String {
        val cacheKey = "v_${getKey()}_$key"
        NetworkTtsRuntime.secretStore?.put(cacheKey, value)
        CacheManager.put(cacheKey, value)
        return value
    }

    fun get(key: String): String {
        val cacheKey = "v_${getKey()}_$key"
        CacheManager.get(cacheKey)?.let { return it }
        return NetworkTtsRuntime.secretStore?.get(cacheKey)?.also { CacheManager.put(cacheKey, it) } ?: ""
    }

    fun refreshJSLib() {
        jsLib?.let { SharedJsScope.remove(it) }
    }

    fun putConcurrent(value: String) {
        updateConcurrentRate(getKey(), value)
    }

    /**
     * 执行 JS，绑定与原版一致（java/source/sourceApi/cookie/cache）。
     *
     * [coroutineContext] 传入后会启用指令级取消检查（Rhino instructionObserverThreshold），
     * 用于实现死循环与长脚本的超时中断。
     */
    fun evalJS(
        jsStr: String,
        coroutineContext: kotlin.coroutines.CoroutineContext? = null,
        bindingsConfig: ScriptBindings.() -> Unit = {},
    ): Any? {
        val bindings = buildScriptBindings { bindings ->
            bindings["java"] = this
            bindings["source"] = this
            bindings["sourceApi"] = this
            bindings["baseUrl"] = getKey()
            bindings["cookie"] = CookieStore
            bindings["cache"] = CacheManager
            bindings.apply(bindingsConfig)
        }
        val sharedScope = SharedJsScope.scopeFor(this, coroutineContext)
        val scope = if (sharedScope == null) {
            RhinoScriptEngine.getRuntimeScope(bindings)
        } else {
            bindings.apply { chainTo(sharedScope, SharedJsScope.globalStateKey(getKey())) }
        }
        return RhinoScriptEngine.eval(jsStr, scope, coroutineContext)
    }

    fun normalizeJsResult(result: Any?): String? {
        return when (result) {
            null -> null
            is String -> result
            is Double if result % 1.0 == 0.0 -> String.format("%.0f", result)
            else -> result.toString()
        }
    }

    /** 登录表单字段（原版 RowUi 的最小子集）。 */
    data class LoginRow(
        val name: String = "",
        val type: String = "text",
        val default: String? = null,
        val hint: String? = null,
        val values: List<String>? = null,
        val options: List<String>? = null,
    ) {
        fun choices(): List<String> = values ?: options ?: emptyList()
    }
}
