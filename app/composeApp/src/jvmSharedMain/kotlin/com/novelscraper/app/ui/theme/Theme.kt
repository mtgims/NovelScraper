package com.novelscraper.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.runtime.collectAsState
import com.novelscraper.app.platform.KeyValueStore
import com.novelscraper.app.platform.settingsStore

// Palettes mirror frontend/app/globals.css tokens (light / dark / purple / blue).

private val Light = lightColorScheme(
    primary = Color(0xFF4F46E5), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFECEBFB), onPrimaryContainer = Color(0xFF18181B),
    background = Color(0xFFFFFFFF), onBackground = Color(0xFF18181B),
    surface = Color(0xFFFAFAFA), onSurface = Color(0xFF18181B),
    surfaceVariant = Color(0xFFF4F4F5), onSurfaceVariant = Color(0xFF71717A),
    outline = Color(0xFFE4E4E7), outlineVariant = Color(0xFFE4E4E7),
    error = Color(0xFFDC2626), onError = Color(0xFFFFFFFF),
    // The surfaces a theme is actually built from: without these, Material
    // hands back its own baseline, which is purple whatever the theme says.
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF4F4F5), surfaceContainerHigh = Color(0xFFEFEFF1),
    surfaceContainerHighest = Color(0xFFE9E9EC),
    // What is picked out (a chosen row, a selected chapter) follows the accent.
    secondary = Color(0xFF4F46E5), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFECEBFB), onSecondaryContainer = Color(0xFF18181B),
    surfaceTint = Color(0xFF4F46E5),
    tertiary = Color(0xFF0D9488), onTertiary = Color(0xFFFFFFFF),

)

private val Dark = darkColorScheme(
    primary = Color(0xFF818CF8), onPrimary = Color(0xFF0A0A0B),
    primaryContainer = Color(0xFF1E1F3A), onPrimaryContainer = Color(0xFFF4F4F5),
    background = Color(0xFF0A0A0B), onBackground = Color(0xFFF4F4F5),
    surface = Color(0xFF161618), onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF232327), onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF2A2A2E), outlineVariant = Color(0xFF2A2A2E),
    error = Color(0xFFF87171), onError = Color(0xFF0A0A0B),
    // The surfaces a theme is actually built from: without these, Material
    // hands back its own baseline, which is purple whatever the theme says.
    surfaceContainerLowest = Color(0xFF060607), surfaceContainerLow = Color(0xFF111113),
    surfaceContainer = Color(0xFF161618), surfaceContainerHigh = Color(0xFF1D1D20),
    surfaceContainerHighest = Color(0xFF232327),
    // What is picked out (a chosen row, a selected chapter) follows the accent.
    secondary = Color(0xFF818CF8), onSecondary = Color(0xFF0A0A0B),
    secondaryContainer = Color(0xFF1E1F3A), onSecondaryContainer = Color(0xFFF4F4F5),
    surfaceTint = Color(0xFF818CF8),
    tertiary = Color(0xFF2DD4BF), onTertiary = Color(0xFF04201D),

)

private val Purple = darkColorScheme(
    primary = Color(0xFFA855F7), onPrimary = Color(0xFF140F1C),
    primaryContainer = Color(0xFF2A1E3A), onPrimaryContainer = Color(0xFFECE8F3),
    background = Color(0xFF140F1C), onBackground = Color(0xFFECE8F3),
    surface = Color(0xFF1D1626), onSurface = Color(0xFFECE8F3),
    surfaceVariant = Color(0xFF271D33), onSurfaceVariant = Color(0xFFA99FB8),
    outline = Color(0xFF342843), outlineVariant = Color(0xFF342843),
    error = Color(0xFFFB7185), onError = Color(0xFF140F1C),
    // The surfaces a theme is actually built from: without these, Material
    // hands back its own baseline, which is purple whatever the theme says.
    surfaceContainerLowest = Color(0xFF0F0B15), surfaceContainerLow = Color(0xFF191220),
    surfaceContainer = Color(0xFF1D1626), surfaceContainerHigh = Color(0xFF241B2E),
    surfaceContainerHighest = Color(0xFF2B2038),
    // What is picked out (a chosen row, a selected chapter) follows the accent.
    secondary = Color(0xFFA855F7), onSecondary = Color(0xFF140F1C),
    secondaryContainer = Color(0xFF2A1E3A), onSecondaryContainer = Color(0xFFECE8F3),
    surfaceTint = Color(0xFFA855F7),
    tertiary = Color(0xFF34D399), onTertiary = Color(0xFF07241A),

)

private val Blue = darkColorScheme(
    primary = Color(0xFF3B82F6), onPrimary = Color(0xFF0A1020),
    primaryContainer = Color(0xFF16233D), onPrimaryContainer = Color(0xFFE6ECF5),
    background = Color(0xFF0A1020), onBackground = Color(0xFFE6ECF5),
    surface = Color(0xFF111A2E), onSurface = Color(0xFFE6ECF5),
    surfaceVariant = Color(0xFF18233A), onSurfaceVariant = Color(0xFF98A6C0),
    outline = Color(0xFF22314C), outlineVariant = Color(0xFF22314C),
    error = Color(0xFFF87171), onError = Color(0xFF0A1020),
    // The surfaces a theme is actually built from: without these, Material
    // hands back its own baseline, which is purple whatever the theme says.
    surfaceContainerLowest = Color(0xFF070C18), surfaceContainerLow = Color(0xFF0E1526),
    surfaceContainer = Color(0xFF111A2E), surfaceContainerHigh = Color(0xFF16213A),
    surfaceContainerHighest = Color(0xFF1C2A45),
    // What is picked out (a chosen row, a selected chapter) follows the accent.
    secondary = Color(0xFF3B82F6), onSecondary = Color(0xFF0A1020),
    secondaryContainer = Color(0xFF16233D), onSecondaryContainer = Color(0xFFE6ECF5),
    surfaceTint = Color(0xFF3B82F6),
    tertiary = Color(0xFF38BDF8), onTertiary = Color(0xFF06243A),

)

/** Available themes (name, label, swatch bg + accent) — mirrors theme-picker.tsx. */
data class AppTheme(val name: String, val label: String, val swatchBg: Color, val accent: Color)

val THEMES = listOf(
    AppTheme("light", "Light", Color(0xFFFFFFFF), Color(0xFF4F46E5)),
    AppTheme("dark", "Dark", Color(0xFF0A0A0B), Color(0xFF818CF8)),
    AppTheme("purple", "Purple", Color(0xFF140F1C), Color(0xFFA855F7)),
    AppTheme("blue", "Blue", Color(0xFF0A1020), Color(0xFF3B82F6)),
)

/** Persisted theme selection (like the web's next-themes). */
object ThemeController {
    private lateinit var prefs: KeyValueStore
    private val _theme = MutableStateFlow("dark")
    val theme: StateFlow<String> = _theme.asStateFlow()

    fun init() {
        prefs = settingsStore("ui")
        _theme.value = prefs.getString("theme", "dark") ?: "dark"
    }

    fun setTheme(name: String) {
        _theme.value = name
        prefs.putString("theme", name)
    }
}

@Composable
fun NovelScraperTheme(content: @Composable () -> Unit) {
    val theme by ThemeController.theme.collectAsState()
    val scheme = when (theme) {
        "light" -> Light
        "purple" -> Purple
        "blue" -> Blue
        else -> Dark
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
