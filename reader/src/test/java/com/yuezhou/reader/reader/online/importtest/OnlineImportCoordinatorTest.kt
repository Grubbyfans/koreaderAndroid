package com.yuezhou.reader.reader.online.importtest

import com.yuezhou.reader.reader.online.import.OnlineImportCoordinator
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** 回归测试：新书正文必须落到 files/epub，否则 registerBook 会报「正文不存在」。 */
class OnlineImportCoordinatorTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `新书导入会把正文落到书库目录`() {
        val staging = File(dir, "staging.tmp").apply { writeBytes("new-book".toByteArray()) }
        val target = File(dir, "book.epub")

        val placement = OnlineImportCoordinator.placeBody(staging, target, replacing = false)

        assertTrue(placement.newlyWritten)
        assertFalse(placement.replaced)
        assertNull(placement.backup)
        assertTrue(target.isFile)
        assertArrayEquals("new-book".toByteArray(), target.readBytes())
        assertFalse(staging.exists(), "staging 应在落盘后被清理")
    }

    @Test
    fun `同名同内容保留原文件且不产生备份`() {
        val target = File(dir, "book.epub").apply { writeBytes("same".toByteArray()) }
        val staging = File(dir, "staging.tmp").apply { writeBytes("same".toByteArray()) }

        val placement = OnlineImportCoordinator.placeBody(staging, target, replacing = true)

        assertFalse(placement.newlyWritten)
        assertFalse(placement.replaced)
        assertNull(placement.backup)
        assertArrayEquals("same".toByteArray(), target.readBytes())
        assertFalse(staging.exists())
    }

    @Test
    fun `同名不同内容先备份再替换`() {
        val target = File(dir, "book.epub").apply { writeBytes("old".toByteArray()) }
        val staging = File(dir, "staging.tmp").apply { writeBytes("new".toByteArray()) }

        val placement = OnlineImportCoordinator.placeBody(staging, target, replacing = true)

        assertTrue(placement.replaced)
        assertFalse(placement.newlyWritten)
        val backup = placement.backup
        assertNotNull(backup)
        assertEquals("old", backup!!.readText())
        assertEquals("new", target.readText())
    }
}
