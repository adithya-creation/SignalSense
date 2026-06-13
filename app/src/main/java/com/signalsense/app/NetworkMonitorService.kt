package com.signalsense.app

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager

/**
 * Foreground service that monitors mobile network changes 24/7.
 *
 * Detection strategy (derived from production APK com.chaos.networkswitchalert):
 *   API 31+  → TelephonyCallback.onDisplayInfoChanged() — most accurate, handles 5G SA/NSA
 *   API 30   → PhoneStateListener with LISTEN_DISPLAY_INFO_CHANGED — same data, older API
 *   API < 30 → PhoneStateListener with LISTEN_DATA_CONNECTION_STATE — basic type detection
 *
 * Additional features from APK:
 *   - Call state tracking: suppress/delay alerts during active phone calls
 *   - TrafficStats-based live speed (real measured bytes/sec, updated every 1 s)
 *   - DEFAULT_DATA_SUBSCRIPTION_CHANGED receiver: re-attaches listener on SIM change
 *   - Debouncing: 3+ switches in 10 s → "unstable signal" notification instead of spam
 *   - START_STICKY: service restarts if killed
 */
class NetworkMonitorService : Service() {

    companion object {
        const val ACTION_NETWORK_CHANGED  = "com.signalsense.app.ACTION_NETWORK_CHANGED"
        const val ACTION_SERVICE_STARTED  = "com.signalsense.app.ACTION_SERVICE_STARTED"
        const val ACTION_SERVICE_STOPPED  = "com.signalsense.app.ACTION_SERVICE_STOPPED"
        const val EXTRA_FROM_NETWORK      = "extra_from_network"
        const val EXTRA_TO_NETWORK        = "extra_to_network"
        const val EXTRA_TRANSITION_TYPE   = "extra_transition_type"
        const val EXTRA_DOWNLINK_MBPS     = "extra_downlink_mbps"
        const val EXTRA_SPEED_DISPLAY     = "extra_speed_display"

        private const val TAG = "NetworkMonitorService"

        // Debounce thresholds
        private const val DEBOUNCE_WINDOW_MS   = 10_000L
        private const val DEBOUNCE_THRESHOLD   = 3
        private const val DEBOUNCE_DELAY_MS    = 3_000L    // settle delay before acting
        private const val CALL_RESUME_DELAY_MS = 1_500L    // resume after call ends

        // Speed polling
        private const val SPEED_POLL_MS = 1_000L

        @Volatile
        var isServiceRunning = false
    }

    private lateinit var notificationHelper: NotificationHelper
    private lateinit var switchLogManager: SwitchLogManager
    private val handler = Handler(Looper.getMainLooper())

    // Current known network state
    private var lastGeneration: NetworkGeneration = NetworkGeneration.UNKNOWN
    private var lastDisplayInfo: TelephonyDisplayInfo? = null

    // Telephony listener references (kept to unregister later)
    private var telephonyCallback: TelephonyCallback? = null        // API 31+
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null      // API < 31

    // Call state: suppress alerts when in a call
    private var isInCall = false
    private var pendingPostCallTransitionTag = 0

    // Debouncing
    private val recentSwitchTimestamps = mutableListOf<Long>()
    private var debounceRunnable: Runnable? = null

    // TrafficStats live speed
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastSpeedSampleMs = 0L
    private var currentRxBps = 0L   // current download bytes/sec
    private var currentTxBps = 0L   // current upload bytes/sec

    private var pingPollTicks = 0
    private var lastPingMs: Int? = null

    private val speedPollRunnable = object : Runnable {
        override fun run() {
            updateTrafficStats()
            if (prefs.getBoolean("pref_show_ping", false)) {
                pingPollTicks++
                if (pingPollTicks >= 3) {
                    pingPollTicks = 0
                    measurePingAsync()
                }
            } else {
                lastPingMs = null
                pingPollTicks = 0
            }
            updatePersistentNotification(lastGeneration)
            broadcastSpeedUpdate()
            handler.postDelayed(this, SPEED_POLL_MS)
        }
    }

