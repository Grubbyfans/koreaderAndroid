package com.yuezhou.reader.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import com.yuezhou.reader.core.speech.PcmPlayer
import com.yuezhou.reader.core.speech.SynthesizedAudio
import kotlin.math.max
import kotlin.math.min

/**
 * 单 AudioTrack + 累计帧边界表的 PCM 播放器（技术方案 §6）。
 *
 * - 每次 [begin] 重建 track，避免残留缓冲污染新代次；
 * - 每句写入后记录「累计帧终点」，用通知 marker 在播放头越过该句终点时回调完成；
 * - 另有 100 ms 轮询线程校验 `playbackHeadPosition`，marker 丢失时同一代次补发完成，
 *   并处理 32 位播放头回绕；两者共用边界表，保证每句 [PcmPlayer.CompletionListener] 恰好一次；
 * - [pause] 保留帧位与缓冲，[resume] 继续；[stop] 释放 track 并清空边界；
 * - 读写线程与回调线程通过 [lock] 串行化，回调在锁外发出。
 */
class AudioTrackPcmPlayer : PcmPlayer {

    private val lock = Any()
    private var listener: PcmPlayer.CompletionListener? = null

    private var track: AudioTrack? = null
    private var sampleRate = 0
    private var generation = -1L
    private var paused = false

    private var writtenFrames = 0L
    private var wrapBase = 0L
    private var lastRawHead = 0
    private var markerArmed = false
    private val boundaries = ArrayDeque<Boundary>()

    private val markerThread = HandlerThread("PcmPlayerMarker").apply { start() }
    private val markerHandler = Handler(markerThread.looper)

    @Volatile private var alive = true
    private val monitorThread = Thread(::monitorLoop, "PcmPlayerMonitor").apply {
        isDaemon = true
        start()
    }

    private val markerListener = object : AudioTrack.OnPlaybackPositionUpdateListener {
        override fun onMarkerReached(track: AudioTrack?) = pollAndNotify()

        override fun onPeriodicNotification(track: AudioTrack?) = pollAndNotify()
    }

    override fun setCompletionListener(listener: PcmPlayer.CompletionListener?) {
        synchronized(lock) { this.listener = listener }
    }

    override fun begin(generation: Long) {
        val old: AudioTrack?
        synchronized(lock) {
            old = releaseTrackLocked()
            this.generation = generation
            writtenFrames = 0
            wrapBase = 0
            lastRawHead = 0
            markerArmed = false
            boundaries.clear()
            paused = false
        }
        old?.let { runCatching { it.release() } }
    }

    override fun enqueue(segmentIndex: Int, audio: SynthesizedAudio): Boolean {
        val samples = audio.samples
        if (samples.isEmpty() || audio.sampleRate <= 0) return false
        val current: AudioTrack
        val gen: Long
        synchronized(lock) {
            if (generation < 0L) return false
            val existing = track
            current = if (existing == null) {
                createTrackLocked(audio.sampleRate) ?: return false
            } else {
                if (sampleRate != audio.sampleRate) return false
                existing
            }
            gen = generation
        }

        var offset = 0
        try {
            while (offset < samples.size) {
                val n = current.write(
                    samples,
                    offset,
                    min(WRITE_CHUNK_SAMPLES, samples.size - offset),
                    AudioTrack.WRITE_BLOCKING,
                )
                if (n < 0) return false
                offset += n
            }
        } catch (_: IllegalStateException) {
            return false
        }

        synchronized(lock) {
            if (track !== current || generation != gen) return false
            writtenFrames += samples.size
            boundaries.addLast(Boundary(segmentIndex, writtenFrames))
            armMarkerLocked()
        }
        return true
    }

    override fun pause() {
        synchronized(lock) {
            paused = true
            runCatching { track?.pause() }
        }
    }

    override fun resume() {
        synchronized(lock) {
            paused = false
            runCatching { track?.play() }
        }
    }

