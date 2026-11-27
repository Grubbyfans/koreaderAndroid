package io.legado.app.nettts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkTtsRuleCodecTest {

    private val sample = """
        [
          {
            "id": 1652146476375,
            "name": "思必驰-俞师",
            "url": "https://dds.dui.ai/runtime/v1/synthesize?voiceId=yukaimp&text={{java.encodeURI(java.encodeURI(speakText))}}&audioType=wav",
            "contentType": "",
            "concurrentRate": "0",
            "enabledCookieJar": false,
            "header": "",
            "loginUrl": "",
            "loginUi": "",
            "loginCheckJs": "",
            "lastUpdateTime": 1695006617414,
            "vendorExtension": {"keep": true},
            "loginInfo": "{\"token\":\"secret\"}",
            "cookie": "sid=abc"
          }
        ]
    """.trimIndent()

    @Test
    fun `import single array keeps unknown fields`() {
        val result = NetworkTtsRuleCodec.parse(sample)
        assertEquals(1, result.rules.size)
        assertTrue(result.errors.isEmpty())
        val rule = result.rules.first()
        assertEquals("思必驰-俞师", rule.name)
        assertNotNull(rule.rawJson)
    }

    @Test
    fun `export keeps unknown fields and overrides known fields`() {
        val rule = NetworkTtsRuleCodec.parse(sample).rules.first()
            .copy(name = "改名后", pauseDuration = 300)
        val exported = NetworkTtsRuleCodec.exportRule(rule)
        assertEquals("改名后", exported["name"]?.jsonPrimitive?.content)
        assertEquals("300", exported["pauseDuration"]?.jsonPrimitive?.content)
        assertTrue(exported.containsKey("vendorExtension"))
        assertEquals("true", exported["vendorExtension"]?.jsonObject?.get("keep")?.jsonPrimitive?.content)
    }

    @Test
    fun `export strips credential fields`() {
        val rule = NetworkTtsRuleCodec.parse(sample).rules.first()
        val exported = NetworkTtsRuleCodec.exportRule(rule)
        assertFalse(exported.containsKey("loginInfo"))
        assertFalse(exported.containsKey("cookie"))
    }

    @Test
    fun `invalid json reports error instead of crashing`() {
        val result = NetworkTtsRuleCodec.parse("{ not json }")
        assertTrue(result.rules.isEmpty())
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun `missing required fields reported per item`() {
        val json = """[{"id":1,"name":"缺失 URL"},{"id":2,"name":"ok","url":"https://example.com/{{speakText}}"}]"""
        val result = NetworkTtsRuleCodec.parse(json)
        assertEquals(1, result.rules.size)
        assertEquals(1, result.errors.size)
    }

    @Test
    fun `export round trip is parseable`() {
        val rule = NetworkTtsRuleCodec.parse(sample).rules.first()
        val text = NetworkTtsRuleCodec.exportRules(listOf(rule))
        val reparsed = NetworkTtsRuleCodec.parse(text)
        assertEquals(1, reparsed.rules.size)
        val parsedJson = Json.parseToJsonElement(text)
        assertTrue(parsedJson.toString().isNotBlank())
    }
}
