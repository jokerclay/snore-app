package com.minimal.snore.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.minimal.snore.data.AppSettings
import java.util.Calendar

object AutoSleepScheduler {
    const val ACTION_BEDTIME = "com.minimal.snore.ACTION_BEDTIME"
    const val ACTION_WAKEUP = "com.minimal.snore.ACTION_WAKEUP"

    fun scheduleAlarms(context: Context) {
        val settings = AppSettings.getInstance(context)
        if (!settings.autoEnabled) {
            cancelAlarms(context)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        // 1. Bedtime Alarm
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

        // 2. Wakeup Alarm
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
        alarmManager.cancel(bedIntent)
        alarmManager.cancel(wakeIntent)
    }
}
