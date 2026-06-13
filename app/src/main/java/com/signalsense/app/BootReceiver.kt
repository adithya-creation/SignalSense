package com.signalsense.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

/**
 * Handles device boot events.
 *
 * Derived from the production APK's BootReceiver (com.chaos.networkswitchalert.BootReceiver).
 * On BOOT_COMPLETED or QUICKBOOT_POWERON (Samsung), if the user has enabled
 * "auto_start_on_boot", the NetworkMonitorService is started as a foreground service.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        Log.d(TAG, "BootReceiver triggered with action: $action")

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        
        if (prefs.getBoolean("pref_autostart_with_data", false)) {
            Log.d(TAG, "Boot receiver rescheduling auto-start mobile data job because pref_autostart_with_data is true.")
            MobileDataJobService.scheduleMobileDataJob(context)
        }
        
        if (prefs.getBoolean("auto_start_on_boot", false)) {
            Log.d(TAG, "Boot receiver starting NetworkMonitorService foreground service because auto_start_on_boot is true.")
            val serviceIntent = Intent(context, NetworkMonitorService::class.java)
            try {
                ContextCompat.startForegroundService(context, serviceIntent)
                Log.d(TAG, "Successfully started NetworkMonitorService on boot.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start NetworkMonitorService on boot", e)
            }
        }
    }
}


