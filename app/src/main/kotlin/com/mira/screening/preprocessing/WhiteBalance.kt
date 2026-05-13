package com.mira.screening.preprocessing

import android.graphics.Bitmap
import android.graphics.Color

// Gray-world white balance correction. Assumes the average color of the
// scene is gray; scales each channel so its mean equals the global mean.
// This is critical for acetowhite detection, where reliable color rendering
// across phones, lighting, and skin tones is the actual clinical signal.
object WhiteBalance {
    fun grayWorld(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        for (p in pixels) {
            sumR += Color.red(p)
            sumG += Color.green(p)
            sumB += Color.blue(p)
        }
        val n = pixels.size.toFloat()
        val meanR = sumR / n
        val meanG = sumG / n
        val meanB = sumB / n
        val gray = (meanR + meanG + meanB) / 3f
        val scaleR = if (meanR > 0) gray / meanR else 1f
        val scaleG = if (meanG > 0) gray / meanG else 1f
        val scaleB = if (meanB > 0) gray / meanB else 1f

        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (Color.red(p) * scaleR).toInt().coerceIn(0, 255)
            val g = (Color.green(p) * scaleG).toInt().coerceIn(0, 255)
            val b = (Color.blue(p) * scaleB).toInt().coerceIn(0, 255)
            out[i] = Color.argb(Color.alpha(p), r, g, b)
        }
        return Bitmap.createBitmap(out, w, h, bitmap.config ?: Bitmap.Config.ARGB_8888)
    }
}
