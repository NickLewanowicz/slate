package dev.slate.android.widget

import java.time.Instant

/** Relative freshness labels for widget + app ("updated 6m ago"). */
object FreshnessFormatter {

    /** Label only, e.g. "just now", "6m ago", "3h ago", "2d ago", "Mar 3". */
    fun relative(fetchedAtEpochMs: Long?, nowEpochMs: Long): String {
        if (fetchedAtEpochMs == null || fetchedAtEpochMs <= 0) return "updated unknown"
        val ageMs = nowEpochMs - fetchedAtEpochMs
        if (ageMs < 0) return "just now"
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour
        return when {
            ageMs < minute -> "just now"
            ageMs < hour -> "updated ${ageMs / minute}m ago"
            ageMs < day -> "updated ${ageMs / hour}h ago"
            ageMs < 7 * day -> "updated ${ageMs / day}d ago"
            else -> {
                val date = java.time.Instant.ofEpochMilli(fetchedAtEpochMs)
                    .atZone(java.time.ZoneId.systemDefault())
                "updated ${date.monthValue}/${date.dayOfMonth}"
            }
        }
    }

    /**
     * Convenience for ISO timestamps stamped by the server (updatedAt). Always
     * carries the "updated " qualifier ("just now" alone is reserved for the
     * device's own freshness); unparseable input degrades to "updated unknown".
     */
    fun relativeIso(iso: String?, nowEpochMs: Long): String {
        val label = relative(iso?.let { parseIso(it) }, nowEpochMs)
        return if (label.startsWith("updated ")) label else "updated $label"
    }

    private fun parseIso(iso: String): Long? = runCatching {
        Instant.parse(iso).toEpochMilli()
    }.getOrNull()
}
