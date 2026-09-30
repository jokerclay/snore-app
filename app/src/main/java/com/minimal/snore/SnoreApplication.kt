package com.minimal.snore

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.minimal.snore.receiver.AutoSleepScheduler

class SnoreApplication : Application() {
    companion object {
        const val CHANNEL_ID = "snore_monitor_channel"
        const val BEDTIME_CHANNEL_ID = "snore_bedtime_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        AutoSleepScheduler.scheduleAlarms(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java) ?: return

            // 1. Service persistent channel (low importance, silent)
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(serviceChannel)

            // 2. Bedtime prompt channel (high importance, heads-up on lockscreen)
            val bedtimeChannel = NotificationChannel(
                BEDTIME_CHANNEL_ID,
                "入睡监测提醒与自启",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "夜间插电或到达睡眠时间时的锁屏一键开启提示"
                setShowBadge(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(bedtimeChannel)
        }
    }
}
