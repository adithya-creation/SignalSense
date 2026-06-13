package com.signalsense.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

/**
 * Main activity displaying the BottomNavigationView container and managing Home, History, and Settings tabs.
 * Also handles runtime permissions and first-launch onboarding.
 */
class MainActivity : AppCompatActivity() {

    // Custom tab views
    private lateinit var dockHome: View
    private lateinit var dockHistory: View
    private lateinit var dockSettings: View
    private lateinit var indicatorHome: View
    private lateinit var indicatorHistory: View
    private lateinit var indicatorSettings: View
    private lateinit var iconHome: android.widget.ImageView
    private lateinit var iconHistory: android.widget.ImageView
    private lateinit var iconSettings: android.widget.ImageView

    // Permission launchers
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        refreshActiveFragment()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dockHome = findViewById(R.id.dock_home)
        dockHistory = findViewById(R.id.dock_history)
        dockSettings = findViewById(R.id.dock_settings)
        indicatorHome = findViewById(R.id.indicator_home)
        indicatorHistory = findViewById(R.id.indicator_history)
        indicatorSettings = findViewById(R.id.indicator_settings)
        iconHome = findViewById(R.id.icon_home)
        iconHistory = findViewById(R.id.icon_history)
        iconSettings = findViewById(R.id.icon_settings)

        setupCustomNavigation()

        // Check first launch
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean("pref_first_launch", true)) {
            showOnboarding()
            prefs.edit()
                .putBoolean("pref_first_launch", false)
                .putString("pref_notification_ringtone", "default")
                .apply()
        } else {
            checkPermissions()
        }

        // Schedule job for auto-start if setting is active
        if (prefs.getBoolean("pref_autostart_with_data", false)) {
            MobileDataJobService.scheduleMobileDataJob(this)
        }

        // Set default fragment if nothing is saved
        if (savedInstanceState == null) {
            selectTab(R.id.dock_home)
        }
    }

    private fun setupCustomNavigation() {
        dockHome.setOnClickListener { selectTab(R.id.dock_home) }
        dockHistory.setOnClickListener { selectTab(R.id.dock_history) }
        dockSettings.setOnClickListener { selectTab(R.id.dock_settings) }
    }

    private fun selectTab(tabId: Int) {
        val fragment: Fragment = when (tabId) {
            R.id.dock_home -> HomeFragment()
            R.id.dock_history -> HistoryFragment()
            R.id.dock_settings -> SettingsFragment()
            else -> return
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment, fragment)
            .commit()

        // Animate the layout change
        val dockCard = findViewById<android.view.ViewGroup>(R.id.bottomDockCard)
        val transition = android.transition.AutoTransition().apply {
            duration = 50L // 50ms (very fast)
            interpolator = android.view.animation.DecelerateInterpolator()
        }
        android.transition.TransitionManager.beginDelayedTransition(dockCard, transition)

        // Adjust widths dynamically
        val activePx = dpToPx(64)
        val inactivePx = dpToPx(44)

        dockHome.layoutParams = dockHome.layoutParams.apply { width = if (tabId == R.id.dock_home) activePx else inactivePx }
        dockHistory.layoutParams = dockHistory.layoutParams.apply { width = if (tabId == R.id.dock_history) activePx else inactivePx }
        dockSettings.layoutParams = dockSettings.layoutParams.apply { width = if (tabId == R.id.dock_settings) activePx else inactivePx }

        // Update indicators visibility
        indicatorHome.visibility = if (tabId == R.id.dock_home) View.VISIBLE else View.INVISIBLE
        indicatorHistory.visibility = if (tabId == R.id.dock_history) View.VISIBLE else View.INVISIBLE
        indicatorSettings.visibility = if (tabId == R.id.dock_settings) View.VISIBLE else View.INVISIBLE

        // Update icon tints
        val activeColor = ContextCompat.getColor(this, R.color.accent_text_on) // #16161F (dark)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_secondary) // #70708A (grey)

        iconHome.setColorFilter(if (tabId == R.id.dock_home) activeColor else inactiveColor)
        iconHistory.setColorFilter(if (tabId == R.id.dock_history) activeColor else inactiveColor)
        iconSettings.setColorFilter(if (tabId == R.id.dock_settings) activeColor else inactiveColor)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun refreshActiveFragment() {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
        if (currentFragment is HomeFragment) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment, HomeFragment())
                .commitAllowingStateLoss()
        }
    }

    // ========== PERMISSIONS ==========

    private fun checkPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.READ_PHONE_STATE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            permissionsLauncher.launch(permissionsNeeded.toTypedArray())
        }
    }

    // ========== ONBOARDING ==========

    private fun showOnboarding() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_onboarding, null)
        dialog.setContentView(view)

        // Setup step indicators
        val steps = listOf(
            Triple(R.id.tvStep1Title, R.id.tvStep1Desc, Pair(R.string.onboarding_step1_title, R.string.onboarding_step1_desc)),
            Triple(R.id.tvStep2Title, R.id.tvStep2Desc, Pair(R.string.onboarding_step2_title, R.string.onboarding_step2_desc)),
            Triple(R.id.tvStep3Title, R.id.tvStep3Desc, Pair(R.string.onboarding_step3_title, R.string.onboarding_step3_desc))
        )

        for ((titleId, descId, stringPair) in steps) {
            view.findViewById<TextView>(titleId)?.setText(stringPair.first)
            view.findViewById<TextView>(descId)?.setText(stringPair.second)
        }

        view.findViewById<MaterialButton>(R.id.btnGetStarted)?.setOnClickListener {
            dialog.dismiss()
            checkPermissions()
        }

        dialog.setCancelable(false)
        dialog.show()
    }
}


