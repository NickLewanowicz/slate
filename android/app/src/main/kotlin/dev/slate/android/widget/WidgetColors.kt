package dev.slate.android.widget

import android.content.Context
import androidx.core.content.ContextCompat
import dev.slate.android.R
import dev.slate.android.spec.Tone

/** Tone → concrete color ints for RemoteViews (values resolve night-aware via values-night). */
object WidgetColors {

    /**
     * Widget surfaces render on a light card, so tones use darker on-light variants
     * (P7 audit: iOS-bright tones measured 1.8–3.3:1 on #F2F2F7 — WCAG AA fail).
     * Frozen brand hues stay in the app theme / dark surfaces.
     */
    fun toneColor(context: Context, tone: Tone): Int = when (tone) {
        Tone.OK -> ContextCompat.getColor(context, R.color.tone_ok_widget)
        Tone.WARN -> ContextCompat.getColor(context, R.color.tone_warn_widget)
        Tone.ERROR -> ContextCompat.getColor(context, R.color.tone_error_widget)
        Tone.INFO -> ContextCompat.getColor(context, R.color.tone_info_widget)
        Tone.NEUTRAL -> ContextCompat.getColor(context, R.color.widget_text_secondary)
    }

    fun textPrimary(context: Context): Int = ContextCompat.getColor(context, R.color.widget_text_primary)
    fun textSecondary(context: Context): Int = ContextCompat.getColor(context, R.color.widget_text_secondary)
    fun optionTextOnFilled(context: Context): Int = ContextCompat.getColor(context, R.color.widget_option_text_on_filled)
}
