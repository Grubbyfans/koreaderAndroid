package io.legado.app.nettts

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class NetworkTtsAudioCacheTest {

    private fun newCache(maxBytes: Long = 1024 * 1024): Pair<NetworkTtsAudioCache, File> {
        val dir = Files.createTempDirectory("nettts-cache").toFile()
        return NetworkTtsAudioCache(dir, maxBytes) to dir
    }

    @Test
    fun `key is stable and changes with inputs`() {
        val a = NetworkTtsAudioCache.buildKey("第一章", "rule1", 5, "文本")
        val b = NetworkTtsAudioCache.buildKey("第一章", "rule1", 5, "文本")
        val c = NetworkTtsAudioCache.buildKey("第一章", "rule1", 6, "文本")
        val d = NetworkTtsAudioCache.buildKey("第一章", "rule2", 5, "文本")
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertNotEquals(a, d)
    }

    @Test
    fun `write rejects empty payload and leaves no tmp file`() {
        val (cache, dir) = newCache()
        runCatching { runBlocking { cache.write("k1") { } } }
        assertFalse(cache.exists("k1"))
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `write publishes atomically`() {
        val (cache, dir) = newCache()
        runBlocking { cache.write("k2") { it.write(ByteArray(128) { 1 }) } }
        assertTrue(cache.exists("k2"))
        assertEquals(128L, cache.fileFor("k2").length())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `trim keeps total under limit`() {
        val (cache, dir) = newCache(maxBytes = 300)
        runBlocking {
            repeat(5) { index ->
                cache.write("k$index") { it.write(ByteArray(100)) }
                Thread.sleep(5)
            }
        }
        val total = dir.listFiles().orEmpty().sumOf { it.length() }
        assertTrue(total <= 300, "实际 $total")
    }

    @Test
    fun `clear removes all files`() {
        val (cache, dir) = newCache()
        runBlocking {
            repeat(3) { cache.write("c$it") { output -> output.write(ByteArray(10)) } }
        }
        assertEquals(3, cache.clear())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
}
