package com.signalsense.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager

/**
 * Manages notification channels and builds/fires notifications.
 */
class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_MONITOR = "SignalSense_monitor"
        const val NOTIFICATION_ID_MONITOR = 1001
        private var alertNotificationId = 2000
    }

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        createNotificationChannels()
    }

    fun getAlertChannelId(): String {
        val isVibrateOn = prefs.getBoolean("pref_vibrate_on_alert", true)
        val ringtoneUri = prefs.getString("pref_notification_ringtone", "default") ?: "default"

        val vibrateSuffix = if (isVibrateOn) "v1" else "v0"
        val soundHash = when (ringtoneUri) {
            "default" -> ""
            "silent" -> "_silent"
            else -> "_" + ringtoneUri.hashCode().toString().replace("-", "n")
        }

        return "SignalSense_alerts_${vibrateSuffix}${soundHash}"
    }

    fun ensureAlertChannelCreated(activeChannelId: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Return early if the active channel already exists
        if (nm.getNotificationChannel(activeChannelId) != null) {
            return
        }

        // Delete legacy alert channels
        val legacyAlertChannels = listOf(
            "SignalSense_alerts",
            "SignalSense_alerts_v2",
            "SignalSense_alerts_vibrate",
            "SignalSense_alerts_silent"
        )
        for (channelId in legacyAlertChannels) {
            try {
                nm.deleteNotificationChannel(channelId)
            } catch (e: Exception) {
                // Ignore
            }
        }

        // Delete any other dynamic combinations to keep system settings clean
        try {
            val registeredChannels = nm.notificationChannels
            for (channel in registeredChannels) {
                val id = channel.id
                if (id.startsWith("SignalSense_alerts_") && id != activeChannelId) {
                    nm.deleteNotificationChannel(id)
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        // Parse settings from the activeChannelId string
        val isSoundOn = !activeChannelId.contains("silent")
        val isVibrateOn = activeChannelId.contains("v1")
        val ringtoneUriString = prefs.getString("pref_notification_ringtone", "default") ?: "default"

        val channel = NotificationChannel(
            activeChannelId,
            context.getString(R.string.notif_channel_alerts_vibrate_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notif_channel_alerts_vibrate_desc)

            if (isSoundOn && ringtoneUriString != "silent") {
                val soundUri = if (ringtoneUriString != "default" && ringtoneUriString.isNotEmpty()) {
                    Uri.parse(ringtoneUriString)
                } else {
                    android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
                }
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .build()
                setSound(soundUri, audioAttributes)
            } else {
                setSound(null, null)
            }

            enableVibration(isVibrateOn)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * Creates both notification channels (monitor and active alert channel).
     */
    fun createNotificationChannels() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Monitor channel — low importance, persistent
        val monitorChannel = NotificationChannel(
            CHANNEL_MONITOR,
            context.getString(R.string.notif_channel_monitor_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_monitor_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(monitorChannel)

        // Ensure current alert channel is created (and others deleted)
        ensureAlertChannelCreated(getAlertChannelId())
    }

    /**
     * Builds the persistent monitoring notification.
     */
    fun buildMonitorNotification(
        generation: NetworkGeneration,
        downlinkMbps: Float?,
        speedStr: String? = null        // real measured speed from TrafficStats (e.g. "2.4MB/s")
    ): NotificationCompat.Builder {
        val carrier = NetworkDetector.getCarrierName(context)
        val statusText = when (generation) {
            NetworkGeneration.FIVE_G_SA  -> "5G SA Active"
            NetworkGeneration.FIVE_G_NSA -> "5G NSA Active"
            NetworkGeneration.FIVE_G     -> "5G Active"
            NetworkGeneration.FOUR_G     -> "4G Active"
            NetworkGeneration.THREE_G    -> "3G Active"
            NetworkGeneration.TWO_G      -> "2G Active"
            else                         -> "No Connection"
        }

        val title = if (!carrier.isNullOrBlank()) {
            "$carrier • $statusText"
        } else {
            statusText
        }

        // Prefer real measured speed (TrafficStats); fall back to estimated bandwidth
        val contentText = if (prefs.getBoolean("pref_show_speed", false) &&
            generation != NetworkGeneration.NO_DATA &&
            generation != NetworkGeneration.UNKNOWN) {
            when {
                speedStr != null      -> speedStr
                downlinkMbps != null  -> String.format("Link Speed: %.0f Mbps", downlinkMbps)
                else                  -> null
            }
        } else {
            null
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_signal_bars)
            .setContentTitle(title)
            .apply {
                if (contentText != null) setContentText(contentText)
            }
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
    }

    /**
     * Fires an alert notification for a network transition.
     */
    fun fireAlertNotification(
        from: NetworkGeneration,
        to: NetworkGeneration,
        transitionType: NetworkDetector.TransitionType
    ) {
        val (title, body) = getAlertContent(from, to, transitionType)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = getAlertChannelId()
        ensureAlertChannelCreated(channelId)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_signal_bars)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

        try {
            notificationManager.notify(alertNotificationId++, notification)
        } catch (e: SecurityException) {
            // Notification permission not granted
        }
    }

    /**
     * Fires an unstable signal notification (debounced).
     */
    fun fireUnstableSignalNotification() {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = getAlertChannelId()
        ensureAlertChannelCreated(channelId)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_signal_bars)
            .setContentTitle(context.getString(R.string.alert_unstable_title))
            .setContentText(context.getString(R.string.alert_unstable_body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            notificationManager.notify(alertNotificationId++, notification)
        } catch (e: SecurityException) {
            // Notification permission not granted
        }
    }

    /**
     * Returns (title, body) pair for an alert notification.
     */
    private fun getAlertContent(
        from: NetworkGeneration,
        to: NetworkGeneration,
        type: NetworkDetector.TransitionType
    ): Pair<String, String> {
        return when (type) {
            NetworkDetector.TransitionType.DATA_LOST -> Pair(
                context.getString(R.string.alert_data_lost_title),
                context.getString(R.string.alert_data_lost_body)
            )
            NetworkDetector.TransitionType.DATA_RESTORED -> Pair(
                context.getString(R.string.alert_data_restored_title),
                context.getString(R.string.alert_data_restored_body, to.displayName)
            )
            NetworkDetector.TransitionType.DOWNGRADE -> getDowngradeContent(from, to)
            NetworkDetector.TransitionType.UPGRADE -> getUpgradeContent(from, to)
            else -> Pair("Network changed", "Network changed from ${from.displayName} to ${to.displayName}")
        }
    }

    private fun getDowngradeContent(from: NetworkGeneration, to: NetworkGeneration): Pair<String, String> {
        return when {
            (from == NetworkGeneration.FIVE_G || from == NetworkGeneration.FIVE_G_NSA || from == NetworkGeneration.FIVE_G_SA) && to == NetworkGeneration.FOUR_G ->
                Pair(context.getString(R.string.alert_5g_to_4g_title), context.getString(R.string.alert_5g_to_4g_body))
            from == NetworkGeneration.FOUR_G && to == NetworkGeneration.THREE_G ->
                Pair(context.getString(R.string.alert_4g_to_3g_title), context.getString(R.string.alert_4g_to_3g_body))
            from == NetworkGeneration.THREE_G && to == NetworkGeneration.TWO_G ->
                Pair(context.getString(R.string.alert_3g_to_2g_title), context.getString(R.string.alert_3g_to_2g_body))
            else ->
                Pair(
                    context.getString(R.string.alert_any_downgrade_title),
                    context.getString(R.string.alert_any_downgrade_body, from.displayName, to.displayName)
                )
        }
    }

    private fun getUpgradeContent(from: NetworkGeneration, to: NetworkGeneration): Pair<String, String> {
        return when {
            from == NetworkGeneration.FOUR_G && (to == NetworkGeneration.FIVE_G || to == NetworkGeneration.FIVE_G_NSA || to == NetworkGeneration.FIVE_G_SA) ->
                Pair(context.getString(R.string.alert_4g_to_5g_title), context.getString(R.string.alert_4g_to_5g_body))
            from == NetworkGeneration.THREE_G && to == NetworkGeneration.FOUR_G ->
                Pair(context.getString(R.string.alert_3g_to_4g_title), context.getString(R.string.alert_3g_to_4g_body))
            from == NetworkGeneration.TWO_G && to == NetworkGeneration.THREE_G ->
                Pair(context.getString(R.string.alert_2g_to_3g_title), context.getString(R.string.alert_2g_to_3g_body))
            else ->
                Pair(
                    "⬆ Network upgraded",
                    "Your network improved from ${from.displayName} to ${to.displayName}."
                )
        }
    }

    /**
     * Checks whether a specific alert preference is enabled.
     */
    fun shouldAlertForTransition(
        @Suppress("UNUSED_PARAMETER") from: NetworkGeneration,
        @Suppress("UNUSED_PARAMETER") to: NetworkGeneration,
        transitionType: NetworkDetector.TransitionType
    ): Boolean {
        val anyChange = prefs.getBoolean("pref_alert_any_change", false)
        if (anyChange) return true

        return when (transitionType) {
            NetworkDetector.TransitionType.DATA_LOST, NetworkDetector.TransitionType.DATA_RESTORED ->
                prefs.getBoolean("pref_alert_data_connection", true)
            NetworkDetector.TransitionType.DOWNGRADE ->
                prefs.getBoolean("pref_alert_downgrade", true)
            NetworkDetector.TransitionType.UPGRADE ->
                prefs.getBoolean("pref_alert_upgrade", false)
            else -> false
        }
    }
}


