package com.mira.screening.preprocessing

import android.graphics.Bitmap
import android.graphics.Color

// Detect and suppress specular highlights (glare from the speculum metal and
// from cervical mucus). Specular pixels share a signature: very high luma,
// very low saturation. They confuse downstream attention models, so we
// replace them with the mean color of non-specular pixels.
object SpecularReflection {
    private const val LUMA_THRESHOLD = 240f
    private const val SATURATION_THRESHOLD = 0.15f

    fun mask(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            out[i] = if (isSpecular(pixels[i])) 1 else 0
        }
        return out
    }

    fun suppress(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0
        for (p in pixels) {
            if (!isSpecular(p)) {
                sumR += Color.red(p)
                sumG += Color.green(p)
                sumB += Color.blue(p)
                count++
            }
        }
        if (count == 0) return bitmap

        val meanR = (sumR / count).toInt()
        val meanG = (sumG / count).toInt()
        val meanB = (sumB / count).toInt()

        val out = pixels.copyOf()
        for (i in out.indices) {
            if (isSpecular(pixels[i])) {
                out[i] = Color.argb(Color.alpha(pixels[i]), meanR, meanG, meanB)
            }
        }
        return Bitmap.createBitmap(out, w, h, bitmap.config ?: Bitmap.Config.ARGB_8888)
    }

    private fun isSpecular(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val luma = 0.299f * r + 0.587f * g + 0.114f * b
        val saturation = if (maxC == 0) 0f else (maxC - minC).toFloat() / maxC
        return luma > LUMA_THRESHOLD && saturation < SATURATION_THRESHOLD
    }
}
