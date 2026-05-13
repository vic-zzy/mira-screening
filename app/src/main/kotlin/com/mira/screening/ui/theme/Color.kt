package com.mira.screening.ui.theme

import androidx.compose.ui.graphics.Color

// Brand foundation, extracted from the aperture mark.
val MiraTeal = Color(0xFF2A4A4D)
val MiraTealMuted = Color(0xFF4F6E70)
val MiraTerracotta = Color(0xFFC97A4A)

// Surfaces, warm cream, not sterile white. The cream is the default background;
// surface is a slightly lighter card fill for elevation without grey shadows.
val MiraCream = Color(0xFFF5EFE3)
val MiraSurface = Color(0xFFFAF6EC)
val MiraSurfaceVariant = Color(0xFFEDE7D8)
val MiraSurfaceMuted = Color(0xFFE6E0CF)

// Ink, type and strokes against cream. Near-black for headlines, dark-warm
// grey for secondary copy (4.5:1+ contrast against cream), soft for
// muted/disabled state.
val MiraInk = Color(0xFF111111)
val MiraInkMuted = Color(0xFF4D4A43)
val MiraInkSoft = Color(0xFF8E887C)
val MiraDivider = Color(0xFFD9D2BF)

// Status palette, used for result-state squircles and accents in History.
// Backgrounds are pastel; dots are saturated. Muted enough that "positive"
// reads as serious-but-not-alarming, "negative" as calm, "inconclusive"
// as neutral.
val MiraPositiveBg = Color(0xFFF4DCC4)
val MiraPositiveDot = Color(0xFFC97A4A)
val MiraNegativeBg = Color(0xFFD4DDD0)
val MiraNegativeDot = Color(0xFF4A6B4F)
val MiraInconclusiveBg = Color(0xFFD6DDDC)
val MiraInconclusiveDot = Color(0xFF4F6E70)

// Error, used sparingly. Warm clay rather than aggressive red.
val MiraError = Color(0xFFB04A2E)
val MiraOnError = Color(0xFFFFFFFF)
val MiraErrorContainer = Color(0xFFF6DBCC)
val MiraOnErrorContainer = Color(0xFF4A1A0A)
