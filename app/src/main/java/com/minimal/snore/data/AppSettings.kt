package com.minimal.snore.data

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("snore_app_prefs", Context.MODE_PRIVATE)

    enum class MicCalibration(val label: String, val offsetDb: Float) {
        BEDSIDE("床头近距离 (0dB)", 0.0f),
        NIGHTSTAND("床头柜标准 (+3dB)", 3.0f),
        FAR("卧室桌远距 (+6dB)", 6.0f)
    }

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

    var micCalibration: MicCalibration
        get() {
            val name = prefs.getString("mic_calibration", MicCalibration.NIGHTSTAND.name)
            return try {
                MicCalibration.valueOf(name ?: MicCalibration.NIGHTSTAND.name)
            } catch (e: Exception) {
                MicCalibration.NIGHTSTAND
            }
        }
        set(value) = prefs.edit().putString("mic_calibration", value.name).apply()

    /**
     * Checks if current local time is within the sleep time window.
     * E.g. 22:30 to 07:30 next morning.
     */
    fun isInNightWindow(): Boolean {
        val now = Calendar.getInstance()
        return isTimeInNightWindow(
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            bedtimeHour,
            bedtimeMinute,
            wakeupHour,
            wakeupMinute
        )
    }

    companion object {
        @Volatile
        private var instance: AppSettings? = null

        fun getInstance(context: Context): AppSettings {
            return instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
        }

        fun isTimeInNightWindow(
            currentHour: Int,
            currentMinute: Int,
            bedHour: Int,
            bedMinute: Int,
            wakeHour: Int,
            wakeMinute: Int
        ): Boolean {
            val currentMinutes = currentHour * 60 + currentMinute
            val bedMinutes = bedHour * 60 + bedMinute
            val wakeMinutes = wakeHour * 60 + wakeMinute

            return if (bedMinutes > wakeMinutes) {
                // Crosses midnight (e.g. 22:30 to 07:30)
                currentMinutes >= bedMinutes || currentMinutes <= wakeMinutes
            } else {
                // Same day (e.g. 01:00 to 08:00)
                currentMinutes in bedMinutes..wakeMinutes
            }
        }
    }
}
