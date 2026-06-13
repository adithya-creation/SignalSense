package com.signalsense.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.signalsense.app.R
import com.signalsense.app.SwitchLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for displaying switch log entries.
 * Uses a colored dot indicator matched to the destination network type.
 */
class SwitchLogAdapter : ListAdapter<SwitchLogEntry, SwitchLogAdapter.ViewHolder>(DiffCallback()) {

    private val dateFormat = SimpleDateFormat("MMM dd, h:mm a", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_switch_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTransition: TextView = itemView.findViewById(R.id.tvTransition)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val tvSpeed: TextView = itemView.findViewById(R.id.tvSpeed)
        private val viewNetworkDot: View = itemView.findViewById(R.id.viewNetworkDot)

        fun bind(entry: SwitchLogEntry) {
            val context = itemView.context

            // Set transition text
            tvTransition.text = context.getString(
                R.string.log_transition,
                entry.fromNetwork,
                entry.toNetwork
            )

            // Color dot to match the destination network
            val dotColor = getNetworkColor(context, entry.toNetwork)
            viewNetworkDot.backgroundTintList = ColorStateList.valueOf(dotColor)

            // Transition text in theme's on-surface color
            tvTransition.setTextColor(resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurface))

            // Set timestamp
            tvTimestamp.text = dateFormat.format(Date(entry.timestamp))

            // Set speed info if available
            val speedText = if (entry.downlinkMbps != null) {
                context.getString(R.string.log_speed, entry.downlinkMbps)
            } else {
                null
            }

            if (speedText != null) {
                tvSpeed.visibility = View.VISIBLE
                tvSpeed.text = speedText
            } else {
                tvSpeed.visibility = View.GONE
            }
        }
    }

    private fun getNetworkColor(context: Context, networkName: String): Int {
        return when {
            networkName.contains("5G SA", ignoreCase = true) ->
                ContextCompat.getColor(context, R.color.color_5g_sa)
            networkName.contains("5G NSA", ignoreCase = true) ->
                ContextCompat.getColor(context, R.color.color_5g_nsa)
            networkName.contains("5G", ignoreCase = true) ->
                ContextCompat.getColor(context, R.color.color_5g)
            networkName.contains("4G", ignoreCase = true) ->
                ContextCompat.getColor(context, R.color.color_4g)
            networkName.contains("3G", ignoreCase = true) ->
                ContextCompat.getColor(context, R.color.color_3g)
            networkName.contains("2G", ignoreCase = true) ->
                ContextCompat.getColor(context, R.color.color_2g)
            else ->
                ContextCompat.getColor(context, R.color.color_no_data)
        }
    }

    private fun resolveThemeColor(context: Context, attr: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private class DiffCallback : DiffUtil.ItemCallback<SwitchLogEntry>() {
        override fun areItemsTheSame(oldItem: SwitchLogEntry, newItem: SwitchLogEntry): Boolean {
            return oldItem.timestamp == newItem.timestamp
        }

        override fun areContentsTheSame(oldItem: SwitchLogEntry, newItem: SwitchLogEntry): Boolean {
            return oldItem == newItem
        }
    }
}


