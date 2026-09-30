package com.minimal.snore.data

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("snore_app_prefs", Context.MODE_PRIVATE)

    var autoEnabled: Boolean
        get() = prefs.getBoolean("auto_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_enabled", value).apply()

    var bedtimeHour: Int
        get() = prefs.getInt("bedtime_hour", 22)
        set(value) = prefs.edit().putInt("bedtime_hour", value).apply()

    var bedtimeMinute: Int
        get() = prefs.getInt("bedtime_minute", 30)
        set(value) = prefs.edit().putInt("bedtime_minute", value).apply()

    var wakeupHour: Int
        get() = prefs.getInt("wakeup_hour", 7)
        set(value) = prefs.edit().putInt("wakeup_hour", value).apply()

    var wakeupMinute: Int
        get() = prefs.getInt("wakeup_minute", 30)
        set(value) = prefs.edit().putInt("wakeup_minute", value).apply()

    /**
     * Checks if current local time is within the sleep time window.
     * E.g. 22:30 to 07:30 next morning.
     */
    fun isInNightWindow(): Boolean {
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val bedMinutes = bedtimeHour * 60 + bedtimeMinute
        val wakeMinutes = wakeupHour * 60 + wakeupMinute

        return if (bedMinutes > wakeMinutes) {
            // Crosses midnight (e.g. 22:30 to 07:30)
            currentMinutes >= bedMinutes || currentMinutes <= wakeMinutes
        } else {
            // Same day (e.g. 01:00 to 08:00)
            currentMinutes in bedMinutes..wakeMinutes
        }
    }

    companion object {
        @Volatile
        private var instance: AppSettings? = null

        fun getInstance(context: Context): AppSettings {
            return instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
        }
    }
}
