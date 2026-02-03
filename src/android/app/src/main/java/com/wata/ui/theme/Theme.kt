package com.wata.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// =============================================================================
// Colors - High contrast for 1.77" low-quality screen (matches src/rn/theme.ts)
// =============================================================================

object WataColors {
    // Backgrounds
    val bg = Color(0xFF000000)              // Pure black for max contrast
    val bgSecondary = Color(0xFF1A1A1A)     // List items, header
    val bgHighlight = Color(0xFF333333)     // Focused item background

    // Text
    val text = Color(0xFFFFFFFF)            // Primary text (pure white)
    val textSecondary = Color(0xFFAAAAAA)   // Secondary text
    val textMuted = Color(0xFF666666)       // Hints, disabled

    // Accent
    val primary = Color(0xFF00AAFF)         // Buttons, links
    val primaryDark = Color(0xFF0088CC)     // Pressed state

    // Message colors
    val incoming = Color(0xFF00DDFF)        // Incoming messages - bright cyan
    val incomingDim = Color(0xFF006677)     // Incoming played - dimmed
    val outgoing = Color(0xFF88AACC)        // Outgoing messages - muted blue-gray
    val outgoingDim = Color(0xFF445566)     // Outgoing played - dimmed

    // Status
    val recording = Color(0xFFFF3333)       // PTT active
    val playing = Color(0xFF33FF33)         // Audio playback
    val played = Color(0xFF33CCFF)          // Message played by recipient (double-check)
    val error = Color(0xFFFF6666)           // Error states

    // Focus indicator (D-pad navigation)
    val focus = Color(0xFFFFAA00)           // Orange border on focused items
}

// =============================================================================
// Typography - Large sizes for small screen readability
// =============================================================================

object WataTypography {
    val title = TextStyle(
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        color = WataColors.text
    )
    val header = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = WataColors.text
    )
    val large = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.Normal,
        color = WataColors.text
    )
    val body = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        color = WataColors.text
    )
    val small = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        color = WataColors.textSecondary
    )
    val status = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = WataColors.text
    )
}

// =============================================================================
// Spacing - Compact for small screen
// =============================================================================

object WataSpacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
}

// =============================================================================
// Material3 Color Scheme
// =============================================================================

private val WataDarkColorScheme = darkColorScheme(
    primary = WataColors.primary,
    secondary = WataColors.textSecondary,
    background = WataColors.bg,
    surface = WataColors.bgSecondary,
    onPrimary = WataColors.text,
    onSecondary = WataColors.text,
    onBackground = WataColors.text,
    onSurface = WataColors.text,
    error = WataColors.error,
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
