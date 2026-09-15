package dev.slate.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** M3 dynamic color (minSdk 31 makes it universally available) with tasteful fallbacks. */
@Composable
fun SlateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** Shared-axis-ish motion: slide + fade on FastOutSlowIn. */
object SlateMotion {
    const val DURATION_MS = 260
}

/** Compose-side mapping of the semantic tone palette. */
fun toneColor(tone: dev.slate.android.spec.Tone): Color = when (tone) {
    dev.slate.android.spec.Tone.OK -> Color(0xFF34C759)
    dev.slate.android.spec.Tone.WARN -> Color(0xFFFF9F0A)
    dev.slate.android.spec.Tone.ERROR -> Color(0xFFFF453A)
    dev.slate.android.spec.Tone.INFO -> Color(0xFF0A84FF)
    dev.slate.android.spec.Tone.NEUTRAL -> Color(0xFF8E8E93)
}
