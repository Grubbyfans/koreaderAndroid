package com.yuezhou.reader.speech

import com.yuezhou.reader.core.speech.SpeechProgressSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 服务侧朗读位置写入器（技术方案 §10.2）。
 *
 * - 过程写入按 [debounceMs] 去抖，只保留最新位置；
 * - [flush] 跳过去抖立即写入，暂停/停止/切书时调用；
 * - [reset] 作废尚未写出的旧会话位置，防止旧书/旧代次覆盖新进度；
 * - 写入失败只回调 [onError]，不中断朗读。
 */
class SpeechProgressWriter(
    private val scope: CoroutineScope,
    private val sink: SpeechProgressSink?,
    private val debounceMs: Long = 800L,
    private val onError: (Throwable) -> Unit = {},
) {

    data class Progress(
        val bookId: String,
        val fileGeneration: String,
        val chapterIndex: Int,
        val segmentIndex: Int,
        val charOffset: Int,
    )

    private val lock = Any()
    private var generation = 0L
    private var pending: Progress? = null
    private var job: Job? = null

    fun onProgress(
        bookId: String,
        fileGeneration: String,
        chapterIndex: Int,
        segmentIndex: Int,
        charOffset: Int,
    ) {
        val progress = Progress(bookId, fileGeneration, chapterIndex, segmentIndex, charOffset)
        synchronized(lock) {
            pending = progress
            job?.cancel()
            val currentGeneration = generation
            job = scope.launch {
                delay(debounceMs)
                persist(currentGeneration)
            }
        }
    }

    /** 立即写入尚未落库的最新位置；无待写位置时直接返回。 */
    suspend fun flush() {
        val progress = synchronized(lock) {
            job?.cancel()
            job = null
            pending.also { pending = null }
        } ?: return
        withContext(NonCancellable) { write(progress) }
    }

    /** 作废旧会话的待写位置（切书、应用云端进度、服务重启时调用）。 */
    fun reset() {
        synchronized(lock) {
            generation++
            pending = null
            job?.cancel()
            job = null
        }
    }

    private suspend fun persist(expectedGeneration: Long) {
        val progress = synchronized(lock) {
            if (expectedGeneration != generation) return
            job = null
            pending.also { pending = null }
        } ?: return
        write(progress)
    }

    private suspend fun write(progress: Progress) {
        val target = sink ?: return
        try {
            target.save(
                bookId = progress.bookId,
                fileGeneration = progress.fileGeneration,
                chapterIndex = progress.chapterIndex,
                segmentIndex = progress.segmentIndex,
                charOffset = progress.charOffset,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onError(t)
        }
    }
}
