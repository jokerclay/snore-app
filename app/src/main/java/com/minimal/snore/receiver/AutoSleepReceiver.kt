package com.minimal.snore.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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
                // 插电时：若在设定作息时间内且未运行，强行尝试启动
                if (settings.autoEnabled && settings.isInNightWindow()) {
                    forceStartOrNotify(context, "已在设定的睡眠时间段内")
                }
            }

            Intent.ACTION_POWER_DISCONNECTED -> {
                // 拔掉充电器
                dismissPromptNotification(context)
                if (settings.autoEnabled && SnoreMonitorService.isRunning.value) {
                    SnoreMonitorService.stop(context)
                }
            }

            AutoSleepScheduler.ACTION_BEDTIME,
            AutoSleepScheduler.ACTION_RETRY_CHECK -> {
                if (!settings.autoEnabled) {
                    dismissPromptNotification(context)
                    return
                }

                // 若已经在运行中，无需再开，直接清除提醒通知
                if (SnoreMonitorService.isRunning.value) {
                    dismissPromptNotification(context)
                    return
                }

                // 判断是否在用户设置的睡眠时间区间内
                if (settings.isInNightWindow()) {
                    // 在区间内：尽全力强行开！开不了就通知用户点开，并且安排下一次重试巡检
                    forceStartOrNotify(context, "已到设定的睡眠监测时间")
                    AutoSleepScheduler.scheduleRetryCheck(context, 15)
                } else {
                    // 已经不在区间内：清理提醒，重新排定常规每日闹钟
                    dismissPromptNotification(context)
                    AutoSleepScheduler.scheduleAlarms(context)
                }
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

    /**
     * 强行启动监测服务。
     * 如果系统因为 Android 12/14 后台限制拦截了录音启动，则立即弹出高优先级全屏/锁屏提醒卡片，
     * 附带 [▶ 立即开启监测] 按钮，方便用户一键开启。
     */
    private fun forceStartOrNotify(context: Context, reasonText: String) {
        if (SnoreMonitorService.isRunning.value) return

        var started = false
        try {
            SnoreMonitorService.start(context)
            started = true
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 如果直接启动被系统拦截或尚未处于 running 状态，强提醒用户开启
        if (!started || !SnoreMonitorService.isRunning.value) {
            showBedtimePromptNotification(context, "$reasonText，点击立即开启监测")
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
