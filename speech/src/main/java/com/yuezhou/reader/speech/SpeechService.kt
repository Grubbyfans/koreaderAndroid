package com.yuezhou.reader.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.ServiceCompat
import com.yuezhou.reader.core.speech.SessionToken
import com.yuezhou.reader.core.speech.SpeechEngineConfig
import com.yuezhou.reader.core.speech.SpeechEngineType
import com.yuezhou.reader.core.speech.SpeechPlaybackEvent
import com.yuezhou.reader.core.speech.SpeechPlan
import com.yuezhou.reader.core.speech.SpeechStateMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 前台朗读服务：持有 TTS 引擎与朗读状态机，暴露媒体控制，处理音频焦点与耳机断开。
 *
 * 对齐技术方案 §7.3 / §7.5：
 *   - 后台朗读不依赖 Activity/WebView 存活；计划由服务独立消费；
 *   - 通过 MediaSession 暴露播放/暂停/停止/前后句；
 *   - 处理耳机断开暂停、来电/音频焦点变化、通知操作；
 *   - 每次启动生成会话 ID，忽略旧引擎回调（见 [sessionId]）。
 *
 * 用法：app 层通过 [SpeechCommands] 的意图 action 控制本服务（startService + 附带动作），
 * 计划通过 [SpeechPlanStore]（进程内内存）传入——首版以内存承载，持久化留待 Room（§8.2 SpeechPlan 表）。
 *
 * 阶段 D：按 [SpeechConfig.engine] 选择系统 TTS 或内置 Kokoro；Kokoro 走 [SpeechCoordinator]
 * 与单 AudioTrack 播放器，完成事件驱动跨章；朗读位置由服务通过 [SpeechRuntime.progressSink]
 * 落库；朗读期间持有 PARTIAL_WAKE_LOCK，暂停/停止/错误释放。
 */
