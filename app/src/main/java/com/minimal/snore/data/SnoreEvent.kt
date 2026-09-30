package com.minimal.snore.data

import org.json.JSONObject

data class SnoreEvent(
    val id: String,
    val timestamp: Long,
    val durationMs: Long,
    val peakDb: Float,
    val audioFilePath: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("timestamp", timestamp)
            put("durationMs", durationMs)
            put("peakDb", peakDb.toDouble())
            put("audioFilePath", audioFilePath)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): SnoreEvent {
            return SnoreEvent(
                id = json.getString("id"),
                timestamp = json.getLong("timestamp"),
                durationMs = json.getLong("durationMs"),
                peakDb = json.getDouble("peakDb").toFloat(),
                audioFilePath = json.getString("audioFilePath")
            )
        }
    }
}
