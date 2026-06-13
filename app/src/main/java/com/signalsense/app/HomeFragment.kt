package com.signalsense.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.signalsense.app.ui.NetworkBadgeView

class HomeFragment : Fragment() {

    // Views
    private lateinit var bannerPermission: MaterialCardView
    private lateinit var tvPermissionWarning: TextView
    private lateinit var btnGrantPermission: MaterialButton
    private lateinit var cardNetworkStatus: View
    private lateinit var badgeLive: View
    private lateinit var viewBadgeCircle: View
    private lateinit var viewNetworkGlow: View
    private lateinit var tvNetworkBadge: TextView
    private lateinit var tvNetworkBadgeSubtype: TextView
    private lateinit var tvCarrierName: TextView
    private lateinit var tvNetworkSubtitle: TextView
    private lateinit var tvSpeedStats: TextView
    private lateinit var btnToggleMonitoring: MaterialButton
    private lateinit var tvSwitchCount: TextView
    private lateinit var tvDowngradeCount: TextView
    private lateinit var tvUptime: TextView

    private lateinit var cardSubtypeBadge: MaterialCardView
    private lateinit var tvSpeedValue: TextView
    private lateinit var tvSpeedUnit: TextView
    private lateinit var tvPingValue: TextView
    private lateinit var cardBanner5gNsa: MaterialCardView
    private lateinit var cardBanner4g: MaterialCardView
    private lateinit var cardBannerNoData: MaterialCardView

    // Data
    private lateinit var switchLogManager: SwitchLogManager
    private var isMonitoring = false
    private var monitoringStartTime = 0L
    private val handler = Handler(Looper.getMainLooper())

