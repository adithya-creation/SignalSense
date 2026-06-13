package com.signalsense.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

/**
 * Pure detection logic for determining the current mobile network generation.
 *
 * Key improvements derived from the production APK (com.chaos.networkswitchalert):
 *  - Uses TelephonyDisplayInfo.getOverrideNetworkType() (API 30+) to distinguish 5G SA vs 5G NSA.
 *  - Uses getDataNetworkType() on API 30+ (more accurate than getNetworkType()).
 *  - Band info: decodes EARFCN → LTE band + frequency, NR-ARFCN → NR band + frequency.
 *  - TrafficStats-based speed formatting (real measured bytes/s).
 */
object NetworkDetector {

    // ─── Permission helper ────────────────────────────────────────────────────

    /** Returns true if READ_PHONE_STATE is granted. */
    fun hasPhonePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED

    /** Returns the current network operator name (carrier). */
    fun getCarrierName(context: Context): String {
        val tm = getScopedTelephonyManager(context)
        val name = try {
            tm.networkOperatorName
        } catch (e: Exception) {
            ""
        }
        return name.ifEmpty { "No Service" }
    }

    // ─── Primary: TelephonyDisplayInfo-based detection (API 30+) ─────────────

    /**
     * Derives the network generation from a [TelephonyDisplayInfo] event.
     *
     * Derived from APK's NetworkMonitorService.m2074b():
     *  - overrideNetworkType 3 (LTE_ADVANCED_PRO), 4 (NR_NSA), 5 (NR_NSA_MMWAVE) → 5G NSA
     *  - networkType 20 (NR) → 5G SA
     *  - Otherwise delegates to raw networkType mapping.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun generationFromDisplayInfo(info: TelephonyDisplayInfo): NetworkGeneration {
        val override = info.overrideNetworkType
        val rawType  = info.networkType

        return when {
            rawType == TelephonyManager.NETWORK_TYPE_NR -> NetworkGeneration.FIVE_G_SA
            override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
            override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE ||
            override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO ->
                NetworkGeneration.FIVE_G_NSA
            else -> generationFromNetworkType(rawType)
        }
    }

    // ─── Fallback: raw networkType → generation ───────────────────────────────

    /**
     * Maps a raw TelephonyManager NETWORK_TYPE_* constant to a [NetworkGeneration].
     *
     * Derived from APK's NetworkMonitorService.m2079m() with full constant coverage.
     */
    fun generationFromNetworkType(networkType: Int): NetworkGeneration = when (networkType) {
        TelephonyManager.NETWORK_TYPE_NR    -> NetworkGeneration.FIVE_G_SA
        TelephonyManager.NETWORK_TYPE_LTE   -> NetworkGeneration.FOUR_G
        // 3G variants
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA,   // 17 — APK maps as 3G
        15                                          // NETWORK_TYPE_HSPA_PLUS variant
            -> NetworkGeneration.THREE_G
        // 2G variants
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN,        // 11
        16                                          // NETWORK_TYPE_GSM
            -> NetworkGeneration.TWO_G
        // Ignore IWLAN (18) — Wi-Fi calling, not cellular
        else -> NetworkGeneration.UNKNOWN
    }

    // ─── getNetworkGeneration (ConnectivityManager path) ─────────────────────

    /**
     * Checks if the monitored SIM slot is the active data SIM slot.
     */
    fun isActiveDataSim(context: Context): Boolean {
        return try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val selectedSlotString = prefs.getString("data_sim_slot", "-1") ?: "-1"
            val selectedSlot = selectedSlotString.toIntOrNull() ?: -1

            if (selectedSlot < 0) {
                // "Auto" slot is selected, so we are always monitoring the active data SIM
                return true
            }

            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                    as? android.telephony.SubscriptionManager ?: return true

            // Get the subscription info for the selected slot
            val subInfo = sm.getActiveSubscriptionInfoForSimSlotIndex(selectedSlot) ?: return false
            val monitoredSubId = subInfo.subscriptionId

            val activeDataSubId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.telephony.SubscriptionManager.getActiveDataSubscriptionId()
            } else {
                android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
            }

