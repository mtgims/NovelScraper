package com.novelscraper.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.novelscraper.app.R

// Matches the web app's editorial pairing (frontend tailwind.config.ts):
//   display = Playfair Display (headings)
//   serif   = Source Serif 4 (reading body)
//   mono    = JetBrains Mono (numeric / operational data)

val Display = FontFamily(
    Font(R.font.playfair_semibold, FontWeight.SemiBold),
    Font(R.font.playfair_bold, FontWeight.Bold),
)

val Serif = FontFamily(
    Font(R.font.source_serif, FontWeight.Normal),
    Font(R.font.source_serif_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.source_serif_semibold, FontWeight.SemiBold),
)

val Mono = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)
