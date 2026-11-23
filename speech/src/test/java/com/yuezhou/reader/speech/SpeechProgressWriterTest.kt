package com.yuezhou.reader.speech

import com.yuezhou.reader.core.speech.SpeechProgressSink
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechProgressWriterTest {

    private class RecordingSink(private val failures: Int = 0) : SpeechProgressSink {
        val saves = mutableListOf<SpeechProgressWriter.Progress>()
        private var calls = 0

        override suspend fun save(
            bookId: String,
            fileGeneration: String,
            chapterIndex: Int,
            segmentIndex: Int,
            charOffset: Int,
        ) {
            calls++
            if (calls <= failures) throw IOException("写入失败 $calls")
            saves += SpeechProgressWriter.Progress(
                bookId, fileGeneration, chapterIndex, segmentIndex, charOffset,
            )
        }
    }

    @Test
    fun `去抖期间只写最后一次位置`() = runTest {
        val sink = RecordingSink()
        val writer = SpeechProgressWriter(backgroundScope, sink)

        writer.onProgress("book", "gen", 0, 1, 0)
        writer.onProgress("book", "gen", 0, 2, 0)
        writer.onProgress("book", "gen", 0, 3, 7)
        advanceTimeBy(799)
        runCurrent()
        assertTrue(sink.saves.isEmpty(), "去抖未到不应落库")

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, sink.saves.size)
        assertEquals(3, sink.saves.single().segmentIndex)
        assertEquals(7, sink.saves.single().charOffset)
    }

    @Test
    fun `flush 立即写入并取消待执行去抖`() = runTest {
        val sink = RecordingSink()
        val writer = SpeechProgressWriter(backgroundScope, sink)

        writer.onProgress("book", "gen", 2, 5, 0)
        writer.flush()
        assertEquals(1, sink.saves.size)
        assertEquals(2, sink.saves.single().chapterIndex)

        advanceTimeBy(1000)
        runCurrent()
        assertEquals(1, sink.saves.size, "flush 后不应重复写入")
    }

    @Test
    fun `reset 丢弃旧会话待写位置`() = runTest {
        val sink = RecordingSink()
        val writer = SpeechProgressWriter(backgroundScope, sink)

        writer.onProgress("old", "gen", 0, 1, 0)
        writer.reset()
        advanceTimeBy(1000)
        runCurrent()
        assertTrue(sink.saves.isEmpty())
    }

    @Test
    fun `写入失败可诊断且不影响后续写入`() = runTest {
        val sink = RecordingSink(failures = 1)
        val errors = mutableListOf<Throwable>()
        val writer = SpeechProgressWriter(backgroundScope, sink, onError = { errors += it })

        writer.onProgress("book", "gen", 0, 1, 0)
        advanceTimeBy(801)
        runCurrent()
        assertEquals(1, errors.size)
        assertTrue(sink.saves.isEmpty())

        writer.onProgress("book", "gen", 0, 2, 0)
        advanceTimeBy(801)
        runCurrent()
        assertEquals(1, sink.saves.size)
        assertEquals(2, sink.saves.single().segmentIndex)
    }

    @Test
    fun `无 sink 时静默跳过`() = runTest {
        val writer = SpeechProgressWriter(backgroundScope, sink = null)
        writer.onProgress("book", "gen", 0, 1, 0)
        writer.flush()
        advanceTimeBy(1000)
        runCurrent()
    }
}
