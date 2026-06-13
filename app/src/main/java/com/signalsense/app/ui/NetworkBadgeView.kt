package com.signalsense.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.signalsense.app.NetworkGeneration
import com.signalsense.app.R

/**
 * Custom view for the large network type badge.
 * Dynamically changes background color based on current network generation.
 */
class NetworkBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * Returns the color resource for a given network generation.
     */
    companion object {
        fun getColorForGeneration(context: Context, generation: NetworkGeneration): Int {
            return when (generation) {
                NetworkGeneration.FIVE_G_SA, NetworkGeneration.FIVE_G ->
                    ContextCompat.getColor(context, R.color.color_5g_sa)
                NetworkGeneration.FIVE_G_NSA ->
                    ContextCompat.getColor(context, R.color.color_5g_nsa)
                NetworkGeneration.FOUR_G ->
                    ContextCompat.getColor(context, R.color.color_4g)
                NetworkGeneration.THREE_G ->
                    ContextCompat.getColor(context, R.color.color_3g)
                NetworkGeneration.TWO_G ->
                    ContextCompat.getColor(context, R.color.color_2g)
                else ->
                    ContextCompat.getColor(context, R.color.color_no_data)
            }
        }

        fun getSubtitleForGeneration(context: Context, generation: NetworkGeneration): String {
            return when (generation) {
                NetworkGeneration.FIVE_G, NetworkGeneration.FIVE_G_NSA, NetworkGeneration.FIVE_G_SA ->
                    context.getString(R.string.subtitle_5g)
                NetworkGeneration.FOUR_G ->
                    context.getString(R.string.subtitle_4g)
                NetworkGeneration.THREE_G ->
                    context.getString(R.string.subtitle_3g)
                NetworkGeneration.TWO_G ->
                    context.getString(R.string.subtitle_2g)
                NetworkGeneration.NO_DATA ->
                    context.getString(R.string.subtitle_no_data)
                else ->
                    context.getString(R.string.subtitle_unknown)
            }
        }
    }
}


