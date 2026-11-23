package com.yuezhou.reader.speech

import com.yuezhou.reader.core.speech.SessionToken
import com.yuezhou.reader.core.speech.SpeechPlaybackEvent
import com.yuezhou.reader.core.speech.SpeechStateMachine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpeechCoordinatorTest {

    private val tokenA = SessionToken(sessionId = 1, generation = 1)
    private val tokenB = SessionToken(sessionId = 1, generation = 2)

    /** 标准 TestDispatcher 下每次调度只推进一层，连续 runCurrent 让合成/入队链跑完。 */
    private fun TestScope.settle() {
        repeat(16) { runCurrent() }
    }

    private fun TestScope.recordEvents(coordinator: SpeechCoordinator): List<SpeechPlaybackEvent> {
        val events = mutableListOf<SpeechPlaybackEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.events.toList(events)
        }
        settle()
        return events
    }

    @Test
    fun `130 句连续播放按序完成且不提前推进`() = runTest {
        val synth = FakeSynthesizer()
        val player = FakePcmPlayer()
        val coordinator = SpeechCoordinator(synth, player, backgroundScope)
        val events = recordEvents(coordinator)

        coordinator.play(planOf(130), 0, tokenA)
        settle()
        assertEquals(-1, coordinator.lastCompletedSegmentIndex)
        assertEquals(listOf(0, 1, 2), player.enqueued)

        for (i in 0 until 130) {
            assertEquals(i - 1, coordinator.lastCompletedSegmentIndex, "第 $i 句完成前进度错误")
            assertTrue(player.completeNext(), "第 $i 句未在播放队列")
            settle()
            assertEquals(i, coordinator.lastCompletedSegmentIndex)
        }

        val completed = events.filterIsInstance<SpeechPlaybackEvent.SegmentCompleted>().map { it.index }
        assertEquals((0 until 130).toList(), completed)

        val started = mutableSetOf<Int>()
        for (event in events) {
            when (event) {
                is SpeechPlaybackEvent.SegmentStarted -> started += event.index
                is SpeechPlaybackEvent.SegmentCompleted -> assertTrue(
                    event.index in started,
                    "第 ${event.index} 句在 Started 之前完成",
                )
                else -> Unit
            }
        }
        assertEquals(1, events.count { it is SpeechPlaybackEvent.Buffering })
    }

    @Test
    fun `旧代次完成回调被丢弃`() = runTest {
        val synth = FakeSynthesizer()
        val player = FakePcmPlayer()
        val coordinator = SpeechCoordinator(synth, player, backgroundScope)
        coordinator.play(planOf(3), 0, tokenA)
        settle()

        player.complete(0, player.beginGeneration - 1)
        settle()
        assertEquals(-1, coordinator.lastCompletedSegmentIndex)

        player.complete(0, player.beginGeneration)
        settle()
        assertEquals(0, coordinator.lastCompletedSegmentIndex)
    }

    @Test
    fun `重新开始朗读后旧计划的完成回调被丢弃`() = runTest {
        val synth = FakeSynthesizer()
        val player = FakePcmPlayer()
        val coordinator = SpeechCoordinator(synth, player, backgroundScope)
        coordinator.play(planOf(5), 0, tokenA)
        settle()
        val oldGeneration = player.beginGeneration

        coordinator.play(planOf(5, "新"), 0, tokenB)
        settle()
        assertTrue(player.beginGeneration > oldGeneration)
        assertEquals(listOf(0, 1, 2), player.enqueued)

        player.complete(0, oldGeneration)
        settle()
        assertEquals(-1, coordinator.lastCompletedSegmentIndex)
    }

    @Test
    fun `暂停幂等且不推进完成进度`() = runTest {
        val synth = FakeSynthesizer()
        val player = FakePcmPlayer()
        val coordinator = SpeechCoordinator(synth, player, backgroundScope)
        coordinator.play(planOf(3), 0, tokenA)
        settle()

        coordinator.pause()
        coordinator.pause()
        settle()
        assertTrue(player.paused)
        assertEquals(SpeechStateMachine.State.PAUSED, coordinator.playbackState)
        assertEquals(-1, coordinator.lastCompletedSegmentIndex)

        coordinator.resume()
        coordinator.resume()
        settle()
        assertFalse(player.paused)
        assertEquals(SpeechStateMachine.State.PLAYING, coordinator.playbackState)

        player.completeNext()
        settle()
        assertEquals(0, coordinator.lastCompletedSegmentIndex)
    }

    @Test
    fun `停止后排空队列且旧完成事件不生效`() = runTest {
        val synth = FakeSynthesizer()
        val player = FakePcmPlayer()
        val coordinator = SpeechCoordinator(synth, player, backgroundScope)
        coordinator.play(planOf(3), 0, tokenA)
        settle()
        val generation = player.beginGeneration

        coordinator.stop()
        coordinator.stop()
        settle()
        assertEquals(SpeechStateMachine.State.STOPPED, coordinator.playbackState)
        assertTrue(player.stopped)
        assertTrue(player.enqueued.isEmpty())

        player.complete(0, generation)
        settle()
        assertEquals(-1, coordinator.lastCompletedSegmentIndex)

        coordinator.release()
        assertTrue(synth.released)
        assertTrue(player.released)
    }

    @Test
    fun `后台释放先停止播放且等待不可取消的合成返回后才释放模型`() = runTest {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val nativeReturned = kotlinx.coroutines.CompletableDeferred<Unit>()
        var released = false
        val synth = object : com.yuezhou.reader.core.speech.SpeechSynthesizer {
            override suspend fun synthesize(
                text: String,
                segmentIndex: Int,
                speed: Float,
                token: SessionToken,
            ): com.yuezhou.reader.core.speech.SynthesizedAudio {
                entered.complete(Unit)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    nativeReturned.await()
                }
                assertFalse(released)
                return com.yuezhou.reader.core.speech.SynthesizedAudio(FloatArray(240) { 0.2f }, 24_000)
            }

            override suspend fun release() { released = true }
        }
        val player = FakePcmPlayer()
        val coordinator = SpeechCoordinator(synth, player, backgroundScope)
        val events = recordEvents(coordinator)
        coordinator.play(planOf(3), 0, tokenA)
        settle()
        assertTrue(entered.isCompleted)

        val cleanup = backgroundScope.launch { coordinator.release() }
        settle()
        assertTrue(player.stopped)
        assertFalse(cleanup.isCompleted)
        assertFalse(released)
        // 清理在等待 native 时，其他协程仍可运行。
        var responsive = false
        backgroundScope.launch { responsive = true }
        settle()
        assertTrue(responsive)

        nativeReturned.complete(Unit)
        settle()
        assertTrue(cleanup.isCompleted)
        assertTrue(released)
        assertTrue(player.released)
        assertTrue(player.enqueued.isEmpty())
        assertTrue(events.none { it is SpeechPlaybackEvent.SegmentCompleted })
    }

    @Test
    fun `对白去引号且纯标点通过短暂停顿完成而不提前推进`() = runTest {
        val synth = FakeSynthesizer()
        val player = FakePcmPlayer()
        val coordinator = SpeechCoordinator(synth, player, backgroundScope)
        val events = recordEvents(coordinator)
        val original = planOf(3)
        val texts = listOf("“你好！”", "……”,", "接着读。")
        val plan = original.copy(segments = original.segments.mapIndexed { i, segment ->
            segment.copy(text = texts[i])
        })
        coordinator.play(plan, 0, tokenA)
        settle()
        assertEquals(listOf(0, 2), synth.calls.map { it.index })
        assertEquals(listOf("你好!", "接着读."), synth.calls.map { it.text })
        assertEquals(listOf(0, 1, 2), player.enqueued)
        assertEquals(-1, coordinator.lastCompletedSegmentIndex)
        assertTrue(events.none { it is SpeechPlaybackEvent.Failed })
        for (index in 0..2) {
            assertTrue(player.completeNext())
            settle()
            assertEquals(index, coordinator.lastCompletedSegmentIndex)
        }
        assertEquals(listOf(0, 1, 2), events.filterIsInstance<SpeechPlaybackEvent.SegmentCompleted>().map { it.index })
        // 输入规范化不改变计划中的原文、句子索引和锚点。
        assertEquals(texts, plan.segments.map { it.text })
        assertEquals(original.segments.map { it.startAnchor }, plan.segments.map { it.startAnchor })
    }

    @Test
    fun `初次播放使用配置语速且跨章继续保留`() = runTest {
        val synth = FakeSynthesizer()
        val player = FakePcmPlayer()
        val coordinator = SpeechCoordinator(synth, player, backgroundScope)
        coordinator.prepare(com.yuezhou.reader.core.speech.SpeechEngineConfig(
            engine = com.yuezhou.reader.core.speech.SpeechEngineType.KOKORO,
            rate = 1.8f,
        ))
        coordinator.play(planOf(3), 0, tokenA)
        settle()
        assertEquals(listOf(1.8f, 1.8f, 1.8f), synth.calls.map { it.speed })
        coordinator.play(planOf(2, "下一章"), 0, tokenB)
        settle()
        assertTrue(synth.calls.all { it.speed == 1.8f })
    }

    @Test
    fun `预生成入队不会提前切换高亮且旧代次开始事件无效`() = runTest {
        val player = FakePcmPlayer().apply { autoStart = false }
        val coordinator = SpeechCoordinator(FakeSynthesizer(), player, backgroundScope)
        val events = recordEvents(coordinator)
        coordinator.play(planOf(3), 0, tokenA)
        settle()
        assertEquals(listOf(0, 1, 2), player.enqueued)
        assertTrue(events.none { it is SpeechPlaybackEvent.SegmentStarted })
        player.start(1, player.beginGeneration - 1)
        settle()
        assertTrue(events.none { it is SpeechPlaybackEvent.SegmentStarted })
        player.startNext()
        settle()
        assertEquals(listOf(0), events.filterIsInstance<SpeechPlaybackEvent.SegmentStarted>().map { it.index })
        assertEquals(0, coordinator.currentSegmentIndex)
        // 播放器确认第一句结束后，第二句仍不能因早已入队而自动高亮。
        player.completeNext()
        settle()
        assertEquals(listOf(0), events.filterIsInstance<SpeechPlaybackEvent.SegmentStarted>().map { it.index })
        player.startNext()
        settle()
        assertEquals(listOf(0, 1), events.filterIsInstance<SpeechPlaybackEvent.SegmentStarted>().map { it.index })
        assertEquals(1, coordinator.currentSegmentIndex)
    }

    @Test
    fun `合成内存不足不重复分配并向服务报告降级原因`() = runTest {
        val synth = FakeSynthesizer().apply { outOfMemory = true }
        val coordinator = SpeechCoordinator(synth, FakePcmPlayer(), backgroundScope)
        val events = recordEvents(coordinator)
        coordinator.play(planOf(3), 0, tokenA)
        settle()
        assertEquals(1, synth.calls.size)
        assertTrue(events.filterIsInstance<SpeechPlaybackEvent.Failed>().single().memoryPressure)
    }

    @Test
    fun `有界预合成最多领先当前句两句`() = runTest {
        val synth = FakeSynthesizer()
        val player = FakePcmPlayer()
        val coordinator = SpeechCoordinator(synth, player, backgroundScope)
        coordinator.play(planOf(20), 0, tokenA)
        settle()
        assertEquals(listOf(0, 1, 2), synth.calls.map { it.index })

        player.completeNext()
        settle()
        assertEquals(listOf(0, 1, 2, 3), synth.calls.map { it.index })

        player.completeNext()
        settle()
        assertEquals(listOf(0, 1, 2, 3, 4), synth.calls.map { it.index })
    }

    @Test
    fun `切速重合成当前未完成句且不改完成进度`() = runTest {
        val synth = FakeSynthesizer()
        val player = FakePcmPlayer()
        val coordinator = SpeechCoordinator(synth, player, backgroundScope)
        coordinator.play(planOf(5), 0, tokenA)
        settle()
        val firstTimeline = player.beginGeneration

        coordinator.setRate(1.5f)
        settle()
        assertEquals(-1, coordinator.lastCompletedSegmentIndex)
        assertTrue(player.beginGeneration > firstTimeline)
        assertEquals(1.5f, synth.calls.last { it.index == 0 }.speed)
        assertEquals(listOf(0, 1, 2), player.enqueued)
    }

    @Test
    fun `本章最后一句完成才触发跨章回调`() = runTest {
        val synth = FakeSynthesizer()
        val player = FakePcmPlayer()
        var chapters = 0
        val coordinator = SpeechCoordinator(
            synth,
            player,
            backgroundScope,
            onChapterCompleted = { chapters++ },
        )
        coordinator.play(planOf(4), 0, tokenA)
        settle()
        for (i in 0 until 3) {
            player.completeNext()
            settle()
            assertEquals(0, chapters, "第 $i 句不应触发跨章")
        }
        assertTrue(player.completeNext())
        settle()
        assertEquals(1, chapters)
    }

    @Test
    fun `健康检查失败重试一次后成功`() = runTest {
        val synth = FakeSynthesizer()
        synth.badOnFirstAttempt += 0
        val player = FakePcmPlayer()
        val coordinator = SpeechCoordinator(synth, player, backgroundScope)
        coordinator.play(planOf(1), 0, tokenA)
        settle()
        assertEquals(2, synth.calls.count { it.index == 0 })
        assertEquals(listOf(0), player.enqueued)
    }

    @Test
    fun `健康检查持续失败发出 Failed 且不入队`() = runTest {
        val synth = FakeSynthesizer()
        synth.alwaysBad += 0
        val player = FakePcmPlayer()
        val coordinator = SpeechCoordinator(synth, player, backgroundScope)
        val events = recordEvents(coordinator)
        coordinator.play(planOf(2), 0, tokenA)
        settle()

        assertEquals(2, synth.calls.count { it.index == 0 })
        assertTrue(player.enqueued.isEmpty())
        assertTrue(events.any { it is SpeechPlaybackEvent.Failed })
        assertEquals(-1, coordinator.lastCompletedSegmentIndex)
    }

    @Test
    fun `合成抛异常时发出 Failed`() = runTest {
        val synth = FakeSynthesizer().apply { throwing = true }
        val player = FakePcmPlayer()
        val coordinator = SpeechCoordinator(synth, player, backgroundScope)
        val events = recordEvents(coordinator)
        coordinator.play(planOf(1), 0, tokenA)
        settle()
        assertTrue(player.enqueued.isEmpty())
        assertTrue(events.any { it is SpeechPlaybackEvent.Failed })
    }

    @Test
    fun `从指定句开始朗读`() = runTest {
        val synth = FakeSynthesizer()
        val player = FakePcmPlayer()
        val coordinator = SpeechCoordinator(synth, player, backgroundScope)
        coordinator.play(planOf(10), 5, tokenA)
        settle()
        assertEquals(listOf(5, 6, 7), player.enqueued)
        assertEquals(listOf(5, 6, 7), synth.calls.map { it.index })
        assertEquals(5, coordinator.currentSegmentIndex)
        assertEquals(-1, coordinator.lastCompletedSegmentIndex)
    }

    @Test
    fun `PCM 预算耗尽时暂停预合成`() = runTest {
        val synth = FakeSynthesizer()
        val player = FakePcmPlayer()
        val coordinator = SpeechCoordinator(synth, player, backgroundScope, maxPcmBytes = 1)
        coordinator.play(planOf(5), 0, tokenA)
        settle()
        assertEquals(listOf(0), synth.calls.map { it.index })

        player.completeNext()
        settle()
        assertEquals(listOf(0, 1), synth.calls.map { it.index })
    }

    @Test
    fun `缓冲中不推进完成进度`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val synth = FakeSynthesizer().apply { this.gate = gate }
        val player = FakePcmPlayer()
        val coordinator = SpeechCoordinator(synth, player, backgroundScope)
        val events = recordEvents(coordinator)
        coordinator.play(planOf(2), 0, tokenA)
        settle()

        assertTrue(events.any { it is SpeechPlaybackEvent.Buffering })
        assertEquals(-1, coordinator.lastCompletedSegmentIndex)
        assertTrue(player.enqueued.isEmpty())

        gate.complete(Unit)
        settle()
        assertTrue(player.enqueued.isNotEmpty())
        assertEquals(-1, coordinator.lastCompletedSegmentIndex)
    }

    @Test
    fun `空计划发出 Failed 不启动播放`() = runTest {
        val synth = FakeSynthesizer()
        val player = FakePcmPlayer()
        val coordinator = SpeechCoordinator(synth, player, backgroundScope)
        val events = recordEvents(coordinator)
        coordinator.play(planOf(0), 0, tokenA)
        settle()
        assertTrue(events.any { it is SpeechPlaybackEvent.Failed })
        assertTrue(synth.calls.isEmpty())
        assertEquals(-1, coordinator.lastCompletedSegmentIndex)
    }
}
