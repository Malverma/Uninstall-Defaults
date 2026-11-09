package com.defaultuninstaller.ui.theme

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
import com.defaultuninstaller.data.safety.SafetyRating
import com.defaultuninstaller.data.settings.ThemeMode

private val Coral = Color(0xFFFF6B5B)

private val LightColors = lightColorScheme(primary = Color(0xFFB3261E), secondary = Color(0xFF775651))
private val DarkColors = darkColorScheme(primary = Coral, secondary = Color(0xFFE7BDB6))

@Composable
fun DefaultUninstallerTheme(themeMode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Safety colors; always paired with a text label so meaning never depends on color alone. */
@Composable
fun safetyColor(rating: SafetyRating): Color {
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()
    return when (rating) {
        SafetyRating.SAFE -> if (dark) Color(0xFF7DD88B) else Color(0xFF1E7B34)
        SafetyRating.CAUTION -> if (dark) Color(0xFFF2C45A) else Color(0xFF8A5A00)
        SafetyRating.CRITICAL -> MaterialTheme.colorScheme.error
        SafetyRating.UNKNOWN -> MaterialTheme.colorScheme.outline
    }
}

private fun Color.luminanceIsDark() = (0.299 * red + 0.587 * green + 0.114 * blue) < 0.5
