package com.signalsense.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.telephony.SubscriptionManager
import android.view.LayoutInflater
import android.content.ClipData
import android.content.ClipboardManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.google.android.material.materialswitch.MaterialSwitch


/**
 * Settings fragment with a fully customized, premium UI design.
 * Manages the data sync service schedules and various user preference switches.
 */
class SettingsFragment : Fragment() {

    private lateinit var switchAutostart: MaterialSwitch
    private lateinit var switchAutostop: MaterialSwitch
    private lateinit var switchAlertDowngrade: MaterialSwitch
    private lateinit var switchAlertUpgrade: MaterialSwitch
    private lateinit var switchAlertAnyChange: MaterialSwitch
    private lateinit var switchAlertDataConn: MaterialSwitch

    private lateinit var switchVibrateAlert: MaterialSwitch
    private lateinit var rowManageNotifications: View

    // Ringtone Picker views
    private lateinit var rowRingtone: View
    private lateinit var tvRingtoneValue: TextView

    private lateinit var switchShowSpeed: MaterialSwitch
    private lateinit var switchShowPing: MaterialSwitch

    private lateinit var rowSimSlot: View
    private lateinit var dividerSimSlot: View
    private lateinit var tvSimSlotValue: TextView
    private lateinit var switchBoot: MaterialSwitch

    // Battery Optimization Views
    private lateinit var tvBatteryOptStatus: TextView
    private lateinit var rowBatteryOpt: View
    private lateinit var tvBatteryOptSummary: TextView

    private var pendingToggle: MaterialSwitch? = null
    private var isUpdatingSwitches = false

