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
 *  - 去掉登录 UI v2、发现/书籍类接口等与网络朗读无关的能力；
 *  - 登录信息加密交给 [NetworkTtsRuntime.secretStore]（app 层用 CredentialCrypto 实现），
 *    未装配时不写入任何凭证；
 *  - 登录头与自定义变量仍用 [CacheManager]，键名与原版一致。
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

    /** 执行规则里的 login() 函数。阶段 A 未提供登录 UI，仅保留脚本调用能力。 */
    fun login() {
        val loginJs = getLoginJs()
        if (!loginJs.isNullOrBlank()) {
            val js = """$loginJs
                if(typeof login=='function'){
                    login.apply(this);
                } else {
                    throw('Function login not implements!!!')
                }
            """.trimIndent()
            evalJS(js)
        }
    }

    /** 解析 header 规则（支持 `@js:` 动态生成）。
     *
     * 动态 header 脚本执行失败时**直接抛出**，不静默降级——否则会把脚本错误变成
     * "缺少鉴权头的请求"，既可能打到服务器产生无效请求，也掩盖了规则本身的问题。
     */
    fun getHeaderMap(hasLoginHeader: Boolean = false) = HashMap<String, String>().apply {
        header?.let {
            try {
                val json = extractInlineJs(it)?.let { js ->
                    normalizeJsResult(evalJS(js)).orEmpty()
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

    fun getLoginHeader(): String? = CacheManager.get("loginHeader_${getKey()}")

    fun getLoginHeaderMap(): Map<String, String>? {
        val cache = getLoginHeader() ?: return null
        return GSON.fromJsonObject<Map<String, String>>(cache).getOrNull()
    }

    fun putLoginHeader(header: String) {
        val headerMap = GSON.fromJsonObject<Map<String, String>>(header).getOrNull()
        val cookie = headerMap?.get("Cookie") ?: headerMap?.get("cookie")
        cookie?.let { CookieStore.replaceCookie(getKey(), it) }
        CacheManager.put("loginHeader_${getKey()}", header)
    }

    fun removeLoginHeader() {
        CacheManager.delete("loginHeader_${getKey()}")
        CookieStore.removeCookie(getKey())
    }

    fun getLoginInfo(): String? {
        return runCatching {
            NetworkTtsRuntime.secretStore?.get("userInfo_${getKey()}")
        }.onFailure {
            NetworkTtsLog.put("获取登录信息出错", it)
        }.getOrNull()
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

    /**
     * 登录表单默认值。
     *
     * 原版支持 loginUi v2，这里只处理"JSON 数组"形态（静态表单默认值），
     * 动态 UI 脚本留待阶段 B 与真实规则一起验证。
     */
    fun getLoginInfoMap(): MutableMap<String, String> {
        val json = getLoginInfo()
        if (!json.isNullOrBlank()) {
            GSON.fromJsonObject<MutableMap<String, String>>(json).getOrNull()?.let { return it }
        }
        val loginUiRule = loginUi?.trim().takeUnless { it.isNullOrBlank() } ?: return mutableMapOf()
        return GSON.fromJsonArray<LoginRow>(loginUiRule).getOrNull()
            ?.filter { it.type != "button" }
            ?.associate { it.name to (it.default ?: "") }
            ?.takeIf { it.isNotEmpty() }
            ?.also { putLoginInfo(GSON.toJson(it)) }
            ?.toMutableMap()
            ?: mutableMapOf()
    }

    fun setVariable(variable: String?) {
        putVariable(variable)
    }

    fun putVariable(variable: String?) {
        if (variable != null) {
            CacheManager.put("sourceVariable_${getKey()}", variable)
        } else {
            CacheManager.delete("sourceVariable_${getKey()}")
        }
    }

    fun getVariable(): String = CacheManager.get("sourceVariable_${getKey()}") ?: ""

    fun put(key: String, value: String): String {
        CacheManager.put("v_${getKey()}_$key", value)
        return value
    }

    fun get(key: String): String = CacheManager.get("v_${getKey()}_$key") ?: ""

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
    )
}
