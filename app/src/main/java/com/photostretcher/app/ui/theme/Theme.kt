package com.photostretcher.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The one bright colour in the app: the lines, the handles and the band. */
val Accent = Color(0xFF22D3EE)

/** Used for the button that does the work. */
val AccentStrong = Color(0xFF06B6D4)

/** A warm yellow, handy for the "squash" state. */
val Warn = Color(0xFFFACC15)

private val Ink = Color(0xFF0B0F14)
private val Surface = Color(0xFF141A22)
private val SurfaceHigh = Color(0xFF1E2630)
private val Outline = Color(0xFF2C3644)
private val TextPrimary = Color(0xFFE7EDF3)
private val TextMuted = Color(0xFF93A1B0)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF04222A),
    primaryContainer = AccentStrong,
    onPrimaryContainer = Color(0xFF04222A),
    secondary = Warn,
    onSecondary = Color(0xFF241F00),
    background = Ink,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextMuted,
    outline = Outline,
    outlineVariant = Color(0xFF222B36),
    error = Color(0xFFFF7A7A),
    onError = Color(0xFF2B0A0A),
)

@Composable
fun PhotoStretcherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
