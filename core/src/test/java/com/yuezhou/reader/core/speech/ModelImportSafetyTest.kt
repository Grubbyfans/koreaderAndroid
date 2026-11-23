package com.yuezhou.reader.core.speech

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ModelImportSafetyTest {
    @Test
    fun `单文件上限对多个小块累计生效`() {
        val budget = ModelArchiveBudget(maxFileBytes = 10, maxTotalBytes = 30)
        budget.beginEntry()
        budget.addBytes(6)
        assertThrows(IOException::class.java) { budget.addBytes(5) }
    }
    @Test
    fun `新条目重置单文件计数但保留总量计数`() {
        val budget = ModelArchiveBudget(maxFileBytes = 10, maxTotalBytes = 15)
        budget.beginEntry(); budget.addBytes(8)
        budget.beginEntry(); budget.addBytes(7)
        assertThrows(IOException::class.java) { budget.addBytes(1) }
    }
    @Test
    fun `目录和忽略条目也纳入条目上限`() {
        val budget = ModelArchiveBudget(maxEntries = 2)
        budget.beginEntry(); budget.beginEntry()
        assertThrows(IOException::class.java) { budget.beginEntry() }
    }
    @Test
    fun `拒绝逃逸路径绝对路径跨平台路径与超长路径`() {
        val root = Files.createTempDirectory("model-path-").toFile()
        try {
            for (name in listOf("../model.onnx", "a/../model.onnx", "/model.onnx", "C:/model.onnx", "a\\model.onnx", "a".repeat(256))) {
                assertThrows(IOException::class.java, { ModelImportSafety.resolve(root, name) }, name)
            }
            assertEquals(File(root, "dict/jieba.dict.utf8"), ModelImportSafety.resolve(root, "dict/jieba.dict.utf8"))
        } finally { root.deleteRecursively() }
    }
    @Test
    fun `安装失败恢复原模型目录`() {
        val root = Files.createTempDirectory("model-rollback-").toFile()
        try {
            val target = File(root, "fp32").apply { mkdirs() }
            File(target, "model.onnx").writeText("original")
            assertThrows(IOException::class.java) { ModelImportSafety.commit(File(root, "missing-stage"), target) }
            assertEquals("original", File(target, "model.onnx").readText())
        } finally { root.deleteRecursively() }
    }
    @Test
    fun `新模型和清单一起替换且无备份残留`() {
        val root = Files.createTempDirectory("model-commit-").toFile()
        try {
            val target = File(root, "fp32").apply { mkdirs() }
            File(target, "model.onnx").writeText("original")
            val stage = File(root, "stage").apply { mkdirs() }
            File(stage, "model.onnx").writeText("new")
            File(stage, "manifest.json").writeText("validated")
            ModelImportSafety.commit(stage, target)
            assertEquals("new", File(target, "model.onnx").readText())
            assertEquals("validated", File(target, "manifest.json").readText())
            assertEquals(listOf("fp32"), root.listFiles()!!.map { it.name })
        } finally { root.deleteRecursively() }
    }
    @Test
    fun `导入和删除不能拿到仍在朗读的租约`() = kotlinx.coroutines.test.runTest {
        val slot = KokoroModelSlot()
        val playing = slot.acquire()
        assertNull(slot.tryAcquire())
        playing.release()
        val importing = slot.tryAcquire()
        assertNotNull(importing)
        assertNull(slot.tryAcquire())
        importing!!.release()
    }
}