            monitoredSubId == activeDataSubId
        } catch (e: Exception) {
            true // fallback to true on error
        }
    }

    /**
     * Full detection using ConnectivityManager + TelephonyManager.
     * Used as a fallback when no [TelephonyDisplayInfo] is available (e.g. initial check).
     *
     * On API 30+, calls getDataNetworkType() (more accurate).
     * On older APIs, falls back to getNetworkType().
     */
    fun getNetworkGeneration(context: Context): NetworkGeneration {
        if (!isActiveDataSim(context)) {
            return NetworkGeneration.NO_DATA
        }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return NetworkGeneration.NO_DATA
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkGeneration.NO_DATA

        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return NetworkGeneration.NO_DATA
        }

        if (!hasPhonePermission(context)) {
            return NetworkGeneration.UNKNOWN
        }

        val tm = getScopedTelephonyManager(context)

        return try {
            val networkType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                tm.dataNetworkType       // API 30+ — data-specific, more accurate
            } else {
                @Suppress("DEPRECATION")
                tm.networkType
            }
            generationFromNetworkType(networkType)
        } catch (e: SecurityException) {
            NetworkGeneration.UNKNOWN
        } catch (e: Exception) {
            NetworkGeneration.UNKNOWN
        }
    }

    // ─── SIM-slot-aware TelephonyManager ─────────────────────────────────────

    /**
     * Returns a [TelephonyManager] scoped to the correct subscription.
     *
     * Derived from APK's NetworkMonitorService.m2082e():
     *  - If user has selected a specific SIM slot (pref "data_sim_slot"), scope to that slot.
     *  - Otherwise scope to the default data subscription ID.
     *  - Falls back to unscoped manager on any error.
     */
    fun getScopedTelephonyManager(context: Context): TelephonyManager {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        return try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val selectedSlotString = prefs.getString("data_sim_slot", "-1") ?: "-1"
            val selectedSlot = selectedSlotString.toIntOrNull() ?: -1

            if (selectedSlot >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                        as? android.telephony.SubscriptionManager
                val subInfo = sm?.getActiveSubscriptionInfoForSimSlotIndex(selectedSlot)
                if (subInfo != null) {
                    return tm.createForSubscriptionId(subInfo.subscriptionId) ?: tm
                }
            }

            // Scope to default data subscription
            val defaultSubId = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
            if (defaultSubId != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                tm.createForSubscriptionId(defaultSubId) ?: tm
            } else {
                tm
            }
        } catch (e: Exception) {
            tm
        }
    }

    // ─── Band info: LTE (EARFCN) ─────────────────────────────────────────────

    /**
     * Decodes an LTE EARFCN to a band label and frequency string.
     *
     * Derived from APK's NetworkMonitorService.m2075f().
     * Returns e.g. "B3 · 1800 MHz" or "B40 · 2300 MHz", or null if unknown.
     */
    fun getBandInfoLte(earfcn: Int): String? {
        val band: Int = when {
            earfcn in 0..599        -> 1
            earfcn in 600..1199     -> 2
            earfcn in 1200..1949    -> 3
            earfcn in 1950..2399    -> 4
            earfcn in 2649..3449    -> 5   // note: gap 2400-2648 is unassigned
            earfcn in 2750..3449    -> 5
            earfcn in 3450..3799    -> 7
            earfcn in 4150..4749    -> 8
            earfcn in 5000..5179    -> 10
            earfcn in 5180..5279    -> 12
            earfcn in 5730..5849    -> 13
            earfcn in 6000..6149    -> 17
            earfcn in 6150..6449    -> 19
            earfcn in 9040..9209    -> 20
            earfcn in 9210..9659    -> 27
            earfcn in 38650..39649  -> 40
            earfcn in 39650..41589  -> 41
            earfcn in 43590..45589  -> 43
            else                    -> return null
        }
        val freq = LTE_BAND_FREQ[band] ?: return "B$band"
        return "B$band · $freq"
    }

    private val LTE_BAND_FREQ = mapOf(
        1 to "2100 MHz", 2 to "1900 MHz", 3 to "1800 MHz", 4 to "1700 MHz",
        5 to "850 MHz",  7 to "2600 MHz", 8 to "900 MHz",  10 to "1700 MHz",
        12 to "700 MHz", 13 to "700 MHz", 17 to "700 MHz", 19 to "850 MHz",
        20 to "800 MHz", 27 to "850 MHz", 28 to "700 MHz", 40 to "2300 MHz",
        41 to "2500 MHz", 43 to "3700 MHz"
    )

    // ─── Band info: NR (NR-ARFCN) ────────────────────────────────────────────

    /**
     * Decodes an NR-ARFCN to an NR band label and frequency string.
     *
     * Derived from APK's NetworkMonitorService.m2078l().
     * Returns e.g. "n78 · 3.5 GHz" or "n257 · 28 GHz", or null if unknown.
     */
    fun getBandInfoNr(nrArfcn: Int): String? {
        val band: Int = when {
            nrArfcn in 422000..434000   -> 1
            nrArfcn in 386000..398000   -> 2
            nrArfcn in 361000..376000   -> 3
            nrArfcn in 173800..178800   -> 5
            nrArfcn in 524000..538000   -> 7
            nrArfcn in 185000..192000   -> 8
            nrArfcn in 285400..286400   -> 12
            nrArfcn in 151600..160600   -> 20
            nrArfcn in 496700..499000   -> 28
            nrArfcn in 499200..537999   -> 41
            nrArfcn in 620000..653333   -> 78
            nrArfcn in 693334..733333   -> 79
            nrArfcn in 2054166..2104165 -> 257
            nrArfcn in 2016667..2070832 -> 258
            else                         -> return null
        }
        val freq = NR_BAND_FREQ[band] ?: return "n$band"
        return "n$band · $freq"
    }

    private val NR_BAND_FREQ = mapOf(
        1 to "2100 MHz", 2 to "1900 MHz", 3 to "1800 MHz", 5 to "850 MHz",
        7 to "2600 MHz", 8 to "900 MHz",  12 to "700 MHz", 20 to "800 MHz",
        28 to "700 MHz", 41 to "2500 MHz", 78 to "3.5 GHz", 79 to "4.7 GHz",
        257 to "28 GHz", 258 to "26 GHz"
    )

    // ─── Speed helpers ────────────────────────────────────────────────────────

    /**
     * Formats a raw bytes/sec value to a human-readable speed string.
     *
     * Derived from APK's NetworkMonitorService.m2076h():
     *  < 1 KB/s → "NNN B/s"
     *  < 1 MB/s → "NNN KB/s"
     *  ≥ 1 MB/s → "N.N MB/s"
     */
    fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec < 1_024L       -> "${bytesPerSec}B/s"
        bytesPerSec < 1_048_576L   -> "${bytesPerSec / 1_024}KB/s"
        else                        -> "%.1fMB/s".format(bytesPerSec / 1_048_576.0)
    }

    /**
     * Returns estimated downlink speed in Mbps from NetworkCapabilities,
     * or null if unavailable/zero.
     */
    fun getDownlinkMbps(context: Context): Float? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return null) ?: return null
        val kbps = caps.linkDownstreamBandwidthKbps
        return if (kbps > 0) kbps / 1000f else null
    }

    /**
     * Returns the total received bytes since boot (via TrafficStats).
     * Returns 0 if unsupported (UNSUPPORTED == -1).
     */
    fun getTotalRxBytes(): Long {
        val v = TrafficStats.getTotalRxBytes()
        return if (v == TrafficStats.UNSUPPORTED.toLong()) 0L else v
    }

    /**
     * Returns the total transmitted bytes since boot (via TrafficStats).
     * Returns 0 if unsupported.
     */
    fun getTotalTxBytes(): Long {
        val v = TrafficStats.getTotalTxBytes()
        return if (v == TrafficStats.UNSUPPORTED.toLong()) 0L else v
    }

    /**
     * Android doesn't provide direct RTT measurement via standard APIs.
     * Returns null — callers may implement ping-based RTT separately.
     */
    fun getRttMs(context: Context): Int? = null

    // ─── Transition type ──────────────────────────────────────────────────────

    /**
     * Determines the transition type between two network generations.
     * Both FIVE_G_SA and FIVE_G_NSA are treated as level-5 for comparison purposes.
     */
    fun getTransitionType(from: NetworkGeneration, to: NetworkGeneration): TransitionType {
        return when {
            from == NetworkGeneration.NO_DATA && to != NetworkGeneration.NO_DATA ->
                TransitionType.DATA_RESTORED
            to == NetworkGeneration.NO_DATA && from != NetworkGeneration.NO_DATA ->
                TransitionType.DATA_LOST
            from.isHigherThan(to) -> TransitionType.DOWNGRADE
            to.isHigherThan(from) -> TransitionType.UPGRADE
            else                   -> TransitionType.NONE
        }
    }

    enum class TransitionType {
        UPGRADE, DOWNGRADE, DATA_LOST, DATA_RESTORED, NONE
    }
}


