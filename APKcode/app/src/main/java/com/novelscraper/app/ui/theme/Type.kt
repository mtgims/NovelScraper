package com.novelscraper.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

// Headings use Playfair (Display); everything readable uses Source Serif (Serif).
// Use `Mono` explicitly for numeric/operational bits (stats, kickers).
val AppTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = Display, fontWeight = FontWeight.Bold),
        displayMedium = displayMedium.copy(fontFamily = Display, fontWeight = FontWeight.Bold),
        displaySmall = displaySmall.copy(fontFamily = Display, fontWeight = FontWeight.Bold),
        headlineLarge = headlineLarge.copy(fontFamily = Display, fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.copy(fontFamily = Display, fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontFamily = Serif),
        bodyMedium = bodyMedium.copy(fontFamily = Serif),
        bodySmall = bodySmall.copy(fontFamily = Serif),
        labelLarge = labelLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(fontFamily = Mono),
        labelSmall = labelSmall.copy(fontFamily = Mono),
    )
}

/** Mono style for stats/kickers (uppercase small caps feel). */
val Kicker = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium)
