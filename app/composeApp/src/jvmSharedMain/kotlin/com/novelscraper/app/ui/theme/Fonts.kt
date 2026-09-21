package com.novelscraper.app.ui.theme

import androidx.compose.ui.text.font.FontFamily

// Matches the web app's editorial pairing (frontend tailwind.config.ts):
//   display = Playfair Display (headings)
//   serif   = Source Serif 4 (reading body)
//   mono    = JetBrains Mono (numeric / operational data)
// Loaded per platform (Android: res/font) so they stay plain values usable
// outside composition, as AppTypography and Kicker need.

expect val Display: FontFamily
expect val Serif: FontFamily
expect val Mono: FontFamily
