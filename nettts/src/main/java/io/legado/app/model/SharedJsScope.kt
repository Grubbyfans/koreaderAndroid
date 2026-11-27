package io.legado.app.model

import com.script.ScriptBindings
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.CacheManager
import io.legado.app.help.http.CookieStore
import org.htmlunit.corejs.javascript.TopLevel
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * 规则 `jsLib` 共享作用域。
 *
 * 来源：Legado 原版 `app/src/main/java/io/legado/app/model/SharedJsScope.kt`（GPLv3）。
 * 裁剪：原版按 source + coroutineContext 维护多个作用域（含 CryptoJS 预置）；
 * 静读网络朗读只需"每个规则一份 jsLib 作用域"，故简化为按规则键 + jsLib 内容哈希缓存。
 *
 * 作用域内容只读（规则 jsLib 是静态函数库），并发求值的安全由调用方串行化保证。
 */
object SharedJsScope {

    private val libScopes = ConcurrentHashMap<String, TopLevel>()

    private fun cacheKey(source: BaseSource): String {
        val jsLib = source.jsLib.orEmpty()
        return "${source.getKey()}:${jsLib.hashCode()}"
    }

    /**
     * 获取（或初始化）规则的 jsLib 共享作用域。
     *
     * [coroutineContext] 传入后，首次初始化执行 jsLib 时会被 Rhino 指令观察器纳入取消检查，
     * 死循环 jsLib 可随调用方超时/取消中断；缓存命中后不再重复求值，无需上下文。
     */
    fun scopeFor(source: BaseSource, coroutineContext: CoroutineContext? = null): TopLevel? {
        if (source.jsLib.isNullOrBlank()) return null
        val key = cacheKey(source)
        libScopes[key]?.let { return it }
        val bindings = buildScriptBindings { bindings ->
            bindings["java"] = source
            bindings["source"] = source
            bindings["sourceApi"] = source
            bindings["baseUrl"] = source.getKey()
            bindings["cookie"] = CookieStore
            bindings["cache"] = CacheManager
        }
        RhinoScriptEngine.eval(source.jsLib!!, bindings, coroutineContext)
        return libScopes.putIfAbsent(key, bindings) ?: bindings
    }

    fun globalStateKey(key: String) = ScriptBindings.getSharedGlobalStateHandle(key)

    fun remove(jsLib: String) {
        val suffix = ":${jsLib.hashCode()}"
        libScopes.keys.filter { it.endsWith(suffix) }.forEach(libScopes::remove)
    }

    fun clear() = libScopes.clear()
}
