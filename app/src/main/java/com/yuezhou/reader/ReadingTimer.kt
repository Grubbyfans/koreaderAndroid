package com.yuezhou.reader

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.yuezhou.reader.reader.db.*
import com.yuezhou.reader.reader.ReadingStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** 单实例计时，单写入队列；单调时钟抵御系统时间调整。 */
internal class ReadingTimer(context: Context, private val bookId: String) {
    private val dao = ReadingStore.dao(context)
    private var ready = false
    private var foreground = false
    private var lastActive = 0L
    private var tick = 0L
    private var wall = 0L
    private var progress: Double? = null
    private var startProgress: Double? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pulse = object : Runnable {
        override fun run() { flush(); if (foreground && ready) handler.postDelayed(this, 30_000) }
    }

    fun opened() {
        if (ready) return
        ready = true
        val now = System.currentTimeMillis()
        enqueue { dao.insertProfile(ReadingProfile(bookId, firstRead = now, lastRead = now)); dao.begin(bookId, now) }
        if (foreground) start()
    }
    fun foreground(value: Boolean) {
        if (foreground == value) return
        if (!value) flush()
        foreground = value
        handler.removeCallbacks(pulse)
        if (value && ready) start()
    }
    private fun start() {
        lastActive = SystemClock.elapsedRealtime()
        tick = lastActive
        wall = System.currentTimeMillis()
        startProgress = progress
        handler.removeCallbacks(pulse)
        handler.postDelayed(pulse, 30_000)
    }
    fun interaction() {
        if (!ready || !foreground) return
        if (SystemClock.elapsedRealtime() - lastActive >= 300_000) flush()
        lastActive = SystemClock.elapsedRealtime()
    }
    fun position(value: Double?) { progress = value?.takeIf { it in 0.0..1.0 }; if (startProgress == null) startProgress = progress }
    private fun flush() {
        if (!ready || !foreground) return
        val now = SystemClock.elapsedRealtime()
        val duration = (minOf(now, lastActive + 300_000) - tick).coerceAtLeast(0)
        val initial = startProgress
        val final = progress
        var from = wall
        var remaining = duration
        val rows = mutableListOf<ReadingSession>()
        while (remaining > 0) {
            val midnight = Instant.ofEpochMilli(from).atZone(ZoneId.systemDefault()).toLocalDate()
                .plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val part = minOf(remaining, midnight - from).coerceAtLeast(1)
            // 跨日分段只在最后一段记录进度差，避免重复用于估算。
            rows += ReadingSession(UUID.randomUUID().toString(), bookId, from, from + part, part,
                if (remaining == part) initial else null, if (remaining == part) final else null)
            from += part
            remaining -= part
        }
        if (rows.isNotEmpty()) enqueue {
            for (row in rows) dao.saveSession(row)
            dao.touch(bookId, rows.last().endTime)
        }
        tick = now
        wall = System.currentTimeMillis()
        startProgress = progress
    }
    companion object {
        private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        init { scope.launch { for (write in writes) try { write() } catch (e: Exception) { Log.e("ReadingTimer", "阅读统计保存失败", e) } } }
        private fun enqueue(write: suspend () -> Unit) { check(writes.trySend(write).isSuccess) }
    }
}
