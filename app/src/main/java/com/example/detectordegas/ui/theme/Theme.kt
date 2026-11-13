package com.example.detectordegas.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Paleta cálida: el color de marca es coral y el estado del detector colorea la cabecera. */
val Coral = Color(0xFFD85A30)
val CoralDark = Color(0xFF993C1D)
val WarmBackground = Color(0xFFFFF7F5)
val WarmLine = Color(0xFFF0E4DF)
val StateOk = Color(0xFF0F6E56)
val StateWarn = Color(0xFFBA7517)
val StateAlert = Color(0xFFE24B4A)
val StateUnknown = Color(0xFF5F5E5A)
val TextSoft = Color(0xFF5F5E5A)

private val Light = lightColorScheme(
    primary = Coral, onPrimary = Color.White, primaryContainer = Color(0xFFFAECE7), onPrimaryContainer = CoralDark,
    secondary = CoralDark, secondaryContainer = Color(0xFFFAECE7), onSecondaryContainer = CoralDark, background = WarmBackground, surface = Color.White, surfaceVariant = Color(0xFFF7EEEA),
    onSurfaceVariant = TextSoft, outline = Color(0xFFD9C9C2), outlineVariant = WarmLine, error = Color(0xFFB3261E)
)
private val Dark = darkColorScheme(primary = Color(0xFFF0997B), background = Color(0xFF1B1412), surface = Color(0xFF261D1A))

@Composable
fun DetectorDeGasTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, typography = Typography, content = content)
}
