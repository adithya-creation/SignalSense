package com.signalsense.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.signalsense.app.ui.SwitchLogAdapter

class HistoryFragment : Fragment() {

    private lateinit var btnClearLog: TextView
    private lateinit var tvEmptyLog: View
    private lateinit var rvSwitchLog: RecyclerView

    private lateinit var switchLogManager: SwitchLogManager
    private lateinit var switchLogAdapter: SwitchLogAdapter

    private val networkChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateLogDisplay()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_history, container, false)
        initViews(view)
        setupRecyclerView()
        setupClickListeners()
        switchLogManager = SwitchLogManager(requireContext())
        return view
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            networkChangeReceiver,
            IntentFilter(NetworkMonitorService.ACTION_NETWORK_CHANGED)
        )
        updateLogDisplay()
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(networkChangeReceiver)
    }

    private fun initViews(view: View) {
        btnClearLog = view.findViewById(R.id.btnClearLog)
        tvEmptyLog = view.findViewById(R.id.tvEmptyLog)
        rvSwitchLog = view.findViewById(R.id.rvSwitchLog)
    }

    private fun setupRecyclerView() {
        switchLogAdapter = SwitchLogAdapter()
        rvSwitchLog.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = switchLogAdapter
        }
    }

    private fun setupClickListeners() {
        btnClearLog.setOnClickListener {
            switchLogManager.clearLog()
            updateLogDisplay()
        }
    }

    private fun updateLogDisplay() {
        if (!isAdded) return
        val log = switchLogManager.getLog()
        if (log.isEmpty()) {
            tvEmptyLog.visibility = View.VISIBLE
            rvSwitchLog.visibility = View.GONE
        } else {
            tvEmptyLog.visibility = View.GONE
            rvSwitchLog.visibility = View.VISIBLE
            switchLogAdapter.submitList(log.toList())
        }
    }
}


