package com.example.detectordegas.ui.theme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
private val Light = lightColorScheme(primary = Color(0xFF006C65), secondary = Color(0xFF4A635F), background = Color(0xFFF3F7F5), surface = Color.White, error = Color(0xFFB3261E))
private val Dark = darkColorScheme(primary = Color(0xFF80D5C8), secondary = Color(0xFFB0CCC5), background = Color(0xFF101816), surface = Color(0xFF192320))
@Composable
fun DetectorDeGasTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, typography = Typography, content = content)
}
