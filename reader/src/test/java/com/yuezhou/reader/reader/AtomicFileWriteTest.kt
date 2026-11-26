package com.yuezhou.reader.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AtomicFileWriteTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `读者只能看到完整的旧内容或新内容`() {
        val target = File(directory, "library.json")
        val old = "旧书".repeat(20_000)
        val updated = "新书".repeat(20_000)
        target.writeAtomically(old)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val writer = executor.submit {
                repeat(20) { target.writeAtomically(if (it % 2 == 0) old else updated) }
            }
            do {
                val contents = target.readText()
                assertTrue(contents == old || contents == updated)
            } while (!writer.isDone)
            writer.get(5, TimeUnit.SECONDS)
            assertEquals(updated, target.readText())
            assertEquals(listOf("library.json"), directory.list()?.toList())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `替换失败会保留原目标并清理暂存文件`() {
        val target = File(directory, "library.json").apply { mkdir() }
        val existing = File(target, "keep").apply { writeText("保留") }
        assertThrows(IOException::class.java) { target.writeAtomically("新内容") }
        assertEquals("保留", existing.readText())
        assertEquals(listOf("library.json"), directory.list()?.toList())
    }
}
