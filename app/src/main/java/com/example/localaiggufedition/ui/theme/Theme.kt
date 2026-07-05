package com.example.localaiggufedition

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

// Graceful fallback palettes if Android version is below Android 12 (S)
private val FallbackDarkColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    background = Color(0xFF121214),
    surface = Color(0xFF1E1E24),
    surfaceVariant = Color(0xFF25252B)
)

private val FallbackLightColorScheme = lightColorScheme(
    primary = Color(0xFF006874),
    background = Color(0xFFF8F9FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F3F4)
)

@Composable
fun LocalAiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Set to true to enable dynamic wallpaper-based Material You themeing
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    // 👈 FIXED: Changed from LocalContext.localContext to LocalContext.current
    val context = LocalContext.current

    val colorScheme = when {
        // Android 12+ supports dynamic wallpaper extraction tokens
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> FallbackDarkColorScheme
        else -> FallbackLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}