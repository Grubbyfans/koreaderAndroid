package io.legado.app.utils

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.JsonSyntaxException
import com.google.gson.Strictness
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

/**
 * Gson 辅助函数。
 *
 * 来源：Legado 原版 `app/src/main/java/io/legado/app/utils/GsonExtensions.kt`（GPLv3）。
 * 裁剪：去掉书籍/书源规则的自定义反序列化器与日期适配器，仅保留
 * UrlOption、header、登录信息等网络朗读链路需要的解析入口。
 */
val GSON: Gson by lazy {
    GsonBuilder()
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()
}

/** 严格模式：UrlOption 解析优先用它，格式不规范时退回 [GSON]。 */
val GSONStrict: Gson by lazy {
    GSON.newBuilder()
        .setStrictness(Strictness.STRICT)
        .create()
}

inline fun <reified T> genericType(): Type = object : TypeToken<T>() {}.type

inline fun <reified T> Gson.fromJsonObject(json: String?): Result<T> {
    return runCatching {
        if (json == null) throw JsonSyntaxException("解析字符串为空")
        fromJson(json, genericType<T>()) as T
    }
}

inline fun <reified T> Gson.fromJsonArray(json: String?): Result<List<T>> {
    return runCatching {
        if (json == null) throw JsonSyntaxException("解析字符串为空")
        val type = TypeToken.getParameterized(List::class.java, T::class.java).type
        val list = fromJson(json, type) as List<T?>
        if (list.contains(null)) {
            throw JsonParseException("列表存在 null 元素，可能是 JSON 多余的逗号所致")
        }
        @Suppress("UNCHECKED_CAST")
        list as List<T>
    }
}

fun String?.isJson(): Boolean {
    val str = this?.trim() ?: return false
    return (str.startsWith("{") && str.endsWith("}")) || (str.startsWith("[") && str.endsWith("]"))
}

fun String?.isJsonObject(): Boolean {
    val str = this?.trim() ?: return false
    return str.startsWith("{") && str.endsWith("}")
}

fun String?.isJsonArray(): Boolean {
    val str = this?.trim() ?: return false
    return str.startsWith("[") && str.endsWith("]")
}

fun String?.isXml(): Boolean {
    val str = this?.trim() ?: return false
    return str.startsWith("<") && str.endsWith(">")
}