    private val batteryOptLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        val context = context ?: return@registerForActivityResult
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        Log.d("SettingsFragment", "Returned from battery optimization screen. isIgnoringBatteryOptimizations: $isIgnoring")
        if (isIgnoring) {
            Log.d("SettingsFragment", "Battery optimization exemption approved.")
            updateBatteryOptStatus()
            pendingToggle?.isChecked = true
        } else {
            Log.d("SettingsFragment", "Battery optimization exemption denied/cancelled.")
        }
        pendingToggle = null
    }

    private val ringtonePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uri = data?.let {
                IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            }
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            if (uri != null) {
                if (uri == RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)) {
                    prefs.edit().putString("pref_notification_ringtone", "default").apply()
                } else {
                    prefs.edit().putString("pref_notification_ringtone", uri.toString()).apply()
                }
            } else {
                prefs.edit().putString("pref_notification_ringtone", "silent").apply()
            }
            updateRingtoneDisplay()
            context?.let { NotificationHelper(it).createNotificationChannels() }
        }
    }


    private lateinit var tvVersionValue: TextView
    private lateinit var rowRate: View
    private lateinit var rowWebsite: View
    private lateinit var rowFeedback: View
    private lateinit var rowDonateUpi: View
    private lateinit var rowDonateGithub: View
    private lateinit var rowDonateKofi: View
    private lateinit var rowPrivacy: View
    private lateinit var rowLicenses: View

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)
        initViews(view)
        setupPreferences()
        setupClickListeners()
        return view
    }

    private fun initViews(view: View) {
        switchAutostart = view.findViewById(R.id.switchAutostart)
        switchAutostop = view.findViewById(R.id.switchAutostop)
        switchAlertDowngrade = view.findViewById(R.id.switchAlertDowngrade)
        switchAlertUpgrade = view.findViewById(R.id.switchAlertUpgrade)
        switchAlertAnyChange = view.findViewById(R.id.switchAlertAnyChange)
        switchAlertDataConn = view.findViewById(R.id.switchAlertDataConn)

        switchVibrateAlert = view.findViewById(R.id.switchVibrateAlert)
        rowManageNotifications = view.findViewById(R.id.rowManageNotifications)

        rowRingtone = view.findViewById(R.id.rowRingtone)
        tvRingtoneValue = view.findViewById(R.id.tvRingtoneValue)

        switchShowSpeed = view.findViewById(R.id.switchShowSpeed)
        switchShowPing = view.findViewById(R.id.switchShowPing)

        rowSimSlot = view.findViewById(R.id.rowSimSlot)
        dividerSimSlot = view.findViewById(R.id.dividerSimSlot)
        tvSimSlotValue = view.findViewById(R.id.tvSimSlotValue)
        switchBoot = view.findViewById(R.id.switchBoot)

        // Find battery optimization views
        tvBatteryOptStatus = view.findViewById(R.id.tvBatteryOptStatus)
        rowBatteryOpt = view.findViewById(R.id.rowBatteryOpt)
        tvBatteryOptSummary = view.findViewById(R.id.tvBatteryOptSummary)

        tvVersionValue = view.findViewById(R.id.tvVersionValue)
        rowRate = view.findViewById(R.id.rowRate)
        rowWebsite = view.findViewById(R.id.rowWebsite)
        rowFeedback = view.findViewById(R.id.rowFeedback)
        rowDonateUpi = view.findViewById(R.id.rowDonateUpi)
        rowDonateGithub = view.findViewById(R.id.rowDonateGithub)
        rowDonateKofi = view.findViewById(R.id.rowDonateKofi)
        rowPrivacy = view.findViewById(R.id.rowPrivacy)
        rowLicenses = view.findViewById(R.id.rowLicenses)
    }

    private fun setupPreferences() {
        // 1. Auto-start Section
        bindSwitch(switchAutostart, "pref_autostart_with_data", false) { enabled ->
            val context = requireContext()
            Log.d("SettingsFragment", "Auto-start toggle changed: $enabled")
            if (enabled) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                    Log.d("SettingsFragment", "Auto-start toggle enabled requested, but battery optimization is active. Prompting user.")
                    switchAutostart.isChecked = false
                    requestBatteryOptimizationExemptionIfNeeded(switchAutostart)
                } else {
                    Log.d("SettingsFragment", "Auto-start toggle enabled. Scheduling job.")
                    MobileDataJobService.scheduleMobileDataJob(context)
                }
            } else {
                Log.d("SettingsFragment", "Auto-start toggle disabled. Cancelling job.")
                MobileDataJobService.cancelMobileDataJob(context)
            }
        }
        bindSwitch(switchAutostop, "pref_autostop_with_data", false)

        // 2. Alert Triggers Section
        val onIndividualTriggerChanged: (Boolean) -> Unit = { enabled ->
            if (!enabled) {
                isUpdatingSwitches = true
                switchAlertAnyChange.isChecked = false
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                    .putBoolean("pref_alert_any_change", false)
                    .apply()
                isUpdatingSwitches = false
            } else {
                if (switchAlertDowngrade.isChecked &&
                    switchAlertUpgrade.isChecked &&
                    switchAlertDataConn.isChecked) {
                    isUpdatingSwitches = true
                    switchAlertAnyChange.isChecked = true
                    PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                        .putBoolean("pref_alert_any_change", true)
                        .apply()
                    isUpdatingSwitches = false
                }
            }
        }
        bindSwitch(switchAlertDowngrade, "pref_alert_downgrade", true, onIndividualTriggerChanged)
        bindSwitch(switchAlertUpgrade, "pref_alert_upgrade", false, onIndividualTriggerChanged)
        bindSwitch(switchAlertAnyChange, "pref_alert_any_change", false) { enabled ->
            if (enabled) {
                isUpdatingSwitches = true
                switchAlertDowngrade.isChecked = true
                switchAlertUpgrade.isChecked = true
                switchAlertDataConn.isChecked = true
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                    .putBoolean("pref_alert_downgrade", true)
                    .putBoolean("pref_alert_upgrade", true)
                    .putBoolean("pref_alert_data_connection", true)
                    .apply()
                isUpdatingSwitches = false
            }
        }
        bindSwitch(switchAlertDataConn, "pref_alert_data_connection", true, onIndividualTriggerChanged)

        // 3. Alert Behaviour Section
        bindSwitch(switchVibrateAlert, "pref_vibrate_on_alert", true) {
            context?.let { NotificationHelper(it).createNotificationChannels() }
        }
        updateRingtoneDisplay()

        // 4. Display Section
        bindSwitch(switchShowSpeed, "pref_show_speed", false)
        bindSwitch(switchShowPing, "pref_show_ping", false)

        // 5. Advanced Section (Sim slot selector + start on boot)
        updateSimSlotDisplay()
        checkSimSlots()
        bindSwitch(switchBoot, "auto_start_on_boot", false) { enabled ->
            Log.d("SettingsFragment", "Auto-start on boot toggle changed: $enabled")
            if (enabled) {
                val context = requireContext()
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                    Log.d("SettingsFragment", "Auto-start on boot toggle enabled requested, but battery optimization is active. Prompting user.")
                    switchBoot.isChecked = false
                    requestBatteryOptimizationExemptionIfNeeded(switchBoot)
                } else {
                    Log.d("SettingsFragment", "Auto-start on boot toggle enabled (optimizations already ignored).")
                }
            }
        }
        updateBatteryOptStatus()

        // 6. About Section
        tvVersionValue.text = BuildConfig.VERSION_NAME
    }

    private fun bindSwitch(
        switch: MaterialSwitch,
        key: String,
        defaultValue: Boolean,
        onCheckedChange: ((Boolean) -> Unit)? = null
    ) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        switch.isChecked = prefs.getBoolean(key, defaultValue)
        switch.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitches) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(key, isChecked).apply()
            onCheckedChange?.invoke(isChecked)
        }
    }

    private fun updateSimSlotDisplay() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val value = prefs.getString("data_sim_slot", "-1") ?: "-1"
        val entries = resources.getStringArray(R.array.sim_slot_entries)
        val values = resources.getStringArray(R.array.sim_slot_values)
        val index = values.indexOf(value)
        tvSimSlotValue.text = if (index >= 0 && index < entries.size) entries[index] else entries[0]
    }

    private fun checkSimSlots() {
        val context = context ?: return
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        var activeSimCount = 0
        if (hasPermission) {
            try {
                val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                if (subscriptionManager != null) {
                    activeSimCount = subscriptionManager.activeSubscriptionInfoCount
                }
            } catch (e: SecurityException) {
                // Ignore and treat as 0
            } catch (e: Exception) {
                // Ignore and treat as 0
            }
        }
        if (activeSimCount >= 2) {
            rowSimSlot.isEnabled = true
            rowSimSlot.alpha = 1.0f
        } else {
            rowSimSlot.isEnabled = false
            rowSimSlot.alpha = 0.4f
        }
    }

    private fun setupClickListeners() {
        // Click action for active data SIM slot selector dialog
        rowSimSlot.setOnClickListener {
            showSimSlotDialog()
        }

        // Ignore battery optimization click action
        rowBatteryOpt.setOnClickListener {
            val context = context ?: return@setOnClickListener
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return@setOnClickListener
            val isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            if (isIgnoring) {
                val manufacturer = android.os.Build.MANUFACTURER.lowercase()
                val isSamsung = manufacturer.contains("samsung")
                val isOtherRestrictedOem = manufacturer.contains("xiaomi") ||
                        manufacturer.contains("redmi") ||
                        manufacturer.contains("poco") ||
                        manufacturer.contains("oneplus") ||
                        manufacturer.contains("oppo") ||
                        manufacturer.contains("realme") ||
                        manufacturer.contains("vivo")

                val builder = AlertDialog.Builder(context)
                    .setTitle(R.string.oem_dialog_title)

                val onOpenSettings = {
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Could not open settings", Toast.LENGTH_SHORT).show()
                    }
                }

                if (isSamsung) {
                    builder.setMessage(R.string.oem_dialog_body_samsung)
                        .setPositiveButton(R.string.oem_dialog_btn_open_settings) { _, _ -> onOpenSettings() }
                        .setNegativeButton(R.string.oem_dialog_btn_not_now, null)
                } else if (isOtherRestrictedOem) {
                    builder.setMessage(R.string.oem_dialog_body_other_oems)
                        .setPositiveButton(R.string.oem_dialog_btn_open_settings) { _, _ -> onOpenSettings() }
                        .setNegativeButton(R.string.oem_dialog_btn_not_now, null)
                } else {
                    builder.setMessage(R.string.oem_dialog_body_generic)
                        .setPositiveButton(android.R.string.ok, null)
                }
                builder.show()
            } else {
                requestBatteryOptimizationExemptionIfNeeded()
            }
        }

        // Rate app — auto-detect which store installed the app
        rowRate.setOnClickListener {
            val context = requireContext()
            val pkg = context.packageName
            val installer = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                try {
                    context.packageManager.getInstallSourceInfo(pkg).installingPackageName
                } catch (e: Exception) {
                    null
                }
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(pkg)
            }
            val url = when (installer) {
                "com.android.vending"        -> "https://play.google.com/store/apps/details?id=$pkg"
                "com.amazon.venezia"         -> "https://www.amazon.com/gp/mas/dl/android?p=$pkg"
                "org.fdroid.fdroid",
                "org.fdroid.basic"           -> "https://f-droid.org/packages/$pkg"
                "com.huawei.appmarket"       -> "https://appgallery.huawei.com/#/app/$pkg"
                "com.xiaomi.mipicks"         -> "https://app.mi.com/details?id=$pkg"   // Xiaomi GetApps (Redmi/POCO)
                "com.heytap.market"          -> "https://store.heytap.com/detail.html?app_id=$pkg" // OPPO / OnePlus (ColorOS)
                "com.realme.market"          -> "https://store.heytap.com/detail.html?app_id=$pkg" // Realme App Market
                "cm.aptoide.pt"              -> "https://aptoide.com/app/$pkg"
                "org.uptodown.android.store" -> "https://signalsense.en.uptodown.com/android"
                else                         -> "https://signalsense.vercel.app"
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                // No browser installed — ignore
            }
        }

        // Visit website
        rowWebsite.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://signalsense.vercel.app")))
            } catch (e: Exception) {
                // No browser installed — ignore
            }
        }

        // Privacy Policy
        rowPrivacy.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://signalsense.vercel.app/privacy.html")))
            } catch (e: Exception) {
                // No browser installed — ignore
            }
        }

        // Open Source Licenses
        rowLicenses.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/adithya-creation/SignalSense/blob/main/LICENSE")))
            } catch (e: Exception) {
                // No browser installed — ignore
            }
        }

        // Send feedback
        rowFeedback.setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(getString(R.string.feedback_email)))
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.feedback_subject, BuildConfig.VERSION_NAME))
            }
            try {
                startActivity(Intent.createChooser(intent, getString(R.string.pref_feedback_title)))
            } catch (e: Exception) {
                // No email app installed
            }
        }

        // Donate via UPI (Indian Users)
        rowDonateUpi.setOnClickListener {
            val upiUri = Uri.parse("upi://pay?pa=adithyamittapally@ptaxis&pn=SignalSense&cu=INR")
            val intent = Intent(Intent.ACTION_VIEW, upiUri)
            try {
                startActivity(intent)
            } catch (e: Exception) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("UPI ID", "adithyamittapally@ptaxis")
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, getString(R.string.pref_support_upi_toast), Toast.LENGTH_LONG).show()
            }
        }

        // Donate via GitHub Sponsors (International)
        rowDonateGithub.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/sponsors/adithya-creation")))
            } catch (e: Exception) {
                // Fallback standard browser error handling
            }
        }

        // Donate via Ko-fi (Everyone Else)
        rowDonateKofi.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/adithyaglobal")))
            } catch (e: Exception) {
                // Fallback standard browser error handling
            }
        }

        // Manage Notification Settings
        rowManageNotifications.setOnClickListener {
            val context = context ?: return@setOnClickListener
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    // Fallback to app details settings
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    startActivity(intent)
                } catch (ex: Exception) {
                    Toast.makeText(context, "Could not open notification settings", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Ringtone Picker row click listener
        rowRingtone.setOnClickListener {
            val context = context ?: return@setOnClickListener
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select alert sound")

                val currentUriString = prefs.getString("pref_notification_ringtone", "default") ?: "default"
                val currentUri = when (currentUriString) {
                    "default" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    "silent" -> null
                    else -> Uri.parse(currentUriString)
                }
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)

                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            }
            try {
                ringtonePickerLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "Could not open ringtone picker", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateRingtoneDisplay() {
        val context = context ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val uriString = prefs.getString("pref_notification_ringtone", "default") ?: "default"

        when (uriString) {
            "default" -> {
                tvRingtoneValue.text = "Default"
            }
            "silent" -> {
                tvRingtoneValue.text = "Silent"
            }
            else -> {
                try {
                    val ringtone = RingtoneManager.getRingtone(context, Uri.parse(uriString))
                    tvRingtoneValue.text = ringtone?.getTitle(context) ?: "Unknown"
                } catch (e: Exception) {
                    tvRingtoneValue.text = "Default"
                }
            }
        }
    }



    private fun showSimSlotDialog() {
        val entries = resources.getStringArray(R.array.sim_slot_entries)
        val values = resources.getStringArray(R.array.sim_slot_values)
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val currentValue = prefs.getString("data_sim_slot", "-1") ?: "-1"
        val currentIndex = values.indexOf(currentValue).let { if (it >= 0) it else 0 }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pref_sim_slot_title)
            .setSingleChoiceItems(entries, currentIndex) { dialog, which ->
                val newValue = values[which]
                prefs.edit().putString("data_sim_slot", newValue).apply()
                updateSimSlotDisplay()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        updateBatteryOptStatus()
    }

    private fun updateBatteryOptStatus() {
        val context = context ?: return
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null) {
            val isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            if (isIgnoring) {
                tvBatteryOptStatus.text = getString(R.string.pref_battery_opt_status_active)
                tvBatteryOptStatus.setTextColor(ContextCompat.getColor(context, R.color.accent))
                tvBatteryOptSummary.text = getString(R.string.pref_battery_opt_active_summary)
            } else {
                tvBatteryOptStatus.text = getString(R.string.pref_battery_opt_status_not_set)
                tvBatteryOptStatus.setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                tvBatteryOptSummary.text = getString(R.string.pref_battery_opt_summary)
            }
        }
    }

    private fun requestBatteryOptimizationExemptionIfNeeded(pendingSwitch: MaterialSwitch? = null) {
        val context = context ?: return
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        if (!isIgnoring) {
            pendingToggle = pendingSwitch
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                batteryOptLauncher.launch(intent)
            } catch (e: Exception) {
                Log.d("SettingsFragment", "Direct ignore request failed, showing fallback instruction dialog.", e)
                showFallbackOptimizationSettingsDialog()
            }
        }
    }

    private fun showFallbackOptimizationSettingsDialog() {
        val context = context ?: return
        AlertDialog.Builder(context)
            .setTitle("Manual Configuration Required")
            .setMessage("On the next screen:\n\n1. Change the filter from 'Not optimized' to 'All apps' (if needed).\n2. Find SignalSense in the list.\n3. Tap it and choose 'Don\'t optimize' or 'Allow background activity'.")
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    batteryOptLauncher.launch(intent)
                } catch (ex: Exception) {
                    Log.d("SettingsFragment", "Settings list ignore request failed, showing App Details instruction dialog.", ex)
                    showAppDetailsSettingsDialog()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                pendingToggle = null
            }
            .setCancelable(false)
            .show()
    }

    private fun showAppDetailsSettingsDialog() {
        val context = context ?: return
        AlertDialog.Builder(context)
            .setTitle("Battery Restrictions")
            .setMessage("On the App Info screen:\n\n1. Tap 'Battery' or 'Power usage'.\n2. Select 'Unrestricted' or disable background restrictions.")
            .setPositiveButton("Open App Info") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    batteryOptLauncher.launch(intent)
                } catch (exc: Exception) {
                    Log.e("SettingsFragment", "Failed to open App details", exc)
                    Toast.makeText(context, "Could not open battery settings", Toast.LENGTH_SHORT).show()
                    pendingToggle = null
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                pendingToggle = null
            }
            .setCancelable(false)
            .show()
    }
}


