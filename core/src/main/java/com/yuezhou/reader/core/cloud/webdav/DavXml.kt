package com.yuezhou.reader.core.cloud.webdav

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * 轻量 WebDAV PROPFIND（multistatus）响应解析器。
 *
 * 只解析本插件需要的字段：`response` 的 `href`、`propstat` 下的 `status`、
 * `prop` 下的 `resourcetype`（是否含 `collection`）、`getcontentlength`、
 * `getetag`、`getlastmodified`。命名空间统一归一到 `DAV:`。
 *
 * 对齐 BaiduPanSync `modules/dav_xml.lua` 的解析语义。
 */
internal object DavXml {

    data class Response(
        val href: String,
        val status: String?,
        val isCollection: Boolean?,
        val size: Long?,
        val etag: String?,
        val modified: String?,
    )

    private const val NS_DAV = "DAV:"

    data class Multistatus(val responses: List<Response>)

    /** 解析 multistatus 响应体。失败抛 [CloudException](INVALID_INPUT)。 */
    fun parse(xml: String): Multistatus {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        val responses = mutableListOf<Response>()
        var current: MutableResponse? = null

        fun tag(): String = parser.name ?: ""

        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            when (event) {
                XmlPullParser.START_TAG -> {
                    val ns = parser.namespace ?: ""
                    val name = tag()
                    if (ns == NS_DAV && name == "response") {
                        current = MutableResponse()
                    } else if (current != null) {
                        when {
                            ns == NS_DAV && name == "href" -> current.href = readText(parser)
                            ns == NS_DAV && name == "status" -> current.status = readText(parser)
                            ns == NS_DAV && name == "collection" -> current.collectionSeen = true
                            ns == NS_DAV && name == "getcontentlength" ->
                                current.size = readText(parser).toLongOrNull()
                            ns == NS_DAV && name == "getetag" -> current.etag = readText(parser)
                            ns == NS_DAV && name == "getlastmodified" -> current.modified = readText(parser)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val ns = parser.namespace ?: ""
                    val name = tag()
                    if (ns == NS_DAV && name == "response") {
                        current?.let { r ->
                            if (r.href != null) responses.add(r.toResponse())
                        }
                        current = null
                    }
                }
            }
        }
        return Multistatus(responses)
    }

    private fun readText(parser: XmlPullParser): String {
        // 读取当前 START_TAG 之后的文本节点，并推进到对应的 END_TAG。
        val sb = StringBuilder()
        var d = 1
        while (d > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT -> sb.append(parser.text)
                XmlPullParser.START_TAG -> d++
                XmlPullParser.END_TAG -> d--
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return sb.toString()
    }

    private class MutableResponse {
        var href: String? = null
        var status: String? = null
        var isCollection: Boolean? = null
        var size: Long? = null
        var etag: String? = null
        var modified: String? = null
        var collectionSeen = false

        fun toResponse(): Response =
            Response(href!!, status, collectionSeen, size, etag, modified)
    }
}