class SpeechService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 持久化专用作用域：服务停止/销毁时仍允许把最后一次位置写完。 */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Kokoro 流水线专用作用域：AudioTrack 阻塞写会长时间占用线程，
     * 不能放在 Main 上，否则暂停后主线程被卡死，媒体键与看门狗均失效。
     */
    private val kokoroScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val progressWriter = SpeechProgressWriter(
        scope = persistScope,
        sink = SpeechRuntime.progressSink,
        onError = { Log.w(TAG, "朗读进度写入失败", it) },
    )

    private lateinit var audioManager: AudioManager
    private var tts: TtsEngine? = null
    private val stateMachine = SpeechStateMachine()
    private var request: SpeechPlanStore.Request? = null
    private var playJob: Job? = null
    private var chapterJob: Job? = null
    private var waitingChapter: SpeechPlan? = null
    private var chapterPaused = false

    /** 当前会话引擎；Kokoro 未装配时始终为系统 TTS。 */
    private var activeEngine = SpeechEngineType.SYSTEM_TTS

    /** 每次 play/stop 递增；用于丢弃启动过程中被新请求取代的 Kokoro 初始化。 */
    private var playGeneration = 0L

    private var kokoroCoordinator: SpeechCoordinator? = null
    private var kokoroEventJob: Job? = null
    private var kokoroGeneration = 0L
    private var kokoroSpeed = 1.0f
    private var lastKokoroEventAt = 0L
    private var lastKokoroProgressAt = 0L
    private var lastKokoroPositionMs = -1L

    /** 首声计时起点与是否已记录（诊断用）。 */
    private var firstSoundStartedAt = 0L
    private var firstSoundRecorded = false

    private var wakeLock: PowerManager.WakeLock? = null

    private var mediaSession: MediaSession? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusGranted = false

    /** 每次开始朗读递增的会话 ID；旧回调据此作废。 */
    private var sessionId = 0L

    /** 看门狗协程：检测引擎后台静默（三星 SMT 等引擎切后台后可能空合成丢弃队列），自动续播剩余段。 */
    private var watchdogJob: Job? = null

    /** 连续多少次检测到静默才触发续播，避免引擎短暂的合成间隙被误判。 */
    private var silentStreak = 0

    /** 耳机断开监听。 */
    private var becomingNoisyRegistered = false

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        mediaSession = MediaSession(this, "SpeechService").apply {
            setCallback(sessionCallback)
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            SpeechCommands.ACTION_PLAY -> {
                // 进入前台须在 startForegroundService 后 5 秒内完成，先同步拉前台再异步初始化引擎。
                startForegroundInternal()
                handlePlay()
            }
            SpeechCommands.ACTION_PAUSE -> handlePause()
            SpeechCommands.ACTION_RESUME -> handleResume()
            SpeechCommands.ACTION_NEXT -> handleNext()
            SpeechCommands.ACTION_PREVIOUS -> handlePrevious()
            SpeechCommands.ACTION_STOP -> handleStop()
            SpeechCommands.ACTION_STOP_FOREGROUND -> handleStop()
            SpeechCommands.ACTION_SET_RATE ->
                handleSetRate(intent.getFloatExtra(SpeechCommands.EXTRA_RATE, 0f))
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- 控制动作 ----

    private fun handlePlay() {
        val nextRequest = SpeechPlanStore.peek() ?: run {
            handleStop()
            return
        }
        playJob?.cancel()
        clearChapterTransition()
        request = nextRequest
        sessionId++
        val generation = ++playGeneration
        playJob = scope.launch {
            beginProgressSession()
            stopCurrentEngine()
            if (generation != playGeneration) return@launch
            val config = SpeechPlanStore.peekConfig()
            val useKokoro = config.engine == SpeechEngineType.KOKORO && SpeechRuntime.kokoroAvailable
            if (config.engine == SpeechEngineType.KOKORO && !useKokoro) {
                Log.w(TAG, "请求 Kokoro 但引擎未装配，回退系统 TTS")
            }
            activeEngine = if (useKokoro) SpeechEngineType.KOKORO else SpeechEngineType.SYSTEM_TTS
            if (useKokoro) {
                startKokoro(nextRequest.plan, nextRequest.startSegment, config, generation)
            } else {
                val engine = ensureEngine() ?: return@launch handleStop("TTS 引擎初始化失败")
                applyConfig(engine)
                playChapter(engine, nextRequest.plan, nextRequest.startSegment)
            }
        }
    }

    /** 实时调速：Kokoro 重合成当前未完成句；系统 TTS 应用到后续入队句。 */
    private fun handleSetRate(rate: Float) {
        if (rate <= 0f) return
        SpeechDiagnosticsState.update { it.copy(rate = rate) }
        if (activeEngine == SpeechEngineType.KOKORO) {
            scope.launch { runCatching { kokoroCoordinator?.setRate(rate) } }
            return
        }
        tts?.applyConfig(rate, SpeechPlanStore.peekConfig().voiceName)
    }

    private suspend fun beginProgressSession() {
        runCatching { progressWriter.flush() }
        progressWriter.reset()
        if (SpeechRuntime.progressSink == null) {
            Log.e(TAG, "进度写入端口未装配，朗读位置不会落库")
        }
    }

    /** 停止当前引擎（保留系统 TTS 实例，释放 Kokoro native 引擎）。 */
    private suspend fun stopCurrentEngine() {
        tts?.stop()
        kokoroEventJob?.cancel()
        kokoroEventJob = null
        val coordinator = kokoroCoordinator
        kokoroCoordinator = null
        if (coordinator != null) {
            // cancel 只能作废结果，JNI 仍可能运行；后台等待结束后再释放，且不能中途取消清理。
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { coordinator.release() }
                    .onFailure { Log.w(TAG, "Kokoro 资源释放失败", it) }
            }
        }
    }

    private fun playChapter(engine: TtsEngine, plan: SpeechPlan, startSegment: Int = 0) {
        stateMachine.load(plan)
        sessionId++
        engine.listener = createProgressListener()
        acquireWakeLock()
        Log.d(TAG, "D_CHAPTER_START engine=SYSTEM book=${plan.bookId} chapter=${plan.chapterIndex} from=$startSegment")
        firstSoundStartedAt = SystemClock.elapsedRealtime()
        firstSoundRecorded = false
        SpeechDiagnosticsState.resetSession(
            engine = "SYSTEM_TTS",
            rate = SpeechPlanStore.peekConfig().rate,
        )
        val startAnchor = plan.segmentAt(startSegment)?.startAnchor
        progressWriter.onProgress(
            plan.bookId, plan.fileGeneration, plan.chapterIndex, startSegment, startAnchor?.offset ?: 0,
            anchorNodeId = startAnchor?.nodeId ?: "",
            plannerVersion = plan.plannerVersion,
        )
        speakFrom(engine, startSegment)
        // 预取下一章：末句 onDone 到达时下一章已就绪，跨章切换不再被「拉取耗时 + 回调延迟」卡住
        prefetchNextChapter()
    }

    /**
     * 从指定片段起，把本章剩余片段一次性连续入队（QUEUE_ADD），交由 TTS 引擎自行连续朗读。
     * 不再依赖逐句 onDone 回调驱动续播——onDone 仅用于推进进度游标与高亮。
     */
    private fun speakFrom(engine: TtsEngine, fromSegment: Int) {
        val plan = stateMachine.plan ?: return
        if (plan.segments.isEmpty()) return
        val start = fromSegment.coerceIn(0, plan.segments.lastIndex)
        // 定位游标到起始段
        stateMachine.play(start)
        val remaining = plan.segments.drop(start).filter { !it.text.isBlank() }
        if (remaining.isEmpty()) {
            // 本章无可朗读文本，直接跨章
            onSegmentDone()
            return
        }
        val pairs = plan.segments.withIndex()
            .drop(start)
            .filter { !it.value.text.isBlank() }
            .map { it.value.text to utteranceIdFor(it.index) }
        val queued = engine.speakAll(pairs)
        Log.d(TAG, "speakFrom() 从段 $start 连续入队 $queued/${remaining.size} 段, state=${stateMachine.state}")
        if (queued < 0) {
            handleStop("TTS 合成失败")
            return
        }
        updatePlaybackState(PlaybackState.STATE_PLAYING, segmentIndex = start)
        startWatchdog()
    }

    // ---- Kokoro 引擎路径 ----

    private suspend fun startKokoro(
        plan: SpeechPlan,
        startSegment: Int,
        config: SpeechConfig,
        generation: Long,
    ) {
        val provider = SpeechRuntime.kokoro ?: run {
            handleStop("Kokoro 引擎不可用")
            return
        }
        val modelId = config.kokoroModelId.ifBlank { provider.defaultModelId }
        kokoroSpeed = if (config.rate > 0f) config.rate else 1f
        updatePlaybackState(PlaybackState.STATE_BUFFERING)
        acquireWakeLock()
        Log.d(
            TAG,
            "D_ENGINE engine=KOKORO model=$modelId speaker=${config.kokoroSpeakerId} " +
                "rate=$kokoroSpeed start=$startSegment",
        )
        val loadStartedAt = SystemClock.elapsedRealtime()
        val synthesizer = try {
            // native 初始化无法立即取消；保留结果，让过期会话仍能安全释放它。
            withContext(NonCancellable) {
                provider.createSynthesizer(modelId, config.kokoroSpeakerId)
            }
        } catch (e: CancellationException) {
            releaseWakeLock()
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Kokoro 模型加载失败 model=$modelId", t)
            handleStop("Kokoro 模型加载失败：${t.message}")
            return
        }
        if (generation != playGeneration) {
            Log.d(TAG, "Kokoro 初始化完成但会话已被取代，释放")
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { synthesizer.release() }
            }
            return
        }
        SpeechDiagnosticsState.resetSession(
            engine = "KOKORO",
            modelId = modelId,
            speakerId = config.kokoroSpeakerId,
            rate = kokoroSpeed,
            modelLoadMs = SystemClock.elapsedRealtime() - loadStartedAt,
        )
        val coordinator = SpeechCoordinator(
            synthesizer = synthesizer,
            player = AudioTrackPcmPlayer(),
            scope = kokoroScope,
            // 流水线在 IO 线程；服务状态机/落库等逻辑必须回到主线程串行执行。
            onChapterCompleted = { finished ->
                withContext(Dispatchers.Main) { onKokoroChapterCompleted(finished) }
            },
        )
        coordinator.prepare(SpeechEngineConfig(
            engine = SpeechEngineType.KOKORO,
            rate = kokoroSpeed,
            speakerId = config.kokoroSpeakerId,
        ))
        kokoroCoordinator = coordinator
        lastKokoroEventAt = SystemClock.elapsedRealtime()
        lastKokoroProgressAt = lastKokoroEventAt
        lastKokoroPositionMs = -1L
        kokoroEventJob = scope.launch { coordinator.events.collect { onKokoroEvent(it) } }
        playKokoroPlan(plan, startSegment)
    }

    private suspend fun playKokoroPlan(plan: SpeechPlan, fromIndex: Int, paused: Boolean = false) {
        val coordinator = kokoroCoordinator ?: return
        if (stateMachine.plan !== plan) stateMachine.load(plan)
        stateMachine.play(fromIndex)
        val token = SessionToken(sessionId = sessionId, generation = ++kokoroGeneration)
        updatePlaybackState(
            if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_BUFFERING,
        )
        firstSoundStartedAt = SystemClock.elapsedRealtime()
        firstSoundRecorded = false
        coordinator.play(plan, fromIndex, token)
        lastKokoroEventAt = SystemClock.elapsedRealtime()
        lastKokoroProgressAt = lastKokoroEventAt
        lastKokoroPositionMs = -1L
        val startAnchor = plan.segmentAt(fromIndex)?.startAnchor
        progressWriter.onProgress(
            plan.bookId, plan.fileGeneration, plan.chapterIndex, fromIndex, startAnchor?.offset ?: 0,
            anchorNodeId = startAnchor?.nodeId ?: "",
            plannerVersion = plan.plannerVersion,
        )
        if (paused) {
            coordinator.pause()
            stateMachine.pause()
            releaseWakeLock()
            updatePlaybackState(PlaybackState.STATE_PAUSED)
        } else {
            acquireWakeLock()
            startWatchdog()
            Log.d(TAG, "D_CHAPTER_START book=${plan.bookId} chapter=${plan.chapterIndex} from=$fromIndex")
        }
    }

    private fun onKokoroEvent(event: SpeechPlaybackEvent) {
        if (event.token.generation != kokoroGeneration) return
        lastKokoroEventAt = SystemClock.elapsedRealtime()
        when (event) {
            is SpeechPlaybackEvent.SegmentStarted -> {
                val plan = stateMachine.plan ?: return
                Log.d(
                    TAG,
                    "D_SEG_START index=${event.index} chars=${plan.segmentAt(event.index)?.text?.length}",
                )
                stateMachine.onSegmentStarted(event.index)
                recordFirstSound()
                SpeechDiagnosticsState.update {
                    it.copy(healthSummary = kokoroCoordinator?.lastHealthReport?.summary().orEmpty())
                }
                val segment = plan.segmentAt(event.index) ?: return
                SpeechProgressTracker.onProgress(
                    bookId = plan.bookId,
                    chapterIndex = plan.chapterIndex,
                    segmentIndex = event.index,
                    charOffset = 0,
                    anchor = segment.startAnchor,
                    endAnchor = segment.endAnchor,
                )
                progressWriter.onProgress(
                    plan.bookId, plan.fileGeneration, plan.chapterIndex, event.index, segment.startAnchor.offset,
                    anchorNodeId = segment.startAnchor.nodeId,
                    plannerVersion = plan.plannerVersion,
                )
                if (stateMachine.state == SpeechStateMachine.State.PLAYING) {
                    updatePlaybackState(PlaybackState.STATE_PLAYING)
                }
            }
            is SpeechPlaybackEvent.SegmentCompleted -> {
                val plan = stateMachine.plan ?: return
                Log.d(TAG, "D_SEG_DONE index=${event.index}")
                stateMachine.onSegmentCompleted(event.index)
                val segment = plan.segmentAt(event.index) ?: return
                SpeechProgressTracker.onProgress(
                    bookId = plan.bookId,
                    chapterIndex = plan.chapterIndex,
                    segmentIndex = event.index,
                    charOffset = segment.text.length,
                    anchor = segment.endAnchor,
                    endAnchor = segment.endAnchor,
                )
                // 完成即把游标推进到下一句起点（含稳定锚点），恢复时从下一句继续，
                // 不因写「已完成句末」而重读；本章末句则保留末句末位锚点。
                val next = plan.segmentAt(event.index + 1)
                progressWriter.onProgress(
                    plan.bookId, plan.fileGeneration, plan.chapterIndex,
                    if (next != null) event.index + 1 else event.index,
                    if (next != null) next.startAnchor.offset else segment.endAnchor.offset,
                    anchorNodeId = if (next != null) next.startAnchor.nodeId else segment.endAnchor.nodeId,
                    plannerVersion = plan.plannerVersion,
                )
            }
            is SpeechPlaybackEvent.Buffering -> {
                SpeechDiagnosticsState.update { it.copy(bufferingCount = it.bufferingCount + 1) }
                if (stateMachine.state == SpeechStateMachine.State.PLAYING) {
                    updatePlaybackState(PlaybackState.STATE_BUFFERING)
                }
            }
            is SpeechPlaybackEvent.Failed -> {
                Log.e(TAG, "D_FAILED ${event.reason}")
                SpeechDiagnosticsState.update { it.copy(lastError = event.reason) }
                handleStop(event.reason)
            }
        }
    }

    private suspend fun onKokoroChapterCompleted(finished: SpeechPlan) {
        if (activeEngine != SpeechEngineType.KOKORO) return
        // 协调器的完成序号是权威值：服务事件收集器可能尚未处理末句 SegmentCompleted。
        val completedIndex = kokoroCoordinator?.lastCompletedSegmentIndex ?: -1
        finished.segmentAt(completedIndex)?.let { segment ->
            progressWriter.onProgress(
                finished.bookId, finished.fileGeneration, finished.chapterIndex,
                completedIndex, segment.endAnchor.offset,
                anchorNodeId = segment.endAnchor.nodeId,
                plannerVersion = finished.plannerVersion,
            )
        }
        Log.d(
            TAG,
            "D_CHAPTER_DONE book=${finished.bookId} chapter=${finished.chapterIndex} " +
                "completed=$completedIndex",
        )
        progressWriter.flush()
        Log.d(TAG, "D_PROGRESS chapter=${finished.chapterIndex} segment=$completedIndex")
        advanceKokoroChapter(finished)
    }

    private suspend fun advanceKokoroChapter(previous: SpeechPlan) {
        val active = request ?: return
        val next = try {
            active.chapters.next(previous)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "无法准备下一章", t)
            handleStop("无法继续朗读下一章")
            return
        }
        if (next == null) {
            Log.d(TAG, "D_BOOK_END book=${previous.bookId}")
            handleStop()
            return
        }
        if (stateMachine.state == SpeechStateMachine.State.PAUSED) {
            waitingChapter = next
            chapterPaused = true
            Log.d(TAG, "D_CHAPTER_WAIT chapter=${next.chapterIndex}")
        } else {
            playKokoroPlan(next, 0)
        }
    }

    private fun handleKokoroPause() {
        if (waitingChapter != null) chapterPaused = true
        scope.launch {
            kokoroCoordinator?.pause()
            stateMachine.pause()
            updatePlaybackState(PlaybackState.STATE_PAUSED)
            releaseWakeLock()
            runCatching { progressWriter.flush() }
        }
    }

    private fun handleKokoroResume() {
        val next = waitingChapter
        if (next != null) {
            waitingChapter = null
            chapterPaused = false
            scope.launch { playKokoroPlan(next, 0) }
            return
        }
        scope.launch {
            val coordinator = kokoroCoordinator ?: return@launch
            coordinator.resume()
            stateMachine.resume()
            lastKokoroEventAt = SystemClock.elapsedRealtime()
            lastKokoroProgressAt = lastKokoroEventAt
            lastKokoroPositionMs = -1L
            acquireWakeLock()
            updatePlaybackState(PlaybackState.STATE_PLAYING)
        }
    }

    private fun handleKokoroNext() {
        val plan = stateMachine.plan ?: return
        val paused = stateMachine.state == SpeechStateMachine.State.PAUSED
        if (stateMachine.currentSegmentIndex >= plan.segments.lastIndex) {
            scope.launch { advanceKokoroChapter(plan) }
            return
        }
        val target = (stateMachine.currentSegmentIndex + 1).coerceAtMost(plan.segments.lastIndex)
        scope.launch { playKokoroPlan(plan, target, paused) }
    }

    private fun handleKokoroPrevious() {
        val plan = stateMachine.plan ?: return
        val target = (stateMachine.currentSegmentIndex - 1).coerceAtLeast(0)
        scope.launch {
            playKokoroPlan(plan, target, stateMachine.state == SpeechStateMachine.State.PAUSED)
        }
    }

    private fun handleKokoroStop(error: String?) {
        playGeneration++
        stopWatchdog()
        clearChapterTransition()
        kokoroEventJob?.cancel()
        kokoroEventJob = null
        val coordinator = kokoroCoordinator
        kokoroScope.launch { runCatching { coordinator?.stop() } }
        stateMachine.stop()
        activeEngine = SpeechEngineType.SYSTEM_TTS
        request?.let { SpeechPlanStore.clear(it) }
        request = null
        SpeechProgressTracker.clear()
        releaseAudioFocus()
        releaseWakeLock()
        persistScope.launch { runCatching { progressWriter.flush() } }
        updatePlaybackState(
            if (error == null) PlaybackState.STATE_STOPPED else PlaybackState.STATE_ERROR,
            errorMessage = error,
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- 看门狗：检测引擎后台静默并自动续播 ----

    /**
     * 启动看门狗：周期检测 TTS 引擎是否「静默但有剩余未读段」。
     *
     * 背景：三星 SMT 等系统引擎在 App 切后台后，可能把 QUEUE_ADD 队列里的后续片段
     * 空合成（生成 0 字节音频）并丢弃，导致既无声也无 onDone 回调，朗读停在中间。
     * 看门狗发现这种静默后，从当前游标段起重新连续入队剩余段，让朗读继续。
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        silentStreak = 0
        val mySession = sessionId
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (activeEngine == SpeechEngineType.KOKORO) {
                    if (stateMachine.state != SpeechStateMachine.State.PLAYING) continue
                    val now = SystemClock.elapsedRealtime()
                    // 长句播放期间只有 Started/Completed 事件，单句音频可超过 60 s；
                    // 用播放头推进作为进展信号，避免把长句误判为停滞。
                    val position = kokoroCoordinator?.playbackPositionMs() ?: -1L
                    if (position >= 0 && position != lastKokoroPositionMs) {
                        lastKokoroPositionMs = position
                        lastKokoroProgressAt = now
                    }
                    val lastProgress = maxOf(lastKokoroEventAt, lastKokoroProgressAt)
                    if (now - lastProgress > KOKORO_STALL_TIMEOUT_MS) {
                        Log.w(TAG, "Kokoro 看门狗：${KOKORO_STALL_TIMEOUT_MS}ms 无播放进展，停止朗读")
                        handleStop("朗读停滞")
                    }
                    continue
                }
                if (mySession != sessionId) return@launch
                if (stateMachine.state != SpeechStateMachine.State.PLAYING) continue
                val engine = tts ?: continue
                val plan = stateMachine.plan ?: continue
                // 本章还有未读段，但引擎已静默 → 疑似队列被引擎丢弃
                val pos = stateMachine.position.segmentIndex
                if (pos >= plan.segments.lastIndex) {
                    // 已到章末（等末句 onDone 触发跨章），不属于静默续播范畴
                    silentStreak = 0
                    continue
                }
                if (!engine.isSpeaking()) {
                    silentStreak++
                    Log.w(TAG, "看门狗检测到引擎静默 pos=$pos/${plan.segments.lastIndex}, streak=$silentStreak")
                    if (silentStreak >= WATCHDOG_THRESHOLD) {
                        silentStreak = 0
                        Log.w(TAG, "看门狗触发续播：从段 $pos 重新连续入队剩余段")
                        engine.stop()
                        speakFrom(engine, pos)
                    }
                } else {
                    silentStreak = 0
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
        silentStreak = 0
    }

    private fun clearChapterTransition() {
        chapterJob?.cancel()
        chapterJob = null
        waitingChapter = null
        chapterPaused = false
    }

    /** 应用运行期朗读配置（语速 + 音色）。 */
    private fun applyConfig(engine: TtsEngine) {
        val config = SpeechPlanStore.peekConfig()
        engine.applyConfig(config.rate, config.voiceName)
    }

    private fun handlePause() {
        if (activeEngine == SpeechEngineType.KOKORO) {
            handleKokoroPause()
            return
        }
        Log.d(TAG, "handlePause() 进入, state=${stateMachine.state}, segment=${stateMachine.position.segmentIndex}, chapterJob=${chapterJob != null}, waitingChapter=${waitingChapter != null}")
        if (chapterJob != null || waitingChapter != null) chapterPaused = true
        sessionId++
        stopWatchdog()
        tts?.stop()
        stateMachine.pause()
        releaseWakeLock()
        persistScope.launch { runCatching { progressWriter.flush() } }
        updatePlaybackState(PlaybackState.STATE_PAUSED)
        Log.d(TAG, "handlePause() 完成, state=${stateMachine.state}")
    }

    private fun handleResume() {
        if (activeEngine == SpeechEngineType.KOKORO) {
            handleKokoroResume()
            return
        }
        Log.d(TAG, "handleResume() 进入, state=${stateMachine.state}, segment=${stateMachine.position.segmentIndex}")
        val engine = tts ?: return
        chapterPaused = false
        val next = waitingChapter
        if (next != null) {
            waitingChapter = null
            playChapter(engine, next)
            return
        }
        if (chapterJob != null) {
            updatePlaybackState(PlaybackState.STATE_BUFFERING)
            return
        }
        // 从暂停位置起，把剩余段连续入队继续读
        val fromSegment = stateMachine.position.segmentIndex
        stateMachine.resume().getOrNull() ?: return
        sessionId++
        engine.listener = createProgressListener()
        acquireWakeLock()
        speakFrom(engine, fromSegment)
    }

    private fun handleNext() {
        if (activeEngine == SpeechEngineType.KOKORO) {
            handleKokoroNext()
            return
        }
        if (chapterJob != null || waitingChapter != null) return
        val engine = tts ?: return
        val plan = stateMachine.plan ?: return
        if (stateMachine.position.segmentIndex >= plan.segments.lastIndex) {
            sessionId++
            engine.stop()
            loadNextChapter(engine)
            return
        }
        stateMachine.next().getOrNull() ?: return
        sessionId++
        engine.listener = createProgressListener()
        // 连续入队：从目标段起把剩余段一次性入队
        engine.stop()
        speakFrom(engine, stateMachine.position.segmentIndex)
    }

    private fun handlePrevious() {
        if (activeEngine == SpeechEngineType.KOKORO) {
            handleKokoroPrevious()
            return
        }
        if (chapterJob != null || waitingChapter != null) return
        val engine = tts ?: return
        stateMachine.previous().getOrNull() ?: return
        sessionId++
        engine.listener = createProgressListener()
        engine.stop()
        speakFrom(engine, stateMachine.position.segmentIndex)
    }

    private fun handleStop(error: String? = null) {
        if (error != null) SpeechDiagnosticsState.update { it.copy(lastError = error) }
        if (activeEngine == SpeechEngineType.KOKORO) {
            handleKokoroStop(error)
            return
        }
        sessionId++
        playGeneration++
        playJob?.cancel()
        stopWatchdog()
        clearChapterTransition()
        request?.let { SpeechPlanStore.clear(it) }
        request = null
        tts?.stop()
        stateMachine.stop()
        SpeechProgressTracker.clear()
        releaseAudioFocus()
        releaseWakeLock()
        persistScope.launch { runCatching { progressWriter.flush() } }
        updatePlaybackState(
            if (error == null) PlaybackState.STATE_STOPPED else PlaybackState.STATE_ERROR,
            errorMessage = error,
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun ensureEngine(): TtsEngine? {
        val existing = tts
        if (existing != null) return existing
        val engine = TtsEngine(this)
        return try {
            engine.initialize()
            tts = engine
            SpeechVoices.publish(engine.availableVoices())
            engine
        } catch (e: CancellationException) {
            engine.shutdown()
            throw e
        } catch (e: Exception) {
            engine.shutdown()
            SpeechVoices.clear()
            updatePlaybackState(PlaybackState.STATE_ERROR, errorMessage = "TTS 引擎初始化失败")
            null
        }
    }

    /** 记录从播放指令到首句真正开始发声的延迟（诊断用，每次会话只记一次）。 */
    private fun recordFirstSound() {
        if (firstSoundRecorded || firstSoundStartedAt <= 0L) return
        firstSoundRecorded = true
        val elapsed = SystemClock.elapsedRealtime() - firstSoundStartedAt
        SpeechDiagnosticsState.update { it.copy(firstSoundMs = elapsed) }
    }

    private fun utteranceIdFor(segmentIndex: Int) = "$sessionId:$segmentIndex"

    private fun createProgressListener() = object : UtteranceProgressListener() {
        private val mySession = sessionId

        override fun onStart(utteranceId: String?) {
            Log.d(TAG, "onStart utteranceId=$utteranceId, mySession=$mySession, curSession=$sessionId")
            dispatch(utteranceId) { confirm(it, 0) }
        }

        override fun onDone(utteranceId: String?) {
            Log.d(TAG, "onDone utteranceId=$utteranceId, mySession=$mySession, curSession=$sessionId")
            dispatch(utteranceId) { segment ->
                confirm(segment, stateMachine.plan?.segmentAt(segment)?.text?.length ?: 0)
                // 连续入队模型：仅当读完的是本章最后一段时才跨章；否则引擎会自行继续读队列中的下一段
                val isLast = stateMachine.plan?.let { segment >= it.segments.lastIndex } == true
                if (isLast) {
                    Log.d(TAG, "onDone 本章最后一段 segment=$segment, 触发跨章")
                    onSegmentDone()
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            Log.w(TAG, "onError utteranceId=$utteranceId, mySession=$mySession, curSession=$sessionId")
            dispatch(utteranceId) {
                handleStop("朗读片段失败")
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) =
            dispatch(utteranceId) { confirm(it, start) }

        private fun dispatch(utteranceId: String?, action: (Int) -> Unit) {
            scope.launch {
                if (mySession != sessionId || utteranceId == null) {
                    Log.w(TAG, "dispatch 丢弃回调: utteranceId=$utteranceId, mySession=$mySession, curSession=$sessionId, reason=${if (mySession != sessionId) "sessionId不一致" else "utteranceId为null"}")
                    return@launch
                }
                // 从 utteranceId 解析段号（格式 "sessionId:segmentIndex"），
                // 连续入队模型下回调段号不依赖当前游标，直接解析更可靠。
                val segment = parseSegment(utteranceId)
                if (segment >= 0) {
                    action(segment)
                } else {
                    Log.w(TAG, "dispatch 无法解析段号: utteranceId=$utteranceId")
                }
            }
        }

        private fun parseSegment(utteranceId: String): Int {
            val idx = utteranceId.lastIndexOf(':')
            if (idx < 0) return -1
            return utteranceId.substring(idx + 1).toIntOrNull() ?: -1
        }

        private fun confirm(segment: Int, charOffset: Int) {
            recordFirstSound()
            stateMachine.onProgress(segment, charOffset)
            val plan = stateMachine.plan ?: return
            val currentSegment = plan.segmentAt(segment)
            SpeechProgressTracker.onProgress(
                bookId = plan.bookId,
                chapterIndex = plan.chapterIndex,
                segmentIndex = segment,
                charOffset = charOffset,
                anchor = currentSegment?.anchorAt(charOffset),
                endAnchor = currentSegment?.endAnchor,
            )
            progressWriter.onProgress(
                bookId = plan.bookId,
                fileGeneration = plan.fileGeneration,
                chapterIndex = plan.chapterIndex,
                segmentIndex = segment,
                // 有稳定锚点时写节点内偏移，供锚点重映射；否则保留片段内偏移。
                charOffset = currentSegment?.anchorAt(charOffset)?.offset ?: charOffset,
                anchorNodeId = currentSegment?.anchorAt(charOffset)?.nodeId ?: "",
                plannerVersion = plan.plannerVersion,
            )
        }
    }

    private fun onSegmentDone() {
        Log.d(TAG, "onSegmentDone() 进入, state=${stateMachine.state}, 当前segment=${stateMachine.position.segmentIndex}")
        stateMachine.plan?.let {
            Log.d(TAG, "D_CHAPTER_DONE engine=SYSTEM book=${it.bookId} chapter=${it.chapterIndex}")
        }
        val engine = tts ?: run {
            Log.w(TAG, "onSegmentDone() engine 为空, 中断续播!")
            return
        }
        // 连续入队模型下，onSegmentDone 仅在「整章读完（末句 onDone）」时被调用，语义为跨章。
        switchToNextChapter(engine)
    }

    /** 开始读当前章时预取下一章到 [waitingChapter]，使跨章切换不被拉取耗时卡住。 */
    private fun prefetchNextChapter() {
        if (chapterJob != null || waitingChapter != null) return
        val active = request ?: return
        val previous = stateMachine.plan ?: return
        chapterJob = scope.launch {
            val next = try {
                active.chapters.next(previous)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "预取下一章失败", e)
                chapterJob = null
                return@launch
            }
            chapterJob = null
            waitingChapter = next
            Log.d(TAG, "预取下一章完成: ${next?.chapterIndex ?: "已是最后一章"}")
        }
    }

    /** 末句读完时切换到下一章：优先用已预取的 [waitingChapter]，否则即时拉取。 */
    private fun switchToNextChapter(engine: TtsEngine) {
        val waiting = waitingChapter
        if (waiting != null) {
            waitingChapter = null
            if (chapterPaused) {
                waitingChapter = waiting
            } else {
                playChapter(engine, waiting)
            }
            return
        }
        // 预取尚未完成，即时拉取（兜底）
        loadNextChapter(engine)
    }

    private fun loadNextChapter(engine: TtsEngine) {
        if (chapterJob != null) return
        val active = request ?: return handleStop()
        val previous = stateMachine.plan ?: return handleStop()
        updatePlaybackState(PlaybackState.STATE_BUFFERING)
        chapterJob = scope.launch {
            val next = try {
                active.chapters.next(previous)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("SpeechService", "无法准备下一章", e)
                chapterJob = null
                handleStop("无法继续朗读下一章")
                return@launch
            }
            chapterJob = null
            if (next == null) {
                handleStop()
            } else if (chapterPaused) {
                waitingChapter = next
            } else {
                playChapter(engine, next)
            }
        }
    }

    // ---- 唤醒锁 ----

    /** 朗读会话期间持有；覆盖句间合成空档。暂停/停止/错误立即释放。 */
    private fun acquireWakeLock() {
        val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yuezhou:SpeechService")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        if (!lock.isHeld) {
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            Log.d(TAG, "D_WAKELOCK acquire")
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        if (lock.isHeld) {
            runCatching { lock.release() }
            Log.d(TAG, "D_WAKELOCK release")
        }
    }

    // ---- 音频焦点 ----

    private fun requestAudioFocus(): Boolean {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        audioFocusGranted = granted
        registerBecomingNoisy()
        return granted
    }

    private fun releaseAudioFocus() {
        audioFocusGranted = false
        unregisterBecomingNoisy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
        audioFocusRequest = null
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.d(TAG, "音频焦点变化 change=$change (${focusChangeName(change)}) state=${stateMachine.state}")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> handlePause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> handlePause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { /* 朗读暂不降音量，保持继续 */ }
            AudioManager.AUDIOFOCUS_GAIN -> if (stateMachine.state == SpeechStateMachine.State.PAUSED) handleResume()
        }
    }

    private fun focusChangeName(change: Int): String = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
        AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
        else -> "UNKNOWN"
    }

    private fun registerBecomingNoisy() {
        if (becomingNoisyRegistered) return
        registerReceiver(
            becomingNoisyReceiver,
            android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        )
        becomingNoisyRegistered = true
    }

    private fun unregisterBecomingNoisy() {
        if (!becomingNoisyRegistered) return
        runCatching { unregisterReceiver(becomingNoisyReceiver) }
        becomingNoisyRegistered = false
    }

    private val becomingNoisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handlePause()
        }
    }

    // ---- MediaSession ----

    private val sessionCallback = object : MediaSession.Callback() {
        override fun onPlay() {
            when {
                chapterPaused || stateMachine.state == SpeechStateMachine.State.PAUSED -> handleResume()
                chapterJob == null && stateMachine.state != SpeechStateMachine.State.PLAYING -> handlePlay()
            }
        }

        override fun onPause() = handlePause()

        override fun onStop() = handleStop()

        override fun onSkipToNext() = handleNext()

        override fun onSkipToPrevious() = handlePrevious()
    }

    private fun updatePlaybackState(
        state: Int,
        segmentIndex: Int? = null,
        errorMessage: String? = null,
    ) {
        val builder = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
        if (errorMessage != null) builder.setErrorMessage(errorMessage)
        mediaSession?.setPlaybackState(builder.build())
        mediaSession?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, stateMachine.plan?.bookId ?: "")
                .build()
        )
    }

    // ---- 前台通知 ----

    private fun startForegroundInternal() {
        requestAudioFocus()
        // 标记会话为活跃媒体：锁屏后系统/厂商冻结策略据此保留后台播放进程。
        mediaSession?.isActive = true
        val notification = buildNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
        )
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, Class.forName("com.yuezhou.reader.MainActivity")),
            PendingIntent.FLAG_IMMUTABLE
        )
        val action = { act: String, label: String, icon: Int ->
            Notification.Action.Builder(
                Icon.createWithResource(this, icon),
                label,
                PendingIntent.getService(
                    this, act.hashCode(),
                    Intent(this, SpeechService::class.java).setAction(act),
                    PendingIntent.FLAG_IMMUTABLE
                )
            ).build()
        }
        val pauseAction = action(SpeechCommands.ACTION_PAUSE, "暂停", android.R.drawable.ic_media_pause)
        val playAction = action(SpeechCommands.ACTION_PLAY, "播放", android.R.drawable.ic_media_play)
        val nextAction = action(SpeechCommands.ACTION_NEXT, "下一句", android.R.drawable.ic_media_next)

        val style = Notification.MediaStyle()
            .setMediaSession(mediaSession?.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("阅舟朗读")
            .setContentText(stateMachine.plan?.bookId ?: "")
            .setContentIntent(openIntent)
            .addAction(playAction)
            .addAction(pauseAction)
            .addAction(nextAction)
            .setStyle(style)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "朗读", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "后台朗读控制" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionId++
        playGeneration++
        stopWatchdog()
        releaseWakeLock()
        scope.cancel()
        request?.let { SpeechPlanStore.clear(it) }
        SpeechProgressTracker.clear()
        releaseAudioFocus()
        tts?.shutdown()
        tts = null
        kokoroEventJob?.cancel()
        kokoroEventJob = null
        val coordinator = kokoroCoordinator
        kokoroCoordinator = null
        // Service.onDestroy 在主线程执行。不能阻塞等待尚未结束的 native 推理，
        // 也不能提前释放仍在使用的模型。独立清理任务负责等流水线结束再释放。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching { progressWriter.flush() }
                if (coordinator != null) {
                    runCatching { coordinator.release() }
                        .onFailure { Log.w(TAG, "Kokoro 销毁清理失败", it) }
                }
            } finally {
                kokoroScope.cancel()
                persistScope.cancel()
            }
        }
        SpeechVoices.clear()
        mediaSession?.release()
        mediaSession = null
    }

    companion object {
        private const val TAG = "SpeechService"
        private const val CHANNEL_ID = "speech"
        private const val NOTIFICATION_ID = 1001

        /** 看门狗轮询间隔（毫秒）。 */
        private const val WATCHDOG_INTERVAL_MS = 2000L

        /** 连续静默多少次才触发续播（约 2s × 3 = 6s 容忍，避免误判正常句间停顿）。 */
        private const val WATCHDOG_THRESHOLD = 3

        /** 唤醒锁超时（防泄漏）；正常朗读由会话内的暂停/停止释放。 */
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L

        /**
         * Kokoro 无播放进展的停滞阈值：播放头不动且无事件超过该值即停止。
         * 需大于 256 字片段的合成与播放时间，避免长句被误判。
         */
        private const val KOKORO_STALL_TIMEOUT_MS = 120_000L
    }
}
