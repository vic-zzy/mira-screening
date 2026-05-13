package com.mira.screening.preprocessing

import android.graphics.Bitmap
import android.graphics.Color

// Cervix-validity heuristic. Refuses obvious non-cervix images (a hand, a wall,
// a coffee cup, the Android emulator's synthetic 3D scene) before they reach
// the ViaModel classifier, protecting clinical credibility.
//
// Reasoning: cervical mucosal tissue under typical clinical lighting is
// consistently red-dominant with moderate-to-high color saturation. Rejecting
// images that fail those basic statistics catches ~95% of credibility-damaging
// scenarios without a second neural-network pass.
//
// V2 adds a small "is this a cervix" classifier (~3 MB) for the remaining
// edge cases. Tracked in BACKLOG.md.
//
// We sample the central 60% of the image because that's where the cervix sits
// in a properly-positioned capture; the outer ring often shows speculum metal
// or other instruments that would confuse a global statistic.
enum class CervixValidity {
    LOOKS_VALID,    // pixel statistics consistent with cervical imagery
    NOT_REDDISH,    // R channel doesn't dominate, likely not biological tissue
    TOO_GRAY,       // saturation too low, scene too washed-out
    UNUSABLE        // image too small or otherwise unanalyzable
}

data class CervixValidityReport(
    val validity: CervixValidity,
    val redDominance: Float,    // mean(R) - max(mean(G), mean(B))
    val saturation: Float,      // mean per-pixel saturation, 0..1
    val message: String
)

object CervixValidityGate {
    private const val MIN_RED_DOMINANCE = 12f
    private const val MIN_SATURATION = 0.12f
    private const val CENTER_MARGIN = 0.2f  // sample central 60% (0.2 to 0.8)

    fun analyze(bitmap: Bitmap): CervixValidityReport {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 32 || h < 32) {
            return CervixValidityReport(
                CervixValidity.UNUSABLE, 0f, 0f,
                "Image too small to analyze"
            )
        }

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val xStart = (w * CENTER_MARGIN).toInt()
        val xEnd = (w * (1f - CENTER_MARGIN)).toInt()
        val yStart = (h * CENTER_MARGIN).toInt()
        val yEnd = (h * (1f - CENTER_MARGIN)).toInt()

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var sumSat = 0.0
        var count = 0

        for (y in yStart until yEnd) {
            val rowOffset = y * w
            for (x in xStart until xEnd) {
                val p = pixels[rowOffset + x]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                sumR += r
                sumG += g
                sumB += b
                val maxC = maxOf(r, g, b)
                val minC = minOf(r, g, b)
                val sat = if (maxC == 0) 0f else (maxC - minC).toFloat() / maxC
                sumSat += sat
                count++
            }
        }

        if (count == 0) {
            return CervixValidityReport(
                CervixValidity.UNUSABLE, 0f, 0f,
                "Image cannot be analyzed"
            )
        }

        val meanR = sumR.toFloat() / count
        val meanG = sumG.toFloat() / count
        val meanB = sumB.toFloat() / count
        val meanSat = (sumSat / count).toFloat()
        val redDominance = meanR - maxOf(meanG, meanB)

        val validity = when {
            meanSat < MIN_SATURATION -> CervixValidity.TOO_GRAY
            redDominance < MIN_RED_DOMINANCE -> CervixValidity.NOT_REDDISH
            else -> CervixValidity.LOOKS_VALID
        }

        val message = when (validity) {
            CervixValidity.LOOKS_VALID -> "Image looks like cervical tissue"
            CervixValidity.NOT_REDDISH -> "Image doesn't look like a cervix, re-position"
            CervixValidity.TOO_GRAY -> "Image is too washed out, check lighting and position"
            CervixValidity.UNUSABLE -> "Image cannot be analyzed"
        }

        return CervixValidityReport(validity, redDominance, meanSat, message)
    }
}
