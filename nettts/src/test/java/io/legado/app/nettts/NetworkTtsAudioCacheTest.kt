package io.legado.app.nettts

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

    @Test
    fun `trim protects retained audio until release`() {
        val (cache, _) = newCache(maxBytes = 150)
        runBlocking {
            cache.write("active") { it.write(ByteArray(100)) }
            cache.fileFor("active").setLastModified(System.currentTimeMillis() - 10_000)
            val lease = cache.retain("active")
            val newLease = cache.retain("new")
            cache.write("new") { it.write(ByteArray(100)) }
            assertTrue(cache.exists("active"))
            assertTrue(cache.exists("new"))
            lease.close()
            assertFalse(cache.exists("active"))
            assertTrue(cache.exists("new"))
            newLease.close()
        }
    }

    @Test
    fun `clear from another cache instance protects retained audio`() {
        val (first, dir) = newCache()
        runBlocking { first.write("shared") { it.write(ByteArray(100)) } }
        val lease = first.retain("shared")
        val second = NetworkTtsAudioCache(dir)
        assertEquals(0, second.clear())
        assertTrue(first.exists("shared"))
        lease.close()
    }

    @Test
    fun `write rejects oversized audio and removes temporary file`() {
        val dir = Files.createTempDirectory("nettts-limit").toFile()
        val cache = NetworkTtsAudioCache(dir, maxAudioBytes = 8)
        assertThrows<NetworkTtsAudioCache.SizeLimitException> {
            runBlocking { cache.write("large") { it.write(ByteArray(9)) } }
        }
        assertFalse(cache.exists("large"))
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }
}
