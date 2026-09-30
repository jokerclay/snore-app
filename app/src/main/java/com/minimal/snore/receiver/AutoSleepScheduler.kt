package com.minimal.snore.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.minimal.snore.data.AppSettings
import com.minimal.snore.service.SnoreMonitorService
import java.util.Calendar

object AutoSleepScheduler {
    const val ACTION_BEDTIME = "com.minimal.snore.ACTION_BEDTIME"
    const val ACTION_WAKEUP = "com.minimal.snore.ACTION_WAKEUP"
    const val ACTION_RETRY_CHECK = "com.minimal.snore.ACTION_RETRY_CHECK"

    /**
     * 统一调度入口：
     * 1. 若当前已在设置的睡眠区间内且未启动，立即尝试强行开启并预约巡检；
     * 2. 设定每日入睡点 (Bedtime) 与起床点 (Wakeup) 定时闹钟。
     */
    fun scheduleAlarms(context: Context) {
        val settings = AppSettings.getInstance(context)
        if (!settings.autoEnabled) {
            cancelAlarms(context)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        // 1. 如果当前已经处于设定的夜间区间内，且服务尚未跑起来：立即强行尝试启动
        if (settings.isInNightWindow() && !SnoreMonitorService.isRunning.value) {
            val triggerIntent = Intent(context, AutoSleepReceiver::class.java).apply {
                action = ACTION_BEDTIME
            }
            context.sendBroadcast(triggerIntent)
        }

        // 2. 预约每日入睡闹钟 (Bedtime Alarm)
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

        // 3. 预约每日早晨起床闹钟 (Wakeup Alarm)
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
        } catch (e: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, bedCalendar.timeInMillis, bedIntent)
            alarmManager.set(AlarmManager.RTC_WAKEUP, wakeCalendar.timeInMillis, wakeIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 在设定的睡眠时间区间内，只要还没开启，就持续巡检并尽力强行拉起或提醒
     */
    fun scheduleRetryCheck(context: Context, delayMinutes: Int = 15) {
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
        } catch (e: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, retryIntent)
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
