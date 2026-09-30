package com.minimal.snore.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File

class SnoreRepository(private val context: Context) {
    private val recordsFile = File(context.filesDir, "snore_events.json")
    private val _eventsFlow = MutableStateFlow<List<SnoreEvent>>(emptyList())
    val eventsFlow: StateFlow<List<SnoreEvent>> = _eventsFlow.asStateFlow()

    init {
        loadEvents()
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
            // Sort by latest first
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
    }

    @Synchronized
    fun deleteEvent(id: String) {
        val currentList = _eventsFlow.value.toMutableList()
        val itemToRemove = currentList.find { it.id == id }
        if (itemToRemove != null) {
            // Delete audio file
            val file = File(itemToRemove.audioFilePath)
            if (file.exists()) {
                file.delete()
            }
            currentList.remove(itemToRemove)
            saveList(currentList)
        }
    }

    @Synchronized
    fun clearAll() {
        val currentList = _eventsFlow.value
        for (item in currentList) {
            val file = File(item.audioFilePath)
            if (file.exists()) {
                file.delete()
            }
        }
        saveList(emptyList())
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
