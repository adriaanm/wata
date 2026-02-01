package com.wata.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Colors matching the RN theme (src/rn/theme.ts)
val WataBlack = Color(0xFF1A1A2E)      // bg
val WataNavy = Color(0xFF16213E)        // surface
val WataPurple = Color(0xFF7B2CBF)      // primary
val WataWhite = Color(0xFFEEEEEE)       // text
val WataGray = Color(0xFF888888)        // textSecondary
val WataGreen = Color(0xFF4CAF50)       // success
val WataRed = Color(0xFFE53935)         // error/danger

private val WataDarkColorScheme = darkColorScheme(
    primary = WataPurple,
    secondary = WataGray,
    background = WataBlack,
    surface = WataNavy,
    onPrimary = WataWhite,
    onSecondary = WataWhite,
    onBackground = WataWhite,
    onSurface = WataWhite,
    error = WataRed,
)

@Composable
fun WataTheme(
    darkTheme: Boolean = true, // Always dark for walkie-talkie
    content: @Composable () -> Unit
) {
    val colorScheme = WataDarkColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
