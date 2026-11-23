package com.yuezhou.reader.tts

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yuezhou.reader.core.speech.SpeechTextPlanning
import com.yuezhou.reader.core.speech.SpeechTextUnit
import com.yuezhou.reader.core.speech.SpeechChapterSource
import com.yuezhou.reader.core.speech.SpeechEngineType
import com.yuezhou.reader.core.speech.SpeechPlan
import com.yuezhou.reader.core.speech.SpeechSegment
import com.yuezhou.reader.reader.EpubChapterTextSource
import com.yuezhou.reader.reader.EpubImporter
import com.yuezhou.reader.reader.EpubSpeechChapterSource
import com.yuezhou.reader.reader.LibraryStore
import com.yuezhou.reader.speech.SpeechConfig
import com.yuezhou.reader.speech.SpeechPlanStore
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/**
 * 阶段 D 服务验收入口（仅 ttsTestDebug）：用测试版书库中的真实 EPUB 起读，
 * 随后销毁 Activity，让前台服务独立跑完 3 个章节。
 *
 * adb：
 *   adb shell am start -n com.yuezhou.reader.tts.test/com.yuezhou.reader.tts.StageDActivity \
 *     --es engine kokoro --es model int8 --ei sid 3
 */
class StageDActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = intent.getStringExtra("engine") ?: "kokoro"
        val model = intent.getStringExtra("model") ?: "int8"
        val sid = intent.getIntExtra("sid", 3)
        val rate = intent.getFloatExtra("rate", 1.0f).coerceIn(0.5f, 2.0f)
        val novelParsing = intent.getBooleanExtra("novelParsing", false)
        val toggleAfterMs = intent.getIntExtra("toggleAfterMs", 0)
        if (toggleAfterMs > 0) Handler(Looper.getMainLooper()).postDelayed({
            SpeechPlanStore.setNovelParsing(applicationContext, !novelParsing)
        }, toggleAfterMs.toLong())
        val bookId = intent.getStringExtra("book")
        val finishDelayMs = intent.getLongExtra("finishDelayMs", 1000L)
        executor.execute {
            try {
                startTrial(engine, model, sid, bookId, finishDelayMs, rate, novelParsing)
            } catch (t: Throwable) {
                Log.e(TAG, "D_HARNESS_FAIL", t)
                finishAndRemoveTask()
            }
        }
    }

    private fun startTrial(
        engineRaw: String,
        model: String,
        sid: Int,
        requestedBookId: String?,
        finishDelayMs: Long,
        rate: Float,
        novelParsing: Boolean,
    ) {
        val books = runBlocking { LibraryStore(applicationContext).snapshot() }.books
        val entry = books.firstOrNull { requestedBookId == null || it.bookId == requestedBookId }
            ?: error("测试版书库为空，请先导入 EPUB")
        val epub = File(EpubImporter.epubDir(this), entry.fileName)
        check(epub.isFile) { "EPUB 不存在：${epub.absolutePath}" }

        val engine = if (engineRaw == "system") SpeechEngineType.SYSTEM_TTS else SpeechEngineType.KOKORO
        var sourceUnits: List<SpeechTextUnit> = emptyList()
        var plannerVersion = SpeechPlan.PLANNER_VERSION
        var chapterIndex = 0
        var segments: List<SpeechSegment> = emptyList()
        for (index in 0 until 20) {
            val units = EpubChapterTextSource(epub, index).extract()
            val result = SpeechTextPlanning.plan(units, engine, novelParsing)
            val planned = result.segments
            if (planned.size >= 3) {
                sourceUnits = units
                plannerVersion = result.version
                chapterIndex = index
                segments = planned.take(3)
                break
            }
        }
        check(segments.isNotEmpty()) { "未找到可朗读章节" }

        SpeechPlanStore.putConfig(
            SpeechConfig(
                rate = rate,
                engine = engine,
                kokoroModelId = model,
                kokoroSpeakerId = sid,
                novelParsing = novelParsing,
            )
        )
        val plan = SpeechPlan(entry.bookId, "", chapterIndex, segments, plannerVersion,
            novelParsing && engine == SpeechEngineType.KOKORO, sourceUnits)
        val chapters = TrimmedChapterSource(
            EpubSpeechChapterSource(this, entry.fileName, null),
            maxSegments = 3,
        )
        Log.i(
            TAG,
            "D_HARNESS_START book=${entry.bookId} chapter=$chapterIndex " +
                "segments=${segments.size} engine=$engine model=$model sid=$sid",
        )
        SpeechPlanStore.startAndPlay(this, plan, chapters, 0)
        Handler(Looper.getMainLooper()).postDelayed({ finishAndRemoveTask() }, finishDelayMs)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    private companion object {
        const val TAG = "StageDActivity"
    }
}

/** 每章只保留前 [maxSegments] 句，把 3 章验收压缩到可接受时长。 */
private class TrimmedChapterSource(
    private val delegate: SpeechChapterSource,
    private val maxSegments: Int,
) : SpeechChapterSource {

    override suspend fun next(after: SpeechPlan): SpeechPlan? {
        val next = delegate.next(after) ?: return null
        return next.copy(segments = next.segments.take(maxSegments))
    }

    override fun close() = delegate.close()
}
