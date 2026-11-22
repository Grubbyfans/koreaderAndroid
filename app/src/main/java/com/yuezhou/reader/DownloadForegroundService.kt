package com.yuezhou.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 下载保活前台服务。
 *
 * 下载书时切到桌面/锁屏，Android（尤其国产 ROM 省电策略）会冻结后台进程的网络与 CPU，
 * 导致 OkHttp 的 readTimeout 触发、下载报 timeout 失败。通过前台服务把进程抬到前台优先级，
 * 并展示一个常驻进度通知，系统就不会冻结下载。
 *
 * 本服务自身不执行下载，只负责「保活 + 进度通知」；真正下载仍由 [MainActivity] 的
 * lifecycleScope 协程执行。下载结束/失败/取消时由调用方 stop。
 */
class DownloadForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(intent)
        // 切后台后仍需要网络：普通前台服务 + dataSync 类型（Android 14 起要求声明类型）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "下载任务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "书籍下载进度"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(intent: Intent?): Notification {
        val fileName = intent?.getStringExtra(EXTRA_FILE_NAME).orEmpty()
        val received = intent?.getLongExtra(EXTRA_RECEIVED, 0L) ?: 0L
        val total = intent?.getLongExtra(EXTRA_TOTAL, 0L) ?: 0L

        // 点击通知回到书库
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (fileName.isEmpty()) "正在下载书籍" else fileName)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        return if (total > 0) {
            builder.setContentText("$received / $total")
                .setProgress(total.toInt(), received.toInt().coerceAtMost(total.toInt()), false)
                .build()
        } else {
            builder.setContentText("下载中…").setProgress(0, 0, true).build()
        }
    }

    companion object {
        private const val CHANNEL_ID = "download"
        private const val NOTIFICATION_ID = 1001

        private const val EXTRA_FILE_NAME = "file_name"
        private const val EXTRA_RECEIVED = "received"
        private const val EXTRA_TOTAL = "total"

        /** 启动（或更新）下载前台服务，使下载在后台不被冻结。 */
        fun start(context: Context, fileName: String) {
            val intent = Intent(context, DownloadForegroundService::class.java)
                .putExtra(EXTRA_FILE_NAME, fileName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 更新下载进度通知。 */
        fun updateProgress(context: Context, fileName: String, received: Long, total: Long) {
            val intent = Intent(context, DownloadForegroundService::class.java)
                .putExtra(EXTRA_FILE_NAME, fileName)
                .putExtra(EXTRA_RECEIVED, received)
                .putExtra(EXTRA_TOTAL, total)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 停止下载前台服务并移除通知。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadForegroundService::class.java))
        }
    }
}