    // Receivers
    private var simChangedReceiver: BroadcastReceiver? = null
    private var screenReceiver: BroadcastReceiver? = null

    // Dynamic connectivity monitor callback
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val prefs: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(this)
    }
    private val prefChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "data_sim_slot") {
                Log.d(TAG, "SIM slot pref changed — re-attaching telephony listener")
                scheduleReattach("prefs: SIM slot changed", 0L)
            } else if (key == "pref_show_ping") {
                val showPing = prefs.getBoolean("pref_show_ping", false)
                if (!showPing) {
                    lastPingMs = null
                    pingPollTicks = 0
                    updatePersistentNotification(lastGeneration)
                    broadcastSpeedUpdate()
                }
            }
        }

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        Log.d(TAG, "NetworkMonitorService onCreate: isServiceRunning set to true")
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_SERVICE_STARTED))
        notificationHelper = NotificationHelper(this)
        switchLogManager   = SwitchLogManager(this)

        // Seed TrafficStats baseline
        lastRxBytes = NetworkDetector.getTotalRxBytes()
        lastTxBytes = NetworkDetector.getTotalTxBytes()
        lastSpeedSampleMs = System.currentTimeMillis()

        // Register DEFAULT_DATA_SUBSCRIPTION_CHANGED (SIM switch detection)
        simChangedReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                Log.d(TAG, "Default data subscription changed — re-attaching listener")
                scheduleReattach("subscription changed", 0L)
            }
        }
        val simFilter = IntentFilter("android.telephony.action.DEFAULT_DATA_SUBSCRIPTION_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(simChangedReceiver, simFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(simChangedReceiver, simFilter)
        }

        // Listen for pref changes (SIM slot setting)
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)

        // Screen receiver — resume speed polling when screen comes back on
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_ON) {
                    handler.post { updateTrafficStats() }
                }
            }
        }
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
        )

        // Read initial generation via saved preference to maintain state across service restarts
        val savedGenName = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("pref_current_generation", NetworkGeneration.UNKNOWN.name)
        lastGeneration = try {
            NetworkGeneration.valueOf(savedGenName ?: NetworkGeneration.UNKNOWN.name)
        } catch (e: Exception) {
            NetworkGeneration.UNKNOWN
        }

        // Register dynamic network callback for reliable metered cellular updates
        registerNetworkCallback()

        // Start speed polling
        handler.postDelayed(speedPollRunnable, SPEED_POLL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notificationHelper.buildMonitorNotification(
            lastGeneration, NetworkDetector.getDownlinkMbps(this)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID_MONITOR,
                notification.build(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_MONITOR, notification.build())
        }

        // Attach telephony listener if not yet attached
        if (telephonyCallback == null && phoneStateListener == null) {
            attachTelephonyListener()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        Log.d(TAG, "NetworkMonitorService onDestroy: isServiceRunning set to false")
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_SERVICE_STOPPED))
        handler.removeCallbacksAndMessages(null)
        detachTelephonyListener()

        try { simChangedReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { screenReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener) } catch (_: Exception) {}

        networkCallback?.let { cb ->
            try { connectivityManager?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }

        if (prefs.getBoolean("pref_autostart_with_data", false)) {
            Log.d(TAG, "Service destroyed. Rescheduling MobileDataJobService because pref_autostart_with_data is true.")
            MobileDataJobService.scheduleMobileDataJob(this, 0)
        }
    }

    // ─── Telephony listener attachment ──────────────────────────────────────────

    private fun attachTelephonyListener() {
        if (!NetworkDetector.hasPhonePermission(this)) {
            Log.w(TAG, "READ_PHONE_STATE not granted — cannot attach telephony listener")
            stopSelf()
            return
        }

        val tm = NetworkDetector.getScopedTelephonyManager(this)

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                // API 31+ — TelephonyCallback
                attachTelephonyCallback(tm)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                // API 30 — PhoneStateListener with LISTEN_DISPLAY_INFO_CHANGED
                attachPhoneStateListenerWithDisplayInfo(tm)
            }
            else -> {
                // API < 30 — PhoneStateListener with data connection state
                attachLegacyPhoneStateListener(tm)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun attachTelephonyCallback(tm: TelephonyManager) {
        val cb = object : TelephonyCallback(),
            TelephonyCallback.DisplayInfoListener,
            TelephonyCallback.CallStateListener {

            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                lastDisplayInfo = info
                handleDisplayInfoChanged(info)
            }

            override fun onCallStateChanged(state: Int) {
                handleCallStateChanged(state)
            }
        }
        telephonyCallback = cb
        tm.registerTelephonyCallback(mainExecutor, cb)
        Log.d(TAG, "Attached TelephonyCallback (API 31+)")
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @Suppress("DEPRECATION")
    private fun attachPhoneStateListenerWithDisplayInfo(tm: TelephonyManager) {
        val listener = object : PhoneStateListener() {
            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                lastDisplayInfo = info
                handleDisplayInfoChanged(info)
            }

            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleCallStateChanged(state)
            }
        }
        phoneStateListener = listener
        @Suppress("DEPRECATION")
        tm.listen(listener,
            PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED or
            PhoneStateListener.LISTEN_CALL_STATE
        )
        Log.d(TAG, "Attached PhoneStateListener with LISTEN_DISPLAY_INFO_CHANGED (API 30)")
    }

    @Suppress("DEPRECATION")
    private fun attachLegacyPhoneStateListener(tm: TelephonyManager) {
        val listener = object : PhoneStateListener() {
            override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
                val gen = if (state == TelephonyManager.DATA_CONNECTED) {
                    NetworkDetector.generationFromNetworkType(networkType)
                } else {
                    NetworkGeneration.NO_DATA
                }
                handleGenerationChange(gen, bandInfo = null)
            }

            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleCallStateChanged(state)
            }
        }
        phoneStateListener = listener
        @Suppress("DEPRECATION")
        tm.listen(listener,
            PhoneStateListener.LISTEN_DATA_CONNECTION_STATE or
            PhoneStateListener.LISTEN_CALL_STATE
        )
        Log.d(TAG, "Attached legacy PhoneStateListener (API < 30)")
    }

    private fun detachTelephonyListener() {
        val tm = try {
            NetworkDetector.getScopedTelephonyManager(this)
        } catch (_: Exception) { null }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { cb ->
                try { tm?.unregisterTelephonyCallback(cb) } catch (_: Exception) {}
            }
            telephonyCallback = null
        }

        @Suppress("DEPRECATION")
        phoneStateListener?.let { listener ->
            try { tm?.listen(listener, PhoneStateListener.LISTEN_NONE) } catch (_: Exception) {}
        }
        phoneStateListener = null
    }

    /**
     * Schedules a re-attach of the telephony listener (e.g. after SIM change).
     * Cancels any previously scheduled re-attach first.
     */
    private fun scheduleReattach(reason: String, delayMs: Long) {
        Log.d(TAG, "Scheduling re-attach ($reason) in ${delayMs}ms")
        handler.removeCallbacksAndMessages("reattach")
        handler.postDelayed({
            detachTelephonyListener()
            attachTelephonyListener()
        }, delayMs)
    }

    // ─── Network change handlers ────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.R)
    private fun handleDisplayInfoChanged(info: TelephonyDisplayInfo) {
        val gen = NetworkDetector.generationFromDisplayInfo(info)
        // Best-effort band info from cell info (requires additional permission on some devices)
        val bandInfo = tryGetBandInfo(info)
        handleGenerationChange(gen, bandInfo)
    }

    /**
     * Core logic called whenever a new [NetworkGeneration] is determined.
     * - Ignores no-change events
     * - Debounces rapid oscillations
     * - Suppresses alerts during active calls
     * - Logs, notifies, and broadcasts on actual transitions
     */
    private fun handleGenerationChange(current: NetworkGeneration, bandInfo: String?) {
        val actualCurrent = if (NetworkDetector.isActiveDataSim(this)) current else NetworkGeneration.NO_DATA
        if (actualCurrent == lastGeneration) return

        val from = lastGeneration
        val to   = actualCurrent
        lastGeneration = actualCurrent

        // Save to SharedPreferences so fragments can read the accurate generation
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString("pref_current_generation", actualCurrent.name)
            .apply()

        Log.d(TAG, "Generation changed: $from → $to${if (bandInfo != null) " [$bandInfo]" else ""}")

        val transitionType = NetworkDetector.getTransitionType(from, to)
        if (transitionType == NetworkDetector.TransitionType.NONE) return

        // Acquire wakelock so notification fires even with screen off
        val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SignalSense::NetworkChange")
            .apply { acquire(10_000L) }

        try {
            val now = System.currentTimeMillis()
            recentSwitchTimestamps.add(now)
            recentSwitchTimestamps.removeAll { it < now - DEBOUNCE_WINDOW_MS }

            if (recentSwitchTimestamps.size > DEBOUNCE_THRESHOLD) {
                // Rapid oscillation — fire "unstable signal" once, clear pending
                debounceRunnable?.let { handler.removeCallbacks(it) }
                debounceRunnable = null
                recentSwitchTimestamps.clear()
                notificationHelper.fireUnstableSignalNotification()
                Log.d(TAG, "Unstable signal debounce triggered")
            } else {
                // Settle delay: cancel previous pending, schedule this one
                debounceRunnable?.let { handler.removeCallbacks(it) }
                val run = Runnable {
                    if (!isInCall) {
                        processTransition(from, to, transitionType)
                    } else {
                        Log.d(TAG, "In call — deferring alert for $to")
                    }
                }
                debounceRunnable = run
                handler.postDelayed(run, DEBOUNCE_DELAY_MS)
            }

            // Always update persistent notification and log immediately
            updatePersistentNotification(to)
            logTransition(from, to, bandInfo)
            broadcastNetworkChange(from, to, transitionType)

        } finally {
            try { wakeLock.release() } catch (_: Exception) {}
        }
    }

    private fun handleCallStateChanged(state: Int) {
        val inCall = (state != TelephonyManager.CALL_STATE_IDLE)
        if (inCall == isInCall) return

        isInCall = inCall
        Log.d(TAG, "Call state → ${if (inCall) "IN_CALL" else "IDLE"}")

        if (!inCall) {
            // Call ended — resume any suppressed alert after a short delay
            handler.postDelayed({ debounceRunnable?.run() }, CALL_RESUME_DELAY_MS)
        }
    }

    // ─── Transition processing ─────────────────────────────────────────────────

    private fun processTransition(
        from: NetworkGeneration,
        to: NetworkGeneration,
        transitionType: NetworkDetector.TransitionType
    ) {
        if (notificationHelper.shouldAlertForTransition(from, to, transitionType)) {
            notificationHelper.fireAlertNotification(from, to, transitionType)
        }
    }

    // ─── Logging ────────────────────────────────────────────────────────────────

    private fun logTransition(from: NetworkGeneration, to: NetworkGeneration, bandInfo: String?) {
        if (from == NetworkGeneration.UNKNOWN) return // Skip logging initial startup state from Unknown
        val entry = SwitchLogEntry(
            timestamp    = System.currentTimeMillis(),
            fromNetwork  = from.displayName,
            toNetwork    = to.displayName,
            downlinkMbps = NetworkDetector.getDownlinkMbps(this),
            rttMs        = lastPingMs,
            bandInfo     = bandInfo
        )
        switchLogManager.addEntry(entry)
    }

    // ─── Notifications & broadcasts ─────────────────────────────────────────────

    private fun updatePersistentNotification(generation: NetworkGeneration) {
        val speedStr = if (currentRxBps > 0 || currentTxBps > 0) {
            "↓ ${NetworkDetector.formatSpeed(currentRxBps)}  ↑ ${NetworkDetector.formatSpeed(currentTxBps)}"
        } else null
        val downlinkMbps = NetworkDetector.getDownlinkMbps(this)
        val notification = notificationHelper.buildMonitorNotification(generation, downlinkMbps, speedStr)
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NotificationHelper.NOTIFICATION_ID_MONITOR, notification.build())
        } catch (_: Exception) {}
    }

    private fun broadcastNetworkChange(
        from: NetworkGeneration,
        to: NetworkGeneration,
        transitionType: NetworkDetector.TransitionType
    ) {
        val intent = Intent(ACTION_NETWORK_CHANGED).apply {
            putExtra(EXTRA_FROM_NETWORK, from.name)
            putExtra(EXTRA_TO_NETWORK, to.name)
            putExtra(EXTRA_TRANSITION_TYPE, transitionType.name)
            NetworkDetector.getDownlinkMbps(this@NetworkMonitorService)?.let {
                putExtra(EXTRA_DOWNLINK_MBPS, it)
            }
            lastPingMs?.let {
                putExtra("extra_ping_ms", it)
            }
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastSpeedUpdate() {
        val intent = Intent(ACTION_NETWORK_CHANGED).apply {
            putExtra(EXTRA_TO_NETWORK, lastGeneration.name)
            if (currentRxBps > 0) putExtra(EXTRA_SPEED_DISPLAY, NetworkDetector.formatSpeed(currentRxBps))
            NetworkDetector.getDownlinkMbps(this@NetworkMonitorService)?.let {
                putExtra(EXTRA_DOWNLINK_MBPS, it)
            }
            lastPingMs?.let {
                putExtra("extra_ping_ms", it)
            }
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun measurePingAsync() {
        Log.d(TAG, "measurePingAsync entry: lastGeneration=$lastGeneration")
        if (!prefs.getBoolean("pref_show_ping", false) || lastGeneration == NetworkGeneration.NO_DATA) {
            lastPingMs = null
            return
        }
        Thread {
            Log.d(TAG, "Starting ping measurement...")
            // 1. Try ICMP ping to 1.1.1.1
            var ping = runIcmpPing("1.1.1.1")
            Log.d(TAG, "ICMP ping 1.1.1.1 result: $ping ms")
            
            // 2. If fails, try ICMP ping to 8.8.8.8
            if (ping == null) {
                ping = runIcmpPing("8.8.8.8")
                Log.d(TAG, "ICMP ping 8.8.8.8 result: $ping ms")
            }
            
            // 3. If still fails, fall back to TCP connect to 1.1.1.1 on port 80
            if (ping == null) {
                ping = runTcpPing("1.1.1.1", 80, 1000)
                Log.d(TAG, "TCP ping 1.1.1.1:80 result: $ping ms")
            }
            
            // 4. If still fails, fall back to TCP connect to connectivitycheck.gstatic.com on port 80
            if (ping == null) {
                ping = runTcpPing("connectivitycheck.gstatic.com", 80, 1000)
                Log.d(TAG, "TCP ping connectivitycheck.gstatic.com:80 result: $ping ms")
            }

            handler.post {
                lastPingMs = ping
                Log.d(TAG, "Finished ping measurement. Final ping: $lastPingMs ms")
                broadcastSpeedUpdate()
            }
        }.start()
    }

    private fun runIcmpPing(host: String): Int? {
        return try {
            val process = java.lang.Runtime.getRuntime().exec("ping -c 1 -w 1 $host")
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            var line: String?
            var timeMs: Int? = null
            while (reader.readLine().also { line = it } != null) {
                if (line != null && line!!.contains("time=")) {
                    val match = Regex("time=([\\d.]+)\\s*ms").find(line!!)
                    if (match != null) {
                        val timeStr = match.groupValues[1]
                        timeMs = timeStr.toDoubleOrNull()?.toInt()
                        break
                    }
                }
            }
            process.waitFor()
            if (process.exitValue() == 0) timeMs else {
                Log.d(TAG, "ICMP ping exit value non-zero: ${process.exitValue()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "ICMP ping exception for $host", e)
            null
        }
    }

    private fun runTcpPing(host: String, port: Int, timeoutMs: Int): Int? {
        return try {
            val startTime = System.currentTimeMillis()
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            socket.close()
            (System.currentTimeMillis() - startTime).toInt()
        } catch (e: Exception) {
            Log.d(TAG, "TCP ping failed/exception for $host:$port: ${e.message}")
            null
        }
    }

    // ─── TrafficStats speed ──────────────────────────────────────────────────────

    /**
     * Computes real-time RX/TX bytes/sec from TrafficStats deltas.
     * Derived from APK's TrafficStats usage in onCreate() + speed runnable.
     */
    private fun updateTrafficStats() {
        val now  = System.currentTimeMillis()
        val rx   = NetworkDetector.getTotalRxBytes()
        val tx   = NetworkDetector.getTotalTxBytes()
        val elapsedMs = now - lastSpeedSampleMs

        if (elapsedMs > 0 && lastSpeedSampleMs > 0) {
            val elapsedSec = elapsedMs / 1000.0
            currentRxBps = ((rx - lastRxBytes) / elapsedSec).toLong().coerceAtLeast(0L)
            currentTxBps = ((tx - lastTxBytes) / elapsedSec).toLong().coerceAtLeast(0L)
        }

        lastRxBytes       = rx
        lastTxBytes       = tx
        lastSpeedSampleMs = now
    }

    // ─── Band info helper ─────────────────────────────────────────────────────────

    /**
     * Attempts to extract the current band info using CellInfo APIs.
     * Returns a formatted string like "B3 · 1800 MHz" or "n78 · 3.5 GHz", or null.
     *
     * Requires ACCESS_FINE_LOCATION on API 29+ for getAllCellInfo(); gracefully returns null
     * if permission is not granted.
     */
    private fun tryGetBandInfo(@Suppress("UNUSED_PARAMETER") info: TelephonyDisplayInfo?): String? {
        return try {
            val tm = NetworkDetector.getScopedTelephonyManager(this)
            @Suppress("DEPRECATION")
            val cells = tm.allCellInfo ?: return null

            for (cell in cells) {
                if (!cell.isRegistered) continue
                when (cell) {
                    is android.telephony.CellInfoLte -> {
                        val earfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            cell.cellIdentity.earfcn
                        } else {
                            @Suppress("DEPRECATION")
                            cell.cellIdentity.earfcn
                        }
                        NetworkDetector.getBandInfoLte(earfcn)?.let { return it }
                    }
                    is android.telephony.CellInfoNr -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val nrId = cell.cellIdentity as? android.telephony.CellIdentityNr
                            nrId?.nrarfcn?.let { arfcn ->
                                NetworkDetector.getBandInfoNr(arfcn)?.let { return it }
                            }
                        }
                    }
                    else -> {}
                }
            }
            null
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
            
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Cellular network available")
                val gen = NetworkDetector.getNetworkGeneration(this@NetworkMonitorService)
                handler.post { handleGenerationChange(gen, null) }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Cellular network lost")
                handler.post {
                    handleGenerationChange(NetworkGeneration.NO_DATA, null)
                    
                    val prefs = PreferenceManager.getDefaultSharedPreferences(this@NetworkMonitorService)
                    val autoStop = prefs.getBoolean("pref_autostop_with_data", false)
                    if (autoStop) {
                        Log.d(TAG, "Auto-stop triggered — stopping monitoring service")
                        stopSelf()
                    }
                }
            }
        }
        
        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }
}


