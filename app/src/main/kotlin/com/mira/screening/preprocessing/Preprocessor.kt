package com.mira.screening.preprocessing

import android.graphics.Bitmap

// V1 preprocessing pipeline: raw capture -> white-balance correct ->
// specular-glare suppress -> resize+center-crop to the model input size.
//
// Phase 2 adds OpenCV-backed steps when we wire the OpenCV-Android AAR in:
//   - edge-preserving denoise (bilateral filter)
//   - phase-correlation frame alignment for multi-frame stacking
//   - HSV-based acetowhite enhancement
//
// Run on a background dispatcher; this is CPU-bound and not negligible
// (~50–150 ms on a $100 Android, ~10 ms on a flagship).
object Preprocessor {
    const val MODEL_INPUT_SIZE = 224

    fun process(bitmap: Bitmap, modelInputSize: Int = MODEL_INPUT_SIZE): Bitmap {
        val balanced = WhiteBalance.grayWorld(bitmap)
        val deglared = SpecularReflection.suppress(balanced)
        return resizeAndCenterCrop(deglared, modelInputSize)
    }

    private fun resizeAndCenterCrop(bitmap: Bitmap, target: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val shortSide = minOf(w, h)
        if (shortSide == 0) return bitmap

        val scale = (target + 32f) / shortSide
        val resizedW = (w * scale).toInt().coerceAtLeast(target)
        val resizedH = (h * scale).toInt().coerceAtLeast(target)
        val resized = Bitmap.createScaledBitmap(bitmap, resizedW, resizedH, true)
        val cropX = ((resizedW - target) / 2).coerceAtLeast(0)
        val cropY = ((resizedH - target) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(resized, cropX, cropY, target, target)
    }
}
