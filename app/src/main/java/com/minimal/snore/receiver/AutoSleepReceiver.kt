package com.minimal.snore.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.minimal.snore.R
import com.minimal.snore.SnoreApplication
import com.minimal.snore.data.AppSettings
import com.minimal.snore.service.SnoreMonitorService
import com.minimal.snore.ui.MainActivity

class AutoSleepReceiver : BroadcastReceiver() {

    companion object {
        const val PROMPT_NOTIFICATION_ID = 2002
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val settings = AppSettings.getInstance(context)

        when (action) {
            Intent.ACTION_POWER_CONNECTED -> {
                // Plugged in during night window
                if (settings.autoEnabled && settings.isInNightWindow()) {
                    handleNightTrigger(context, "检测到夜间入睡充电")
                }
            }

            Intent.ACTION_POWER_DISCONNECTED -> {
                // Unplugged in the morning
                dismissPromptNotification(context)
                if (settings.autoEnabled && SnoreMonitorService.isRunning.value) {
                    SnoreMonitorService.stop(context)
                }
            }

            AutoSleepScheduler.ACTION_BEDTIME -> {
                // Bedtime arrived
                if (settings.autoEnabled && !SnoreMonitorService.isRunning.value) {
                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    val isScreenOn = powerManager?.isInteractive ?: false

                    if (!isScreenOn) {
                        handleNightTrigger(context, "已到设定睡眠作息时间")
                    } else {
                        // User is still using phone; show lock screen reminder prompt
                        showBedtimePromptNotification(context, "已到入睡时间，锁屏后点击立即开始监测")
                    }
                }
                AutoSleepScheduler.scheduleAlarms(context)
            }

            AutoSleepScheduler.ACTION_WAKEUP -> {
                dismissPromptNotification(context)
                if (SnoreMonitorService.isRunning.value) {
                    SnoreMonitorService.stop(context)
                }
                AutoSleepScheduler.scheduleAlarms(context)
            }

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AutoSleepScheduler.scheduleAlarms(context)
            }
        }
    }

    private fun handleNightTrigger(context: Context, reasonText: String) {
        if (SnoreMonitorService.isRunning.value) return

        var started = false
        try {
            // Attempt direct start (works on Android 11 and below, or when permitted by OEM)
            SnoreMonitorService.start(context)
            started = true
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // On Android 12/14 where background start of microphone service is blocked,
        // display the high-priority lock screen prompt so user can tap once to start!
        if (!started || !SnoreMonitorService.isRunning.value) {
            showBedtimePromptNotification(context, "$reasonText，点击开启监测")
        }
    }

    private fun showBedtimePromptNotification(context: Context, text: String) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_AUTO_START", true)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            101,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val startServiceIntent = Intent(context, SnoreMonitorService::class.java).apply {
            action = SnoreMonitorService.ACTION_START
        }
        val startServicePendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                context,
                102,
                startServiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                context,
                102,
                startServiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(context, SnoreApplication.BEDTIME_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("🌙 夜间打鼾监测已就绪")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_media_play, "▶ 立即开启监测", startServicePendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(PROMPT_NOTIFICATION_ID, notification)
    }

    private fun dismissPromptNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(PROMPT_NOTIFICATION_ID)
    }
}
