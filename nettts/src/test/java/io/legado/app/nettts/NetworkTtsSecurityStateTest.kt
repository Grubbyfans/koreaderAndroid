package io.legado.app.nettts

import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.CacheManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files

class NetworkTtsSecurityStateTest {

    private val store = MemorySecretStore()

    @BeforeEach
    fun setUp() {
        CacheManager.clearMemory()
        NetworkTtsRuntime.secretStore = store
    }

    @AfterEach
    fun tearDown() {
        NetworkTtsRuntime.secretStore = null
        CacheManager.clearMemory()
    }

    @Test
    fun `dynamic login ui is evaluated and defaults are saved`() = runBlocking {
        val rule = HttpTTS(
            id = 9101,
            name = "dynamic",
            url = "https://example.com",
            loginUi = "@js:JSON.stringify([{name:'voice',type:'select',values:['a','b'],default:'a'}])",
        )
        val rows = withTimeout(2_000) { rule.getLoginRows(currentCoroutineContext()) }
        assertEquals(1, rows.size)
        assertEquals(listOf("a", "b"), rows.single().choices())
        val values = rule.getLoginInfoMap(rows)
        assertEquals("a", values["voice"])
        assertTrue(store.values.containsKey("userInfo_httpTts:9101"))
    }

    @Test
    fun `login script executes with active timeout context`() = runBlocking {
        val rule = HttpTTS(
            id = 9102,
            name = "login",
            url = "https://example.com",
            loginUrl = "@js:function login(){source.putLoginInfo(JSON.stringify({voice:'b'}));source.putVariable('b');}",
        )
        withTimeout(2_000) { rule.loginWithContext(currentCoroutineContext()) }
        assertTrue(rule.getLoginInfo().orEmpty().contains("b"))
        assertEquals("b", rule.getVariable())
    }

    @Test
    fun `login state and variables survive memory cache clear`() {
        val rule = HttpTTS(id = 9103, name = "persist", url = "https://example.com")
        rule.putLoginInfo("""{"voice":"a","token":"secret"}""")
        rule.putLoginHeader("""{"Authorization":"Bearer secret"}""")
        rule.putVariable("a")
        rule.put("custom", "stored")
        CacheManager.clearMemory()

        val restored = rule.copy()
        assertTrue(restored.getLoginInfo().orEmpty().contains("voice"))
        assertTrue(restored.getLoginHeader().orEmpty().contains("Authorization"))
        assertEquals("a", restored.getVariable())
        assertEquals("stored", restored.get("custom"))
    }

    @Test
    fun `cache identity changes with voice but not short lived token`() {
        val dir = Files.createTempDirectory("nettts-rule-hash").toFile()
        val synthesizer = NetworkTtsSynthesizer(NetworkTtsAudioCache(dir))
        val rule = HttpTTS(id = 9104, name = "hash", url = "https://example.com")
        rule.putLoginInfo("""{"voice":"a","token":"old"}""")
        val original = synthesizer.ruleHash(rule)
        rule.putLoginInfo("""{"voice":"a","token":"new"}""")
        assertEquals(original, synthesizer.ruleHash(rule))
        rule.putLoginInfo("""{"voice":"b","token":"new"}""")
        assertNotEquals(original, synthesizer.ruleHash(rule))
    }

    @Test
    fun `cache manager enforces expiration and lru capacity`() {
        CacheManager.put("expires", "value", saveTime = 1)
        Thread.sleep(1_100)
        assertNull(CacheManager.get("expires"))
        repeat(300) { CacheManager.put("key$it", "$it") }
        assertNull(CacheManager.get("key0"))
        assertEquals("299", CacheManager.get("key299"))
    }

    @Test
    fun `log sanitizer masks quoted json credentials`() {
        val sanitized = NetworkTtsLog.sanitize("""{"token":"json-secret","authorization":"Bearer header-secret"}""")
        assertFalse(sanitized.contains("json-secret"))
        assertFalse(sanitized.contains("header-secret"))
        assertTrue(sanitized.contains("***"))
    }

    private class MemorySecretStore : NetworkTtsSecretStore {
        val values = mutableMapOf<String, String>()
        override fun put(key: String, plainText: String) {
            values[key] = plainText
        }

        override fun get(key: String): String? = values[key]
        override fun remove(key: String) {
            values.remove(key)
        }
    }
}
