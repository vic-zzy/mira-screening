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

        // Low lighting is a warning, not a gate. Many low-resource clinics
        // do not have ideal lighting, and gating capture on luminance would
        // disadvantage exactly the users Mira is built to serve. The
        // classifier already routes uncertain results to Inconclusive, so
        // the worst case of capturing in poor light is a "please try again"
        // result rather than a wrong confident answer. tooBright, tooBlurry,
        // and tooMuchGlare still gate because each makes the frame
        // physically unusable for inference (white-clipped, motion-smeared,
        // or speculum-mirrored pixels lack the signal the model needs).
        val quality = when {
            tooBright || tooBlurry || tooMuchGlare -> Quality.POOR
            tooDark || somewhatBlurry -> Quality.LOW
            else -> Quality.GOOD
        }

        val message = when {
            tooBlurry -> "Hold still, image is blurry"
            tooBright -> "Too bright, adjust position"
            tooMuchGlare -> "Too much glare, reposition"
            tooDark -> "Low light, capture allowed but result may be inconclusive"
            somewhatBlurry -> "Hold steady, adjusting"
            else -> "Image quality looks good"
        }

        return QualityReport(quality, meanLuma, sharpness, glareFraction, message)
    }
}