    override fun stop() {
        val old: AudioTrack?
        synchronized(lock) {
            old = releaseTrackLocked()
            generation = -1L
            writtenFrames = 0
            wrapBase = 0
            lastRawHead = 0
            markerArmed = false
            paused = false
            boundaries.clear()
        }
        old?.let { t ->
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.stop() }
            runCatching { t.release() }
        }
    }

    override fun release() {
        stop()
        alive = false
        synchronized(lock) { listener = null }
        markerHandler.removeCallbacksAndMessages(null)
        markerThread.quitSafely()
        monitorThread.interrupt()
    }

    override fun positionMs(): Long {
        synchronized(lock) {
            val rate = sampleRate
            if (track == null || rate <= 0) return -1L
            return currentFramesLocked() * 1000L / rate
        }
    }

    private fun createTrackLocked(rate: Int): AudioTrack? {
        val minBytes = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBytes <= 0) return null
        val created = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBytes * 2, MIN_BUFFER_BYTES))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: UnsupportedOperationException) {
            return null
        }
        created.setPlaybackPositionUpdateListener(markerListener, markerHandler)
        track = created
        sampleRate = rate
        if (!paused) created.play()
        return created
    }

    private fun releaseTrackLocked(): AudioTrack? {
        val t = track
        track = null
        sampleRate = 0
        return t
    }

    private fun pollAndNotify() {
        val completed: List<Boundary>
        synchronized(lock) {
            if (track == null) return
            completed = drainLocked()
        }
        notifyCompleted(completed)
    }

    private fun drainLocked(): List<Boundary> {
        if (boundaries.isEmpty()) return emptyList()
        val frames = currentFramesLocked()
        val done = ArrayList<Boundary>(2)
        while (boundaries.isNotEmpty() && boundaries.first().endFrame <= frames) {
            done.add(boundaries.removeFirst())
            markerArmed = false
        }
        armMarkerLocked()
        return done
    }

    /** 把通知 marker 指向最早尚未完成的边界；要求该边界的 PCM 已全部写入。 */
    private fun armMarkerLocked() {
        val t = track ?: return
        if (markerArmed) return
        val first = boundaries.firstOrNull() ?: return
        if (first.endFrame > writtenFrames) return
        if (first.endFrame <= currentFramesLocked()) return
        val wrapped = first.endFrame - wrapBase
        if (wrapped >= WRAP_LIMIT) return
        runCatching { t.setNotificationMarkerPosition(wrapped.toInt()) }
            .onSuccess { markerArmed = true }
    }

    private fun currentFramesLocked(): Long {
        val t = track ?: return wrapBase + lastRawHead
        val raw = t.playbackHeadPosition
        if (raw < lastRawHead && lastRawHead - raw > WRAP_DETECT_THRESHOLD) {
            wrapBase += WRAP_LIMIT
        }
        lastRawHead = raw
        return wrapBase + raw
    }

    private fun notifyCompleted(completed: List<Boundary>) {
        if (completed.isEmpty()) return
        val callback: PcmPlayer.CompletionListener?
        val gen: Long
        synchronized(lock) {
            callback = listener
            gen = generation
        }
        val target = callback ?: return
        for (b in completed) target.onCompleted(b.index, gen)
    }

    private fun monitorLoop() {
        while (alive) {
            try {
                Thread.sleep(MONITOR_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (!alive) return
            val completed: List<Boundary>
            synchronized(lock) {
                if (track == null) continue
                completed = drainLocked()
            }
            notifyCompleted(completed)
        }
    }

    private data class Boundary(val index: Int, val endFrame: Long)

    private companion object {
        const val MIN_BUFFER_BYTES = 32 * 1024
        const val WRITE_CHUNK_SAMPLES = 4096
        const val MONITOR_INTERVAL_MS = 100L
        const val WRAP_LIMIT = 1L shl 31
        const val WRAP_DETECT_THRESHOLD = 1L shl 30
    }
}
