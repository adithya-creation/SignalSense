package com.signalsense.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

/**
 * JobService scheduled to run when network constraints are met (e.g. connectivity active).
 * Wakes up the app in the background to start the foreground monitoring service if mobile data is ON
 * and the user has auto-start enabled.
 */
class MobileDataJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val context = applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        
        val autoStart = prefs.getBoolean("pref_autostart_with_data", false)
        Log.d(TAG, "Job started. pref_autostart_with_data: $autoStart")
        if (!autoStart) {
            Log.d(TAG, "pref_autostart_with_data is false. Not rescheduling, completing job.")
            jobFinished(params, false)
            return false
        }

        val delayMs = params?.extras?.getLong("delay_ms", 0L) ?: 0L
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && params?.isExpeditedJob == false) {
            if (delayMs > 0) {
                Log.d(TAG, "Standard delayed job fired. Rescheduling as expedited job to start foreground service safely.")
                scheduleMobileDataJob(context, 0)
            } else {
                Log.d(TAG, "Expedited job was demoted to regular. Proceeding without rescheduling to avoid loop.")
            }
            if (delayMs > 0) {
                jobFinished(params, false)
                return false
            }
        }

        // Use a permission-free way to check if the mobile data toggle is turned ON
        val isMobileDataEnabled = try {
            android.provider.Settings.Global.getInt(context.contentResolver, "mobile_data", 0) == 1
        } catch (e: Exception) {
            val tm = NetworkDetector.getScopedTelephonyManager(context)
            try {
                tm.isDataEnabled
            } catch (ex: Exception) {
                false
            }
        }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val hasCellularNetwork = try {
            cm.allNetworks.any { network ->
                val caps = cm.getNetworkCapabilities(network)
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            }
        } catch (e: Exception) {
            false
        }

        val systemTm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val isSimReady = try {
            systemTm.simState == TelephonyManager.SIM_STATE_READY
        } catch (e: Exception) {
            true // fallback to true
        }

        val isCellularAvailable = hasCellularNetwork || isSimReady

        Log.d(TAG, "Mobile data check - DataEnabled: $isMobileDataEnabled, CellularAvailable: $isCellularAvailable (SimReady: $isSimReady, CellularNetwork: $hasCellularNetwork)")

        if (isMobileDataEnabled && isCellularAvailable) {
            if (!NetworkMonitorService.isServiceRunning) {
                Log.d(TAG, "Mobile data detected ON. Monitoring service not running. Starting NetworkMonitorService...")
                val serviceIntent = Intent(context, NetworkMonitorService::class.java)
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                    Log.d(TAG, "Successfully started NetworkMonitorService.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start NetworkMonitorService foreground service", e)
                }
            } else {
                Log.d(TAG, "Mobile data detected ON, but NetworkMonitorService is already running.")
            }
            // Do NOT reschedule the job if the service is running to avoid infinite loop.
            // The service will reschedule the job when it is destroyed.
        } else {
            Log.d(TAG, "Conditions for starting NetworkMonitorService not met. Rescheduling job with a 5-minute delay to prevent looping.")
            scheduleMobileDataJob(context, 5 * 60 * 1000) // 5 minutes delay to prevent loop
        }

        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return false
    }

    companion object {
        private const val TAG = "MobileDataJobService"
        const val JOB_ID = 1002

        fun scheduleMobileDataJob(context: Context, delayMs: Long = 0) {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val componentName = ComponentName(context, MobileDataJobService::class.java)
            
            val extras = android.os.PersistableBundle().apply {
                putLong("delay_ms", delayMs)
            }
            
            val builder = JobInfo.Builder(JOB_ID, componentName)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_CELLULAR)
                .setExtras(extras)
                
            if (delayMs > 0) {
                builder.setMinimumLatency(delayMs)
                builder.setPersisted(true)
            } else {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    builder.setExpedited(true)
                }
            }
            
            val jobInfo = builder.build()
                
            try {
                jobScheduler.schedule(jobInfo)
                Log.d(TAG, "Job scheduled. Mobile data connection job scheduled successfully. Delay: ${delayMs}ms")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule job", e)
            }
        }

        fun cancelMobileDataJob(context: Context) {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            jobScheduler.cancel(JOB_ID)
            Log.d(TAG, "Job cancelled. Mobile data connection job cancelled.")
        }
    }
}


