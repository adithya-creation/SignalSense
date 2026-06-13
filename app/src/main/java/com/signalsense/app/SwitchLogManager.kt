package com.signalsense.app

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages reading and writing the switch log to SharedPreferences.
 * Keeps the last 50 entries serialized as a JSON array.
 */
class SwitchLogManager(context: Context) {

    companion object {
        private const val KEY_SWITCH_LOG = "pref_switch_log"
        private const val MAX_LOG_ENTRIES = 50
    }

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val gson = Gson()

    /**
     * Returns the current switch log, newest entries first.
     */
    fun getLog(): List<SwitchLogEntry> {
        val json = prefs.getString(KEY_SWITCH_LOG, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<SwitchLogEntry>>() {}.type
            gson.fromJson<List<SwitchLogEntry>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Appends a new entry to the log (most recent first). Trims to MAX_LOG_ENTRIES.
     */
    fun addEntry(entry: SwitchLogEntry) {
        val log = getLog().toMutableList()
        log.add(0, entry) // Add to beginning (newest first)
        // Trim to max size
        while (log.size > MAX_LOG_ENTRIES) {
            log.removeAt(log.size - 1)
        }
        saveLog(log)
    }

    /**
     * Clears the entire log.
     */
    fun clearLog() {
        prefs.edit().remove(KEY_SWITCH_LOG).apply()
    }

    /**
     * Returns count of downgrades in the current log.
     */
    fun getDowngradeCount(): Int {
        return getLog().count { entry ->
            val from = NetworkGeneration.fromDisplayName(entry.fromNetwork)
            val to = NetworkGeneration.fromDisplayName(entry.toNetwork)
            from.isHigherThan(to)
        }
    }

    /**
     * Returns total number of switches in the log.
     */
    fun getSwitchCount(): Int = getLog().size

    private fun saveLog(log: List<SwitchLogEntry>) {
        val json = gson.toJson(log)
        prefs.edit().putString(KEY_SWITCH_LOG, json).apply()
    }
}


