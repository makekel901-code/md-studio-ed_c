package com.amd.mdstudio.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private data class Accent(val light: Color, val dark: Color, val secondary: Color)
private fun accent(key: String): Accent = when (key) {
    "emerald" -> Accent(Color(0xFF087F5B), Color(0xFF62D6A8), Color(0xFF4C9A7A))
    "violet" -> Accent(Color(0xFF6941C6), Color(0xFFB69CFF), Color(0xFF8A70C9))
    "amber" -> Accent(Color(0xFF9A6700), Color(0xFFFFC857), Color(0xFFC08B2C))
    "crimson" -> Accent(Color(0xFFB42336), Color(0xFFFF8A98), Color(0xFFD36A77))
    else -> Accent(Color(0xFF2563EB), Color(0xFF8AB4FF), Color(0xFF16A34A))
}

@Composable fun MDTheme(
    themeMode: String = "system",
    accentKey: String = "blue",
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val selected = accent(accentKey)
    val scheme = if (dark) darkColorScheme(
        primary = selected.dark, secondary = selected.secondary,
        background = Color(0xFF101827), surface = Color(0xFF182334),
        onSurface = Color(0xFFE7EDF7)
    ) else lightColorScheme(
        primary = selected.light, onPrimary = Color.White,
        secondary = selected.secondary, background = Color(0xFFF6F8FC),
        surface = Color.White, onSurface = Color(0xFF172033)
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
