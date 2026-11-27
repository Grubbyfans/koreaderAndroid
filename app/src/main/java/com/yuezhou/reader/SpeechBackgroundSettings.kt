package com.yuezhou.reader

import androidx.compose.ui.unit.dp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private fun notificationsAllowed(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun batteryUnrestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

private fun notificationSettings(context: Context) = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

private fun batterySettings(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    .setData(Uri.parse("package:${context.packageName}"))

/** 从用户点击朗读开始引导，通知授权结果返回后才继续，不阻塞主线程。 */
@Composable
internal fun rememberBackgroundSpeechStart(onStart: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val latestStart by rememberUpdatedState(onStart)
    val guidePrefs = remember(context) { context.getSharedPreferences("speech_background", Context.MODE_PRIVATE) }
    var notificationGuide by remember { mutableStateOf(false) }
    var batteryGuide by remember { mutableStateOf(false) }
    val continueStart = {
        if (!batteryUnrestricted(context) && !guidePrefs.getBoolean("battery_guide_seen", false)) {
            batteryGuide = true
        } else latestStart()
    }
    val latestContinue by rememberUpdatedState(continueStart)
    val notificationSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (notificationsAllowed(context)) latestContinue() else notificationGuide = true
    }
    val batterySettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        latestStart()
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (notificationsAllowed(context)) latestContinue() else notificationGuide = true
    }
    if (notificationGuide) {
        AlertDialog(
            onDismissRequest = { notificationGuide = false },
            title = { Text("允许朗读通知") },
            text = { Text("锁屏朗读需要显示播放通知。关闭通知时，部分手机可能暂停后台朗读。可前往设置开启，也可以继续本次朗读。") },
            confirmButton = { TextButton(onClick = {
                notificationGuide = false
                notificationSettingsLauncher.launch(notificationSettings(context))
            }) { Text("通知设置") } },
            dismissButton = { TextButton(onClick = { notificationGuide = false; latestContinue() }) { Text("继续朗读") } },
        )
    }
    if (batteryGuide) {
        fun acknowledge() { guidePrefs.edit().putBoolean("battery_guide_seen", true).apply(); batteryGuide = false }
        AlertDialog(
            onDismissRequest = { batteryGuide = false },
            title = { Text("后台朗读设置") },
            text = { Text("长时间锁屏朗读时，建议在应用信息的「电池」中选择「不受限制」。不同手机的选项可能不同；设置后返回即可开始朗读。") },
            confirmButton = { TextButton(onClick = {
                acknowledge()
                batterySettingsLauncher.launch(batterySettings(context))
            }) { Text("打开电池设置") } },
            dismissButton = { TextButton(onClick = { acknowledge(); latestStart() }) { Text("直接朗读") } },
        )
    }
    return {
        when {
            notificationsAllowed(context) -> continueStart()
            Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> notificationGuide = true
        }
    }
}

/** 设置页提供随时可回到的入口，返回应用后刷新系统状态。 */
@Composable
internal fun SpeechBackgroundSettings() {
    val context = LocalContext.current
    var notifications by remember { mutableStateOf(notificationsAllowed(context)) }
    var unrestricted by remember { mutableStateOf(batteryUnrestricted(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifications = notificationsAllowed(context)
                unrestricted = batteryUnrestricted(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Column {
        Text(if (notifications) "朗读通知已允许" else "朗读通知未允许")
        Text(
            if (unrestricted) "系统电池优化已豁免" else "锁屏后若朗读中断，请将应用电池用量设为「不受限制」。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.OutlinedButton(onClick = { context.startActivity(notificationSettings(context)) }) { Text("通知设置") }
            androidx.compose.material3.OutlinedButton(onClick = { context.startActivity(batterySettings(context)) }) { Text("电池设置") }
        }
    }
}
