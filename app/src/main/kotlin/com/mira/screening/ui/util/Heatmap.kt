package com.mira.screening.ui.util

import android.graphics.Bitmap
import android.graphics.Color

// 19-stop subset of matplotlib's viridis colormap. Perceptually uniform,
// color-blind safe, and clinically familiar (used widely in radiology).
private val VIRIDIS_LUT = arrayOf(
    intArrayOf(68, 1, 84),
    intArrayOf(72, 21, 103),
    intArrayOf(72, 38, 119),
    intArrayOf(69, 55, 129),
    intArrayOf(64, 71, 136),
    intArrayOf(57, 86, 140),
    intArrayOf(51, 99, 141),
    intArrayOf(45, 112, 142),
    intArrayOf(40, 125, 142),
    intArrayOf(35, 138, 141),
    intArrayOf(31, 150, 139),
    intArrayOf(31, 162, 135),
    intArrayOf(40, 174, 128),
    intArrayOf(60, 187, 117),
    intArrayOf(94, 201, 98),
    intArrayOf(135, 213, 73),
    intArrayOf(180, 222, 44),
    intArrayOf(226, 228, 30),
    intArrayOf(253, 231, 37)
)

fun viridisColor(value: Float, alpha: Int = 255): Int {
    val v = value.coerceIn(0f, 1f)
    val scaled = v * (VIRIDIS_LUT.size - 1)
    val i = scaled.toInt()
    val frac = scaled - i
    val a = VIRIDIS_LUT[i]
    val b = VIRIDIS_LUT[(i + 1).coerceAtMost(VIRIDIS_LUT.size - 1)]
    val r = (a[0] * (1 - frac) + b[0] * frac).toInt().coerceIn(0, 255)
    val g = (a[1] * (1 - frac) + b[1] * frac).toInt().coerceIn(0, 255)
    val bl = (a[2] * (1 - frac) + b[2] * frac).toInt().coerceIn(0, 255)
    return Color.argb(alpha, r, g, bl)
}

fun heatmapToBitmap(
    heatmap: FloatArray,
    width: Int,
    height: Int,
    alpha: Int = 180,
    minActivation: Float = 0.15f
): Bitmap {
    require(heatmap.size == width * height) {
        "Heatmap size ${heatmap.size} does not match ${width}x$height"
    }
    val pixels = IntArray(width * height)
    for (i in heatmap.indices) {
        val v = heatmap[i]
        // Below-threshold values render fully transparent so the underlying
        // image is unobstructed where the model isn't attending.
        pixels[i] = if (v < minActivation) Color.TRANSPARENT
        else viridisColor(v, alpha)
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
