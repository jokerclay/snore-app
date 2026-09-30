package com.minimal.snore.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File
import java.util.Locale

data class StorageUsage(
    val totalBytes: Long,
    val audioBytes: Long,
    val reportBytes: Long,
    val sessionCount: Int,
    val snoreCount: Int
) {
    val totalFormatted: String get() = formatBytes(totalBytes)
    val audioFormatted: String get() = formatBytes(audioBytes)
    val reportFormatted: String get() = formatBytes(reportBytes)

    companion object {
        fun formatBytes(bytes: Long): String {
            return when {
                bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
                bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
                bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / 1024.0)
                else -> "$bytes B"
            }
        }
    }
}

class SnoreRepository(private val context: Context) {
    private val recordsFile = File(context.filesDir, "snore_events.json")
    private val sessionFile = File(context.filesDir, "last_session.json")
    private val sessionsHistoryFile = File(context.filesDir, "sessions_history.json")
    private val audioDir = File(context.filesDir, "snore_audio")

    private val _eventsFlow = MutableStateFlow<List<SnoreEvent>>(emptyList())
    val eventsFlow: StateFlow<List<SnoreEvent>> = _eventsFlow.asStateFlow()

    private val _latestSessionFlow = MutableStateFlow<SleepSession?>(null)
    val latestSessionFlow: StateFlow<SleepSession?> = _latestSessionFlow.asStateFlow()

    private val _sessionsHistoryFlow = MutableStateFlow<List<SleepSession>>(emptyList())
    val sessionsHistoryFlow: StateFlow<List<SleepSession>> = _sessionsHistoryFlow.asStateFlow()

    private val _storageUsageFlow = MutableStateFlow(
        StorageUsage(totalBytes = 0L, audioBytes = 0L, reportBytes = 0L, sessionCount = 0, snoreCount = 0)
    )
    val storageUsageFlow: StateFlow<StorageUsage> = _storageUsageFlow.asStateFlow()

    init {
        loadEvents()
        loadSessionsHistory()
        loadLatestSession()
        updateStorageUsage()
    }

    @Synchronized
    private fun loadLatestSession() {
        if (_sessionsHistoryFlow.value.isNotEmpty()) {
            _latestSessionFlow.value = _sessionsHistoryFlow.value.first()
            return
        }
        if (!sessionFile.exists()) return
        try {
            val content = sessionFile.readText()
            _latestSessionFlow.value = SleepSession.fromJson(org.json.JSONObject(content))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    private fun loadSessionsHistory() {
        if (!sessionsHistoryFile.exists()) {
            // Check if last_session.json exists to migrate
            if (sessionFile.exists()) {
                try {
                    val s = SleepSession.fromJson(org.json.JSONObject(sessionFile.readText()))
                    val list = listOf(s)
                    saveSessionsHistory(list)
                    _sessionsHistoryFlow.value = list
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return
        }

        try {
            val content = sessionsHistoryFile.readText()
            val jsonArray = JSONArray(content)
            val list = mutableListOf<SleepSession>()
            for (i in 0 until jsonArray.length()) {
                list.add(SleepSession.fromJson(jsonArray.getJSONObject(i)))
            }
            _sessionsHistoryFlow.value = list.sortedByDescending { it.startTime }
        } catch (e: Exception) {
            e.printStackTrace()
            _sessionsHistoryFlow.value = emptyList()
        }
    }

    @Synchronized
    fun saveSession(session: SleepSession) {
        try {
            // 1. Save as latest session
            sessionFile.writeText(session.toJson().toString())
            _latestSessionFlow.value = session

            // 2. Append to full history
            val currentHistory = _sessionsHistoryFlow.value.toMutableList()
            // Remove older session with same ID if exists
            currentHistory.removeAll { it.id == session.id }
            currentHistory.add(0, session)
            saveSessionsHistory(currentHistory)
            _sessionsHistoryFlow.value = currentHistory

            updateStorageUsage()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveSessionsHistory(list: List<SleepSession>) {
        try {
            val jsonArray = JSONArray()
            list.forEach { jsonArray.put(it.toJson()) }
            sessionsHistoryFile.writeText(jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    private fun loadEvents() {
        if (!recordsFile.exists()) {
            _eventsFlow.value = emptyList()
            return
        }

        try {
            val content = recordsFile.readText()
            val jsonArray = JSONArray(content)
            val list = mutableListOf<SnoreEvent>()
            for (i in 0 until jsonArray.length()) {
                list.add(SnoreEvent.fromJson(jsonArray.getJSONObject(i)))
            }
            _eventsFlow.value = list.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            e.printStackTrace()
            _eventsFlow.value = emptyList()
        }
    }

    @Synchronized
    fun addEvent(event: SnoreEvent) {
        val currentList = _eventsFlow.value.toMutableList()
        currentList.add(0, event)
        saveList(currentList)
        updateStorageUsage()
    }

    @Synchronized
    fun deleteEvent(id: String) {
        val currentList = _eventsFlow.value.toMutableList()
        val itemToRemove = currentList.find { it.id == id }
        if (itemToRemove != null) {
            val file = File(itemToRemove.audioFilePath)
            if (file.exists()) {
                file.delete()
            }
            currentList.remove(itemToRemove)
            saveList(currentList)
            updateStorageUsage()
        }
    }

    @Synchronized
    fun clearAll() {
        if (audioDir.exists()) {
            audioDir.listFiles()?.forEach { it.delete() }
        }
        saveList(emptyList())
        saveSessionsHistory(emptyList())
        if (sessionFile.exists()) sessionFile.delete()
        _latestSessionFlow.value = null
        _sessionsHistoryFlow.value = emptyList()
        updateStorageUsage()
    }

    /**
     * Clears only the WAV audio recordings in snore_audio/ to free up megabytes/gigabytes of storage,
     * while completely preserving all historical sleep session reports, graphs, and statistics.
     */
    @Synchronized
    fun clearAudioFilesOnly(): Long {
        var freedBytes = 0L
        if (audioDir.exists() && audioDir.isDirectory) {
            val files = audioDir.listFiles { _, name -> name.endsWith(".wav") } ?: emptyArray()
            for (f in files) {
                freedBytes += f.length()
                f.delete()
            }
        }
        updateStorageUsage()
        return freedBytes
    }

    private fun saveList(list: List<SnoreEvent>) {
        try {
            val jsonArray = JSONArray()
            list.forEach { jsonArray.put(it.toJson()) }
            recordsFile.writeText(jsonArray.toString())
            _eventsFlow.value = list
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateStorageUsage() {
        var audioBytes = 0L
        if (audioDir.exists() && audioDir.isDirectory) {
            val files = audioDir.listFiles() ?: emptyArray()
            for (f in files) {
                audioBytes += f.length()
            }
        }

        var reportBytes = 0L
        if (recordsFile.exists()) reportBytes += recordsFile.length()
        if (sessionFile.exists()) reportBytes += sessionFile.length()
        if (sessionsHistoryFile.exists()) reportBytes += sessionsHistoryFile.length()

        val totalBytes = audioBytes + reportBytes
        val sessionsCount = _sessionsHistoryFlow.value.size
        val snoreCount = _eventsFlow.value.size

        _storageUsageFlow.value = StorageUsage(
            totalBytes = totalBytes,
            audioBytes = audioBytes,
            reportBytes = reportBytes,
            sessionCount = sessionsCount,
            snoreCount = snoreCount
        )
    }

    companion object {
        @Volatile
        private var instance: SnoreRepository? = null

        fun getInstance(context: Context): SnoreRepository {
            return instance ?: synchronized(this) {
                instance ?: SnoreRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