    // Uptime timer
    private val uptimeRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                updateUptimeDisplay()
                handler.postDelayed(this, 1000)
            }
        }
    }

    // Pulse animation
    private var pulseAnimator: ObjectAnimator? = null

    // Broadcast receiver for network changes and service status changes
    private val networkChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                NetworkMonitorService.ACTION_SERVICE_STARTED -> {
                    isMonitoring = true
                    monitoringStartTime = System.currentTimeMillis()
                    startUptimeTimer()
                    updateMonitoringButton()
                    updateUI()
                }
                NetworkMonitorService.ACTION_SERVICE_STOPPED -> {
                    isMonitoring = false
                    monitoringStartTime = 0L
                    handler.removeCallbacks(uptimeRunnable)
                    stopPulseAnimation()
                    tvUptime.text = "00:00"
                    updateMonitoringButton()
                    updateUI()
                }
                NetworkMonitorService.ACTION_NETWORK_CHANGED -> {
                    val toGenName = intent.getStringExtra(NetworkMonitorService.EXTRA_TO_NETWORK)
                    val speedDisplay = intent.getStringExtra(NetworkMonitorService.EXTRA_SPEED_DISPLAY)
                    val pingMs = intent.getIntExtra("extra_ping_ms", -1)
                    
                    val generation = if (toGenName != null) {
                        try { NetworkGeneration.valueOf(toGenName) } catch (e: Exception) { null }
                    } else null
                    
                    if (generation != null) {
                        updateNetworkBadge(generation)
                    } else {
                        updateUI()
                    }
                    
                    if (isMonitoring && generation != NetworkGeneration.NO_DATA && generation != NetworkGeneration.UNKNOWN) {
                        if (speedDisplay != null) {
                            val speedVal = speedDisplay.filter { it.isDigit() || it == '.' }
                            val speedUnit = speedDisplay.filter { !it.isDigit() && it != '.' }
                            tvSpeedValue.text = speedVal
                            tvSpeedUnit.text = speedUnit
                        } else {
                            val downlinkMbps = NetworkDetector.getDownlinkMbps(context)
                            if (downlinkMbps != null) {
                                when {
                                    downlinkMbps < 1f -> {
                                        tvSpeedValue.text = "%.0f".format(downlinkMbps * 1000)
                                        tvSpeedUnit.text = "Kbps"
                                    }
                                    downlinkMbps < 1000f -> {
                                        tvSpeedValue.text = "%.0f".format(downlinkMbps)
                                        tvSpeedUnit.text = "Mbps"
                                    }
                                    else -> {
                                        tvSpeedValue.text = "%.1f".format(downlinkMbps / 1000f)
                                        tvSpeedUnit.text = "Gbps"
                                    }
                                }
                            } else {
                                tvSpeedValue.text = "—"
                                tvSpeedUnit.text = "Mbps"
                            }
                        }
                        
                        if (pingMs >= 0) {
                            tvPingValue.text = pingMs.toString()
                        } else {
                            tvPingValue.text = "—"
                        }
                    } else {
                        tvSpeedValue.text = "—"
                        tvSpeedUnit.text = "Mbps"
                        tvPingValue.text = "—"
                    }
                    
                    updateStats()
                }
            }
        }
    }

    // Permission launcher
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val phoneGranted = permissions[Manifest.permission.READ_PHONE_STATE] ?: false
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else true
        
        checkPermissions()
        updateUI()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)
        initViews(view)
        setupClickListeners()
        switchLogManager = SwitchLogManager(requireContext())
        return view
    }

    override fun onResume() {
        super.onResume()
        // Register receiver for network change and service status broadcasts
        val filter = IntentFilter().apply {
            addAction(NetworkMonitorService.ACTION_NETWORK_CHANGED)
            addAction(NetworkMonitorService.ACTION_SERVICE_STARTED)
            addAction(NetworkMonitorService.ACTION_SERVICE_STOPPED)
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            networkChangeReceiver,
            filter
        )

        // Check current service state
        isMonitoring = isServiceRunning(requireContext())
        if (isMonitoring) {
            startUptimeTimer()
        } else {
            monitoringStartTime = 0L
        }
        
        checkPermissions()
        updateUI()
        updateMonitoringButton()
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(networkChangeReceiver)
        handler.removeCallbacks(uptimeRunnable)
        stopPulseAnimation()
    }

    private fun initViews(view: View) {
        bannerPermission = view.findViewById(R.id.bannerPermission)
        tvPermissionWarning = view.findViewById(R.id.tvPermissionWarning)
        btnGrantPermission = view.findViewById(R.id.btnGrantPermission)
        cardNetworkStatus = view.findViewById(R.id.cardNetworkStatus)
        badgeLive = view.findViewById(R.id.badgeLive)
        viewBadgeCircle = view.findViewById(R.id.viewBadgeCircle)
        viewNetworkGlow = view.findViewById(R.id.viewNetworkGlow)
        tvNetworkBadge = view.findViewById(R.id.tvNetworkBadge)
        tvNetworkBadgeSubtype = view.findViewById(R.id.tvNetworkBadgeSubtype)
        tvCarrierName = view.findViewById(R.id.tvCarrierName)
        tvNetworkSubtitle = view.findViewById(R.id.tvNetworkSubtitle)
        tvSpeedStats = view.findViewById(R.id.tvSpeedStats)
        btnToggleMonitoring = view.findViewById(R.id.btnToggleMonitoring)
        tvSwitchCount = view.findViewById(R.id.tvSwitchCount)
        tvDowngradeCount = view.findViewById(R.id.tvDowngradeCount)
        tvUptime = view.findViewById(R.id.tvUptime)

        cardSubtypeBadge = view.findViewById(R.id.cardSubtypeBadge)
        tvSpeedValue = view.findViewById(R.id.tvSpeedValue)
        tvSpeedUnit = view.findViewById(R.id.tvSpeedUnit)
        tvPingValue = view.findViewById(R.id.tvPingValue)
        cardBanner5gNsa = view.findViewById(R.id.cardBanner5gNsa)
        cardBanner4g = view.findViewById(R.id.cardBanner4g)
        cardBannerNoData = view.findViewById(R.id.cardBannerNoData)
    }

    private fun setupClickListeners() {
        btnToggleMonitoring.setOnClickListener {
            toggleMonitoring()
        }

        btnGrantPermission.setOnClickListener {
            val context = requireContext()
            
            if (bannerPermission.tag == "notification") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionsLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                } else {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    startActivity(intent)
                }
            } else {
                val permissionsNeeded = mutableListOf(Manifest.permission.READ_PHONE_STATE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && 
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                permissionsLauncher.launch(permissionsNeeded.toTypedArray())
            }
        }
    }

    private fun toggleMonitoring() {
        if (isMonitoring) {
            stopMonitoring()
        } else {
            startMonitoring()
        }
    }

    private fun startMonitoring() {
        val serviceIntent = Intent(requireContext(), NetworkMonitorService::class.java)
        ContextCompat.startForegroundService(requireContext(), serviceIntent)
        isMonitoring = true
        monitoringStartTime = System.currentTimeMillis()
        updateMonitoringButton()
        startUptimeTimer()
        Toast.makeText(requireContext(), "Monitoring started", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoring() {
        requireContext().stopService(Intent(requireContext(), NetworkMonitorService::class.java))
        isMonitoring = false
        monitoringStartTime = 0L
        updateMonitoringButton()
        handler.removeCallbacks(uptimeRunnable)
        stopPulseAnimation()
        Toast.makeText(requireContext(), "Monitoring stopped", Toast.LENGTH_SHORT).show()
        tvUptime.text = "00:00"
    }

    private fun updateMonitoringButton() {
        val context = requireContext()
        if (isMonitoring) {
            btnToggleMonitoring.text = getString(R.string.btn_stop_monitoring)
            btnToggleMonitoring.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.destructive)
            )
            btnToggleMonitoring.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            btnToggleMonitoring.iconTint = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_primary))
            badgeLive.visibility = View.VISIBLE
            startPulseAnimation()
        } else {
            btnToggleMonitoring.text = getString(R.string.btn_start_monitoring)
            btnToggleMonitoring.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.accent)
            )
            btnToggleMonitoring.setTextColor(ContextCompat.getColor(context, R.color.accent_text_on))
            btnToggleMonitoring.iconTint = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.accent_text_on))
            badgeLive.visibility = View.GONE
            stopPulseAnimation()
        }
    }

    private fun updateUI() {
        if (!isAdded) return
        val context = requireContext()
        val isServiceActive = isServiceRunning(context)
        
        val generation = if (isServiceActive) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val genName = prefs.getString("pref_current_generation", NetworkGeneration.UNKNOWN.name)
            try { NetworkGeneration.valueOf(genName ?: NetworkGeneration.UNKNOWN.name) } catch (e: Exception) { NetworkGeneration.UNKNOWN }
        } else {
            NetworkDetector.getNetworkGeneration(context)
        }
        
        updateNetworkBadge(generation)
        updateSpeedStats()
        updateStats()
    }

    private fun updateNetworkBadge(generation: NetworkGeneration) {
        if (!isAdded) return
        val context = requireContext()

        // Circle badge text
        tvNetworkBadge.text = when (generation) {
            NetworkGeneration.FIVE_G_SA, NetworkGeneration.FIVE_G_NSA, NetworkGeneration.FIVE_G -> "5G"
            NetworkGeneration.FOUR_G -> "4G"
            NetworkGeneration.THREE_G -> "3G"
            NetworkGeneration.TWO_G -> "2G"
            NetworkGeneration.NO_DATA -> "No\nData"
            else -> "?"
        }
        // Adjust text size for No Data (multi-line)
        tvNetworkBadge.textSize = if (generation == NetworkGeneration.NO_DATA) 18f else 36f

        // Subtype text inside circle (SA / NSA)
        when (generation) {
            NetworkGeneration.FIVE_G_SA -> {
                tvNetworkBadgeSubtype.visibility = View.VISIBLE
                tvNetworkBadgeSubtype.text = "SA"
            }
            NetworkGeneration.FIVE_G_NSA -> {
                tvNetworkBadgeSubtype.visibility = View.VISIBLE
                tvNetworkBadgeSubtype.text = "NSA"
            }
            else -> {
                tvNetworkBadgeSubtype.visibility = View.GONE
            }
        }

        // Network color for circle and glow
        val networkColor = ContextCompat.getColor(context, when (generation) {
            NetworkGeneration.FIVE_G_SA, NetworkGeneration.FIVE_G_NSA, NetworkGeneration.FIVE_G -> R.color.color_5g
            NetworkGeneration.FOUR_G -> R.color.color_4g
            NetworkGeneration.THREE_G -> R.color.color_3g
            NetworkGeneration.TWO_G -> R.color.color_2g
            NetworkGeneration.NO_DATA -> R.color.color_no_data
            else -> R.color.color_no_data
        })

        // Apply color to circle and glow
        viewBadgeCircle.backgroundTintList = ColorStateList.valueOf(networkColor)
        viewNetworkGlow.backgroundTintList = ColorStateList.valueOf(networkColor)

        val carrier = NetworkDetector.getCarrierName(context)
        val subtitle = NetworkBadgeView.getSubtitleForGeneration(context, generation)

        // Carrier name in teal accent, subtitle in grey
        if (generation == NetworkGeneration.NO_DATA || carrier.isNullOrBlank()) {
            tvCarrierName.visibility = View.GONE
            tvNetworkSubtitle.text = subtitle
        } else {
            tvCarrierName.visibility = View.VISIBLE
            tvCarrierName.text = carrier
            tvNetworkSubtitle.text = subtitle
        }

        // Banners visibility based on network generation
        cardBanner5gNsa.visibility = if (generation == NetworkGeneration.FIVE_G_NSA) View.VISIBLE else View.GONE
        cardBanner4g.visibility = if (generation == NetworkGeneration.FOUR_G) View.VISIBLE else View.GONE
        cardBannerNoData.visibility = if (generation == NetworkGeneration.NO_DATA) View.VISIBLE else View.GONE

        // Subtle scale bounce on transition
        viewBadgeCircle.animate()
            .scaleX(1.06f)
            .scaleY(1.06f)
            .setDuration(120)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                viewBadgeCircle.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120)
                    .start()
            }
            .start()
    }

    private fun updateSpeedStats() {
        if (!isAdded) return
        val context = requireContext()
        if (!isMonitoring) {
            tvSpeedValue.text = "—"
            tvSpeedUnit.text = "Mbps"
            tvPingValue.text = "—"
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val genName = prefs.getString("pref_current_generation", NetworkGeneration.UNKNOWN.name)
        val generation = try { NetworkGeneration.valueOf(genName ?: NetworkGeneration.UNKNOWN.name) } catch (e: Exception) { NetworkGeneration.UNKNOWN }

        if (generation == NetworkGeneration.NO_DATA || generation == NetworkGeneration.UNKNOWN) {
            tvSpeedValue.text = "—"
            tvSpeedUnit.text = "Mbps"
            tvPingValue.text = "—"
            return
        }

        val downlinkMbps = NetworkDetector.getDownlinkMbps(context)
        if (downlinkMbps != null) {
            when {
                downlinkMbps < 1f -> {
                    tvSpeedValue.text = "%.0f".format(downlinkMbps * 1000)
                    tvSpeedUnit.text = "Kbps"
                }
                downlinkMbps < 1000f -> {
                    tvSpeedValue.text = "%.0f".format(downlinkMbps)
                    tvSpeedUnit.text = "Mbps"
                }
                else -> {
                    tvSpeedValue.text = "%.1f".format(downlinkMbps / 1000f)
                    tvSpeedUnit.text = "Gbps"
                }
            }
        } else {
            tvSpeedValue.text = "—"
            tvSpeedUnit.text = "Mbps"
        }
        tvPingValue.text = "—"
    }

    private fun updateStats() {
        tvSwitchCount.text = switchLogManager.getSwitchCount().toString()
        tvDowngradeCount.text = switchLogManager.getDowngradeCount().toString()
    }



    private fun updateUptimeDisplay() {
        if (monitoringStartTime == 0L) {
            tvUptime.text = "00:00"
            return
        }

        val elapsed = System.currentTimeMillis() - monitoringStartTime
        val totalSeconds = elapsed / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600

        tvUptime.text = if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun startUptimeTimer() {
        if (monitoringStartTime == 0L) {
            monitoringStartTime = System.currentTimeMillis()
        }
        handler.removeCallbacks(uptimeRunnable)
        handler.post(uptimeRunnable)
    }

    private fun startPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = ObjectAnimator.ofFloat(btnToggleMonitoring, "alpha", 1f, 0.7f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        btnToggleMonitoring.alpha = 1f
    }

    private fun checkPermissions() {
        if (!isAdded) return
        val context = requireContext()
        // 1. Phone State Permission
        val hasPhone = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hasNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        if (!hasPhone) {
            showPermissionBanner(
                getString(R.string.permission_phone_state_rationale),
                isNotification = false
            )
        } else if (!hasNotification) {
            showPermissionBanner(
                getString(R.string.permission_notification_rationale),
                isNotification = true
            )
        } else {
            bannerPermission.visibility = View.GONE
        }
    }

    private fun showPermissionBanner(message: String, isNotification: Boolean) {
        bannerPermission.visibility = View.VISIBLE
        tvPermissionWarning.text = message
        bannerPermission.tag = if (isNotification) "notification" else "phone"
        btnGrantPermission.text = if (isNotification) {
            getString(R.string.btn_open_settings)
        } else {
            getString(R.string.btn_grant)
        }
    }

    private fun isServiceRunning(context: Context): Boolean {
        return NetworkMonitorService.isServiceRunning
    }
}


