package com.minimal.snore.data

import org.json.JSONObject

data class SleepSession(
    val id: String,
    val startTime: Long,
    val endTime: Long,
    val totalMonitoringMs: Long,
    val totalSnoreMs: Long,
    val snoreCount: Int,
    val maxDb: Float,
    val avgDb: Float,
    val lightSnoreCount: Int,   // < 48 dB
    val mediumSnoreCount: Int,  // 48..60 dB
    val severeSnoreCount: Int,  // > 60 dB
    val apneaSuspectCount: Int, // suspected apnea gaps
    val hourlyDistribution: Map<Int, Int> // hour of day (0..23) -> count
) {
    val snoreBurdenPercent: Float
        get() = if (totalMonitoringMs > 0) {
            (totalSnoreMs.toFloat() / totalMonitoringMs.toFloat() * 100f).coerceIn(0f, 100f)
        } else 0f

    val severityLevel: String
        get() = when {
            apneaSuspectCount >= 3 || snoreBurdenPercent >= 15f -> "重度需关注"
            snoreBurdenPercent >= 5f -> "中度打鼾"
            else -> "轻微安眠"
        }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("startTime", startTime)
        json.put("endTime", endTime)
        json.put("totalMonitoringMs", totalMonitoringMs)
        json.put("totalSnoreMs", totalSnoreMs)
        json.put("snoreCount", snoreCount)
        json.put("maxDb", maxDb.toDouble())
        json.put("avgDb", avgDb.toDouble())
        json.put("lightSnoreCount", lightSnoreCount)
        json.put("mediumSnoreCount", mediumSnoreCount)
        json.put("severeSnoreCount", severeSnoreCount)
        json.put("apneaSuspectCount", apneaSuspectCount)

        val hourlyJson = JSONObject()
        hourlyDistribution.forEach { (hour, count) ->
            hourlyJson.put(hour.toString(), count)
        }
        json.put("hourlyDistribution", hourlyJson)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): SleepSession {
            val hourlyMap = mutableMapOf<Int, Int>()
            if (json.has("hourlyDistribution")) {
                val hJson = json.getJSONObject("hourlyDistribution")
                val keys = hJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    hourlyMap[key.toInt()] = hJson.getInt(key)
                }
            }
            return SleepSession(
                id = json.getString("id"),
                startTime = json.getLong("startTime"),
                endTime = json.getLong("endTime"),
                totalMonitoringMs = json.getLong("totalMonitoringMs"),
                totalSnoreMs = json.getLong("totalSnoreMs"),
                snoreCount = json.getInt("snoreCount"),
                maxDb = json.getDouble("maxDb").toFloat(),
                avgDb = json.getDouble("avgDb").toFloat(),
                lightSnoreCount = json.optInt("lightSnoreCount", 0),
                mediumSnoreCount = json.optInt("mediumSnoreCount", 0),
                severeSnoreCount = json.optInt("severeSnoreCount", 0),
                apneaSuspectCount = json.optInt("apneaSuspectCount", 0),
                hourlyDistribution = hourlyMap
            )
        }
    }
}
