package com.minimal.snore.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.minimal.snore.data.AppSettings
import com.minimal.snore.service.SnoreMonitorService

class AutoSleepReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val settings = AppSettings.getInstance(context)

        when (action) {
            Intent.ACTION_POWER_CONNECTED -> {
                // Plugged in: if in night window and auto-enabled, start monitoring!
                if (settings.autoEnabled && settings.isInNightWindow()) {
                    if (!SnoreMonitorService.isRunning.value) {
                        SnoreMonitorService.start(context)
                    }
                }
            }

            Intent.ACTION_POWER_DISCONNECTED -> {
                // Unplugged in the morning: stop monitoring and generate report!
                if (settings.autoEnabled && SnoreMonitorService.isRunning.value) {
                    SnoreMonitorService.stop(context)
                }
            }

            AutoSleepScheduler.ACTION_BEDTIME -> {
                // Bedtime arrived: check screen-off guard
                if (settings.autoEnabled && !SnoreMonitorService.isRunning.value) {
                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    val isScreenOn = powerManager?.isInteractive ?: false

                    if (!isScreenOn) {
                        // Phone is locked/screen off: user is sleeping, start monitoring!
                        SnoreMonitorService.start(context)
                    }
                }
                // Reschedule for next day
                AutoSleepScheduler.scheduleAlarms(context)
            }

            AutoSleepScheduler.ACTION_WAKEUP -> {
                // Wakeup time arrived: stop monitoring and finalize report
                if (SnoreMonitorService.isRunning.value) {
                    SnoreMonitorService.stop(context)
                }
                // Reschedule for next day
                AutoSleepScheduler.scheduleAlarms(context)
            }

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Re-register alarms after phone restart
                AutoSleepScheduler.scheduleAlarms(context)
            }
        }
    }
}
