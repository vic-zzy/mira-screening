package com.mira.screening.preprocessing

import android.graphics.Bitmap
import android.graphics.Color

// Sharpness metric: variance of the Laplacian. The clinical-imaging standard
// for "is this image in focus enough", used in Pertuz 2013, OpenCV examples,
// and most colposcopy software. Higher = sharper.
//
// Operates on the luma channel (BT.601 weighting). Border pixels are excluded
// (no padding) to avoid edge artifacts.
object LaplacianVariance {
    fun compute(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return 0f

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val luma = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            luma[i] = 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
        }

        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val center = luma[y * w + x]
                val up = luma[(y - 1) * w + x]
                val down = luma[(y + 1) * w + x]
                val left = luma[y * w + (x - 1)]
                val right = luma[y * w + (x + 1)]
                val response = up + down + left + right - 4f * center
                sum += response
                sumSq += response.toDouble() * response
                n++
            }
        }
        if (n == 0) return 0f
        val mean = sum / n
        return ((sumSq / n) - mean * mean).toFloat()
    }
}
