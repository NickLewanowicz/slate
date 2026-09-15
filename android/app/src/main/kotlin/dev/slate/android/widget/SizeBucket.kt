package dev.slate.android.widget

import android.os.Bundle
import android.appwidget.AppWidgetManager

/** Widget size buckets derived from AppWidgetManager.getAppWidgetOptions (min width in dp). */
enum class SizeBucket {
    /** min width <= 230dp: title + up to 3 statusRows digest + freshness + question affordance. */
    COMPACT,

    /** min width <= 400dp: header + statusRows + first question compact. */
    MEDIUM,

    /** everything: header + all elements; todoList via fixed-height ListView. */
    FULL;

    companion object {
        const val COMPACT_MAX_DP = 230
        const val MEDIUM_MAX_DP = 400

        /** Choose bucket from widget options; [fallback] used when no width is present (picker/preview). */
        fun fromOptions(options: Bundle?, fallback: SizeBucket = FULL): SizeBucket {
            val widthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, -1) ?: -1
            if (widthDp <= 0) return fallback
            return fromWidthDp(widthDp)
        }

        fun fromWidthDp(widthDp: Int): SizeBucket = when {
            widthDp <= 0 -> FULL
            widthDp <= COMPACT_MAX_DP -> COMPACT
            widthDp <= MEDIUM_MAX_DP -> MEDIUM
            else -> FULL
        }
    }
}
