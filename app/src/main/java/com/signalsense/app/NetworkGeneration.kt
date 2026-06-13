package com.signalsense.app

/**
 * Represents the mobile network generation type.
 *
 * FIVE_G_SA  = 5G Standalone (true 5G NR, networkType == NR)
 * FIVE_G_NSA = 5G Non-Standalone (5G over LTE anchor, overrideNetworkType from TelephonyDisplayInfo)
 * FIVE_G     = legacy alias kept for backward-compat (maps to SA label "5G SA")
 */
enum class NetworkGeneration(val displayName: String, val level: Int) {
    FIVE_G_SA("5G SA", 6),
    FIVE_G_NSA("5G NSA", 5),
    FIVE_G("5G", 5),           // fallback when SA/NSA cannot be distinguished
    FOUR_G("4G", 4),
    THREE_G("3G", 3),
    TWO_G("2G", 2),
    UNKNOWN("Unknown", 0),
    NO_DATA("No Data", -1);

    /** Returns true if this generation is higher than [other]. */
    fun isHigherThan(other: NetworkGeneration): Boolean = this.level > other.level

    /** Returns true if this generation is lower than [other] (excludes NO_DATA/UNKNOWN). */
    fun isLowerThan(other: NetworkGeneration): Boolean =
        this.level < other.level && this.level > 0 && other.level > 0

    /** Returns true if this is any flavour of 5G. */
    fun is5G(): Boolean = this == FIVE_G_SA || this == FIVE_G_NSA || this == FIVE_G

    companion object {
        fun fromDisplayName(name: String): NetworkGeneration =
            entries.find { it.displayName == name } ?: UNKNOWN
    }
}


