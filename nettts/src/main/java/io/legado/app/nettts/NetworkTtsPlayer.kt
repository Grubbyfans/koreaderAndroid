package io.legado.app.nettts

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 网络 TTS 独立播放链路 —— 队列版（阶段 C/D）。
 *
 * 线程模型（关键）：ExoPlayer **所有 public 方法都必须在主线程**调用。
 * 因此所有 public API 内部都 `withContext(Dispatchers.Main.immediate)`，
 * 调用方（可能在 IO / DefaultDispatcher worker）无需关心线程。
 */
class NetworkTtsPlayer(
    context: Context,
    cacheDir: File,
) {

    companion object {
        const val MAX_QUEUE_SIZE = 12
        const val CACHE_MAX_BYTES = 128L * 1024 * 1024

        /** SimpleCache 全局单例 —— SimpleCache 严格禁止两个实例指向同目录。 */
        @Volatile
        private var sharedSimpleCache: SimpleCache? = null

        /** 获取/创建 SimpleCache 单例；多线程安全。
         * 遇到磁盘目录损坏（旧版本残留坏条目）时自动清目录重建。 */
        fun acquireSimpleCache(context: Context, cacheDir: File): SimpleCache {
            sharedSimpleCache?.let { return it }
            synchronized(this) {
                sharedSimpleCache?.let { return it }
                val dir = File(cacheDir, "exo_tts_cache")
                // 先试探：如果目录存在且非空，检查 metadata 是否可读
                if (dir.exists() && dir.listFiles()?.isNotEmpty() == true) {
                    runCatching {
                        val probe = SimpleCache(
                            dir,
                            LeastRecentlyUsedCacheEvictor(64L * 1024 * 1024),
                            StandaloneDatabaseProvider(context.applicationContext),
                        )
                        // 这个 release 只是试探用，真要用的是下面正式创建的实例
                        probe.release()
                    }.onFailure {
                        android.util.Log.w("NetworkTtsPlayer", "SimpleCache dir corrupted, wiping: ${dir.absolutePath}", it)
                        dir.deleteRecursively()
                    }
                }
                dir.mkdirs()
                val created = SimpleCache(
                    dir,
                    LeastRecentlyUsedCacheEvictor(CACHE_MAX_BYTES),
                    StandaloneDatabaseProvider(context.applicationContext),
                )
                sharedSimpleCache = created
                return created
            }
        }

        /** 测试/进程退出时释放单例（通常只在 release() 最后一个 Player 时调用）。 */
        fun releaseSimpleCache() {
            synchronized(this) {
                sharedSimpleCache?.let { runCatching { it.release() } }
                sharedSimpleCache = null
            }
        }
    }

    sealed interface PlayerEvent {
        data class PlayerSegmentStarted(val index: Int) : PlayerEvent
        data class PlayerSegmentCompleted(val index: Int) : PlayerEvent
        data object PlayerFinished : PlayerEvent
        data class PlayerError(val reason: String) : PlayerEvent
    }

    data class State(
        val playing: Boolean = false,
        val currentIndex: Int = 0,
        val totalCount: Int = 0,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val error: String? = null,
        val finished: Boolean = false,
    )

    private val appContext = context.applicationContext

    /** TTS 音频全部是本地 File，直接用 DefaultDataSource 播，不经过 SimpleCache。
     * SimpleCache 历史上多次因磁盘坏条目崩在 getContentMetadata，这里彻底移除。 */
    private val localDataSourceFactory = DefaultDataSource.Factory(appContext)

    private val mediaSourceFactory: DefaultMediaSourceFactory by lazy {
        DefaultMediaSourceFactory(localDataSourceFactory)
    }

    private val player: ExoPlayer by lazy {
        ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply { addListener(playerListener) }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    private val itemSegmentIndex = mutableListOf<Int?>()
    private val started = mutableSetOf<Int>()
    private val completed = mutableSetOf<Int>()
    private var previousSegmentIndex: Int? = null
    private var prepared = false

    private val playerListener = object : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(playing = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    _state.update {
                        it.copy(
                            durationMs = player.duration.coerceAtLeast(0L),
                            totalCount = player.mediaItemCount,
                        )
                    }
                }
                Player.STATE_ENDED -> {
                    val lastIdx = itemSegmentIndex.lastOrNull()
                    if (lastIdx != null && completed.add(lastIdx)) {
                        _events.tryEmit(PlayerEvent.PlayerSegmentCompleted(lastIdx))
                    }
                    _events.tryEmit(PlayerEvent.PlayerFinished)
                    _state.update {
                        it.copy(playing = false, finished = true, positionMs = it.durationMs)
                    }
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val prev = previousSegmentIndex
            if (prev != null && completed.add(prev)) {
                _events.tryEmit(PlayerEvent.PlayerSegmentCompleted(prev))
            }
            val currentItemIndex = player.currentMediaItemIndex
            val seg = itemSegmentIndex.getOrNull(currentItemIndex)
            previousSegmentIndex = seg
            if (seg != null && started.add(seg)) {
                _events.tryEmit(PlayerEvent.PlayerSegmentStarted(seg))
            }
            _state.update {
                it.copy(
                    currentIndex = currentItemIndex,
                    positionMs = 0,
                    durationMs = 0,
                    finished = false,
                )
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            _events.tryEmit(PlayerEvent.PlayerError(error.message ?: "播放失败"))
            _state.update { it.copy(playing = false, error = error.message ?: "播放失败") }
        }
    }

    /** 以下所有 public API 都 withContext(Main) 保证线程安全。 */

    suspend fun size(): Int = withContext(Dispatchers.Main.immediate) { player.mediaItemCount }

    suspend fun isFull(): Boolean = withContext(Dispatchers.Main.immediate) { player.mediaItemCount >= MAX_QUEUE_SIZE }

    suspend fun add(item: MediaItem, segmentIndex: Int): Boolean = withContext(Dispatchers.Main.immediate) {
        // 已播放条目不再占队列容量，暂停时保留当前项及其后续缓冲。
        val playedCount = player.currentMediaItemIndex.coerceAtMost(itemSegmentIndex.size)
        if (playedCount > 0) {
            itemSegmentIndex.subList(0, playedCount).clear()
            player.removeMediaItems(0, playedCount)
        }
        if (player.mediaItemCount >= MAX_QUEUE_SIZE) return@withContext false
        val wasEnded = player.playbackState == Player.STATE_ENDED
        itemSegmentIndex.add(segmentIndex)
        player.addMediaItem(item)
        if (wasEnded) player.seekToDefaultPosition(player.mediaItemCount - 1)
        if (!prepared) {
            prepared = true
            player.prepare()
        }
        true
    }

    suspend fun addSilence(durationMs: Int, segmentIndex: Int): Boolean = withContext(Dispatchers.Main.immediate) {
        if (player.mediaItemCount >= MAX_QUEUE_SIZE) return@withContext false
        itemSegmentIndex.add(null)
        player.addMediaItem(MediaItem.fromUri(silentWavFile(durationMs).toURI().toString()))
        if (!prepared) {
            prepared = true
            player.prepare()
        }
        true
    }

    suspend fun setAll(files: List<File>, segmentIndex: () -> Int, silenceMs: Int) {
        withContext(Dispatchers.Main.immediate) {
            player.stop()
            player.clearMediaItems()
            itemSegmentIndex.clear()
            started.clear()
            completed.clear()
            previousSegmentIndex = null
            prepared = false
        }
        val items = ArrayList<MediaItem>(files.size * 2)
        val segmentMap = ArrayList<Int?>(files.size * 2)
        files.forEachIndexed { idx, file ->
            items.add(MediaItem.fromUri(file.toURI().toString()))
            segmentMap.add(segmentIndex())
            if (silenceMs > 0) {
                items.add(MediaItem.fromUri(silentWavFile(silenceMs).toURI().toString()))
                segmentMap.add(null)
            }
        }
        withContext(Dispatchers.Main.immediate) {
            itemSegmentIndex.addAll(segmentMap)
            player.setMediaItems(items)
            prepared = true
            player.prepare()
            _state.value = State(totalCount = items.size, finished = false, error = null)
            player.playWhenReady = true
        }
    }

    suspend fun play(files: List<File>, silenceMs: Int) {
        var seg = 0
        setAll(files, { seg++ }, silenceMs)
    }

    suspend fun start() = withContext(Dispatchers.Main.immediate) { player.playWhenReady = true }

    suspend fun pause() = withContext(Dispatchers.Main.immediate) {
        player.playWhenReady = false
        player.pause()
        _state.update { it.copy(playing = false) }
    }

    /** 暂停 + 截断后续队列，只保留当前正在播的 item（防止"读完这一段才停"的尾巴）。 */
    suspend fun pauseAndTruncate() = withContext(Dispatchers.Main.immediate) {
        val currentIdx = player.currentMediaItemIndex
        player.playWhenReady = false
        player.pause()
        // 只保留当前播放项；如果已经播完（currentIdx 超出范围）就全部清空
        if (currentIdx in itemSegmentIndex.indices && player.mediaItemCount > 1) {
            val keepCount = 1
            val removeFrom = currentIdx + 1
            player.removeMediaItems(removeFrom, player.mediaItemCount)
            // 同步更新 segment 映射
            if (removeFrom < itemSegmentIndex.size) {
                itemSegmentIndex.subList(removeFrom, itemSegmentIndex.size).clear()
            }
        } else if (currentIdx >= itemSegmentIndex.size) {
            player.clearMediaItems()
            itemSegmentIndex.clear()
        }
        _state.update { it.copy(playing = false, totalCount = player.mediaItemCount) }
    }

    suspend fun resume() = withContext(Dispatchers.Main.immediate) {
        if (!player.playWhenReady) player.playWhenReady = true
        player.play()
    }

    suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        player.stop()
        player.clearMediaItems()
        itemSegmentIndex.clear()
        started.clear()
        completed.clear()
        previousSegmentIndex = null
        prepared = false
        _state.value = State()
    }

    suspend fun clear() = withContext(Dispatchers.Main.immediate) {
        player.stop()
        player.clearMediaItems()
        itemSegmentIndex.clear()
        started.clear()
        completed.clear()
        previousSegmentIndex = null
        prepared = false
        _state.value = State()
    }

    suspend fun positionMs(): Long = withContext(Dispatchers.Main.immediate) { player.currentPosition }

    suspend fun release() = withContext(Dispatchers.Main.immediate) {
        player.release()
    }

    /** 清空磁盘缓存（silence WAV + audio 子目录）。 */
    fun clearCache(): Int {
        var removed = 0
        silencesDir.listFiles()?.forEach { if (it.delete()) removed++ }
        val audioDir = File(silencesDir.parentFile ?: return removed, "audio")
        audioDir.walkBottomUp().forEach { f -> if (f.isFile && f.delete()) removed++ }
        return removed
    }

    private val silencesDir: File by lazy {
        File(cacheDir, "silence").apply { mkdirs() }
    }

    fun silentWavFile(durationMs: Int): File {
        val duration = durationMs.coerceIn(0, 10_000)
        val file = File(silencesDir, "silence_$duration.wav")
        if (file.exists() && file.length() > 44) return file
        file.writeBytes(generateSilentWavBytes(duration))
        return file
    }

    fun generateSilentWavBytes(durationMs: Int): ByteArray {
        val duration = durationMs.coerceIn(0, 10_000)
        val sampleRate = 24_000
        val channelCount = 1
        val bitsPerSample = 16
        val bytesPerSample = bitsPerSample / 8
        val dataSize = sampleRate * channelCount * bytesPerSample * duration / 1000
        return ByteBuffer.allocate(44 + dataSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + dataSize)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1)
                putShort(channelCount.toShort())
                putInt(sampleRate)
                putInt(sampleRate * channelCount * bytesPerSample)
                putShort((channelCount * bytesPerSample).toShort())
                putShort(bitsPerSample.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataSize)
            }
            .array()
    }
}
