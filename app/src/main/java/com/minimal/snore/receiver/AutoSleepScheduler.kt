package com.minimal.snore.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.minimal.snore.data.AppSettings
import com.minimal.snore.service.SnoreMonitorService
import java.util.Calendar

object AutoSleepScheduler {
    const val ACTION_BEDTIME = "com.minimal.snore.ACTION_BEDTIME"
    const val ACTION_WAKEUP = "com.minimal.snore.ACTION_WAKEUP"
    const val ACTION_RETRY_CHECK = "com.minimal.snore.ACTION_RETRY_CHECK"

    fun scheduleAlarms(context: Context) {
        val settings = AppSettings.getInstance(context)
        if (!settings.autoEnabled) {
            cancelAlarms(context)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        // 1. If currently already inside night sleep window and monitoring has not started:
        if (settings.isInNightWindow() && !SnoreMonitorService.isRunning.value) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isScreenOn = powerManager?.isInteractive ?: false

            if (!isScreenOn) {
                // Screen is already dark/locked: trigger night start check immediately!
                val triggerIntent = Intent(context, AutoSleepReceiver::class.java).apply {
                    action = ACTION_BEDTIME
                }
                context.sendBroadcast(triggerIntent)
            } else {
                // User is currently using phone: schedule short interval retry check
                scheduleRetryCheck(context, 10)
            }
        }

        // 2. Bedtime Alarm (for next occurrence)
        val bedCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, settings.bedtimeHour)
            set(Calendar.MINUTE, settings.bedtimeMinute)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val bedIntent = PendingIntent.getBroadcast(
            context,
            201,
            Intent(context, AutoSleepReceiver::class.java).apply { action = ACTION_BEDTIME },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 3. Wakeup Alarm (for next occurrence)
        val wakeCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, settings.wakeupHour)
            set(Calendar.MINUTE, settings.wakeupMinute)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val wakeIntent = PendingIntent.getBroadcast(
            context,
            202,
            Intent(context, AutoSleepReceiver::class.java).apply { action = ACTION_WAKEUP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, bedCalendar.timeInMillis, bedIntent)
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeCalendar.timeInMillis, wakeIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, bedCalendar.timeInMillis, bedIntent)
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, wakeCalendar.timeInMillis, wakeIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Schedules a periodic retry alarm (e.g. 10 minutes later) during the night window
     * while the user is still actively using the phone (screen ON).
     * Once the user locks their screen to sleep, the next check will auto-start monitoring.
     */
    fun scheduleRetryCheck(context: Context, delayMinutes: Int = 10) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val retryIntent = PendingIntent.getBroadcast(
            context,
            203,
            Intent(context, AutoSleepReceiver::class.java).apply { action = ACTION_RETRY_CHECK },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAtMillis = System.currentTimeMillis() + delayMinutes * 60 * 1000L

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, retryIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, retryIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cancelAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val bedIntent = PendingIntent.getBroadcast(
            context,
            201,
            Intent(context, AutoSleepReceiver::class.java).apply { action = ACTION_BEDTIME },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val wakeIntent = PendingIntent.getBroadcast(
            context,
            202,
            Intent(context, AutoSleepReceiver::class.java).apply { action = ACTION_WAKEUP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val retryIntent = PendingIntent.getBroadcast(
            context,
            203,
            Intent(context, AutoSleepReceiver::class.java).apply { action = ACTION_RETRY_CHECK },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(bedIntent)
        alarmManager.cancel(wakeIntent)
        alarmManager.cancel(retryIntent)
    }
}
