package com.novelscraper.app.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

// The same font files as Android (res/font), copied onto the desktop classpath
// under font/ by the desktopFonts Gradle task.

private fun font(name: String, weight: FontWeight, style: FontStyle = FontStyle.Normal) =
    Font(
        identity = name,
        data = checkNotNull(object {}.javaClass.getResourceAsStream("/font/$name.ttf")) {
            "font/$name.ttf missing from the classpath"
        }.use { it.readBytes() },
        weight = weight,
        style = style,
    )

actual val Display = FontFamily(
    font("playfair_semibold", FontWeight.SemiBold),
    font("playfair_bold", FontWeight.Bold),
)

actual val Serif = FontFamily(
    font("source_serif", FontWeight.Normal),
    font("source_serif_italic", FontWeight.Normal, FontStyle.Italic),
    font("source_serif_semibold", FontWeight.SemiBold),
)

actual val Mono = FontFamily(
    font("jetbrains_mono", FontWeight.Normal),
    font("jetbrains_mono_medium", FontWeight.Medium),
)
