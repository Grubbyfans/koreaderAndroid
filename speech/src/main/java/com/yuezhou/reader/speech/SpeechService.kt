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
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.ServiceCompat
import com.yuezhou.reader.core.speech.SpeechPlan
import com.yuezhou.reader.core.speech.SpeechSegment
import com.yuezhou.reader.core.speech.SpeechStateMachine
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
 */
class SpeechService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var audioManager: AudioManager
    private var tts: TtsEngine? = null
    private val stateMachine = SpeechStateMachine()
    private var request: SpeechPlanStore.Request? = null
    private var playJob: Job? = null
    private var chapterJob: Job? = null
    private var waitingChapter: SpeechPlan? = null
    private var chapterPaused = false

    private var mediaSession: MediaSession? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusGranted = false

    /** 每次开始朗读递增的会话 ID；旧回调据此作废。 */
    private var sessionId = 0L

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
        tts?.stop()
        playJob = scope.launch {
            val engine = ensureEngine() ?: return@launch handleStop("TTS 引擎初始化失败")
            applyConfig(engine)
            playChapter(engine, nextRequest.plan)
        }
    }

    private fun playChapter(engine: TtsEngine, plan: SpeechPlan) {
        stateMachine.load(plan)
        sessionId++
        engine.listener = createProgressListener()
        speakCurrent(engine)
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
        if (chapterJob != null || waitingChapter != null) chapterPaused = true
        sessionId++
        tts?.stop()
        stateMachine.pause()
        updatePlaybackState(PlaybackState.STATE_PAUSED)
    }

    private fun handleResume() {
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
        val segment = stateMachine.resume().getOrNull() ?: return
        sessionId++
        engine.listener = createProgressListener()
        speak(engine, segment)
    }

    private fun handleNext() {
        if (chapterJob != null || waitingChapter != null) return
        val engine = tts ?: return
        val plan = stateMachine.plan ?: return
        if (stateMachine.position.segmentIndex >= plan.segments.lastIndex) {
            sessionId++
            engine.stop()
            loadNextChapter(engine)
            return
        }
        val segment = stateMachine.next().getOrNull() ?: return
        sessionId++
        engine.listener = createProgressListener()
        speak(engine, segment)
    }

    private fun handlePrevious() {
        if (chapterJob != null || waitingChapter != null) return
        val engine = tts ?: return
        val segment = stateMachine.previous().getOrNull() ?: return
        sessionId++
        engine.listener = createProgressListener()
        speak(engine, segment)
    }

    private fun handleStop(error: String? = null) {
        sessionId++
        playJob?.cancel()
        clearChapterTransition()
        request?.let { SpeechPlanStore.clear(it) }
        request = null
        tts?.stop()
        stateMachine.stop()
        SpeechProgressTracker.clear()
        releaseAudioFocus()
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

    private fun speakCurrent(engine: TtsEngine) {
        val segment = stateMachine.play().getOrNull() ?: return
        speak(engine, segment)
    }

    private fun speak(engine: TtsEngine, segment: SpeechSegment) {
        if (segment.text.isBlank()) {
            onSegmentDone()
            return
        }
        val ok = engine.speak(segment.text, utteranceIdFor(stateMachine.position.segmentIndex))
        if (!ok) {
            handleStop("TTS 合成失败")
            return
        }
        updatePlaybackState(PlaybackState.STATE_PLAYING, segmentIndex = stateMachine.position.segmentIndex)
    }

    private fun utteranceIdFor(segmentIndex: Int) = "$sessionId:$segmentIndex"

    private fun createProgressListener() = object : UtteranceProgressListener() {
        private val mySession = sessionId

        override fun onStart(utteranceId: String?) = dispatch(utteranceId) { confirm(it, 0) }

        override fun onDone(utteranceId: String?) = dispatch(utteranceId) { segment ->
            confirm(segment, stateMachine.plan?.segmentAt(segment)?.text?.length ?: 0)
            onSegmentDone()
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = dispatch(utteranceId) {
            handleStop("朗读片段失败")
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) =
            dispatch(utteranceId) { confirm(it, start) }

        private fun dispatch(utteranceId: String?, action: (Int) -> Unit) {
            scope.launch {
                if (mySession != sessionId || utteranceId == null) return@launch
                val segment = stateMachine.position.segmentIndex
                if (utteranceId == utteranceIdFor(segment)) action(segment)
            }
        }

        private fun confirm(segment: Int, charOffset: Int) {
            stateMachine.onProgress(segment, charOffset)
            val plan = stateMachine.plan ?: return
            SpeechProgressTracker.onProgress(
                bookId = plan.bookId,
                segmentIndex = segment,
                charOffset = charOffset,
                anchor = plan.segmentAt(segment)?.anchorAt(charOffset),
            )
        }
    }

    private fun onSegmentDone() {
        val engine = tts ?: return
        val next = stateMachine.advanceToNext()
        if (next == null) loadNextChapter(engine) else speak(engine, next)
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
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> handlePause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> handlePause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { /* 朗读暂不降音量，保持继续 */ }
            AudioManager.AUDIOFOCUS_GAIN -> if (stateMachine.state == SpeechStateMachine.State.PAUSED) handleResume()
        }
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
        scope.cancel()
        request?.let { SpeechPlanStore.clear(it) }
        SpeechProgressTracker.clear()
        releaseAudioFocus()
        tts?.shutdown()
        tts = null
        SpeechVoices.clear()
        mediaSession?.release()
        mediaSession = null
    }

    companion object {
        private const val CHANNEL_ID = "speech"
        private const val NOTIFICATION_ID = 1001
    }
}
