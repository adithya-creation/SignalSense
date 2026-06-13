package com.signalsense.app

/**
 * Data class representing a network switch log entry.
 */
data class SwitchLogEntry(
    val timestamp: Long,        // System.currentTimeMillis()
    val fromNetwork: String,    // "5G SA", "5G NSA", "4G", "3G", "2G", "NO_DATA"
    val toNetwork: String,
    val downlinkMbps: Float?,   // nullable — may not be available
    val rttMs: Int?,            // nullable
    val bandInfo: String?       // e.g. "B3 · 1800 MHz" or "n78 · 3.5 GHz", nullable
)



