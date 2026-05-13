package com.mira.screening.preprocessing

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

enum class Quality { GOOD, LOW, POOR }

data class QualityReport(
    val quality: Quality,
    val brightness: Float,
    val sharpness: Float,
    val glareFraction: Float,
    val message: String
)

// Lightweight quality check on the Y plane of YUV_420_888 frames.
// Real OpenCV-backed checks (Laplacian variance, specular reflection masks) land
// once we wire OpenCV-Android in. These heuristics are good enough to gate capture.
object QualityGate {
    private const val LUMA_LOW = 0.18f
    private const val LUMA_HIGH = 0.92f
    private const val SHARPNESS_GOOD = 220f
    private const val SHARPNESS_LOW = 90f
    private const val GLARE_THRESHOLD = 245
    private const val GLARE_MAX_FRACTION = 0.18f
    private const val SAMPLE_STEP = 4

    fun analyze(image: ImageProxy): QualityReport {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        var sumLuma = 0L
        var lumaCount = 0
        var glareCount = 0
        var sumGradSq = 0.0
        var gradCount = 0

        for (y in 0 until height step SAMPLE_STEP) {
            val rowOffset = y * rowStride
            var prevLuma = -1
            for (x in 0 until width step SAMPLE_STEP) {
                val idx = rowOffset + x
                if (idx >= data.size) continue
                val luma = data[idx].toInt() and 0xFF
                sumLuma += luma
                lumaCount++
                if (luma >= GLARE_THRESHOLD) glareCount++
                if (prevLuma >= 0) {
                    val d = (luma - prevLuma).toDouble()
                    sumGradSq += d * d
                    gradCount++
                }
                prevLuma = luma
            }
        }

        val meanLuma = if (lumaCount > 0) (sumLuma.toFloat() / lumaCount) / 255f else 0f
        val sharpness = if (gradCount > 0) (sumGradSq / gradCount).toFloat() else 0f
        val glareFraction = if (lumaCount > 0) glareCount.toFloat() / lumaCount else 0f

        val tooDark = meanLuma < LUMA_LOW
        val tooBright = meanLuma > LUMA_HIGH
        val tooBlurry = sharpness < SHARPNESS_LOW
        val somewhatBlurry = sharpness < SHARPNESS_GOOD
        val tooMuchGlare = glareFraction > GLARE_MAX_FRACTION

        val quality = when {
            tooDark || tooBright || tooBlurry || tooMuchGlare -> Quality.POOR
            somewhatBlurry -> Quality.LOW
            else -> Quality.GOOD
        }

        val message = when {
            tooDark -> "Too dark, turn on the light"
            tooBright -> "Too bright, adjust position"
            tooMuchGlare -> "Too much glare, reposition"
            tooBlurry -> "Hold still, image is blurry"
            somewhatBlurry -> "Hold steady, adjusting"
            else -> "Image quality looks good"
        }

        return QualityReport(quality, meanLuma, sharpness, glareFraction, message)
    }
}
