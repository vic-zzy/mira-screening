package com.mira.screening.inference

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.random.Random

// Front door for VIA classification. Two execution paths:
//   1. TFLite-backed: real trained model loaded from assets/via_model.tflite
//   2. Stub: pseudo-classification + synthetic heatmap (used when no model ships)
//
// The right path is selected automatically at app start via init(context).
// ProcessingScreen calls classify() without knowing or caring which path runs.
object ViaModel {
    @Volatile
    private var tflite: TfLiteClassifier? = null

    fun init(context: Context) {
        val c = TfLiteClassifier(context.applicationContext)
        if (c.isReady) tflite = c
    }

    val isUsingTrainedModel: Boolean get() = tflite != null

    suspend fun classify(image: Bitmap): ViaResult {
        val classifier = tflite
        return if (classifier != null) {
            withContext(Dispatchers.Default) { classifier.classify(image) }
        } else {
            stubClassify(image)
        }
    }

    private suspend fun stubClassify(image: Bitmap): ViaResult {
        val startMs = System.currentTimeMillis()
        delay(STUB_INFERENCE_DELAY_MS)

        val w = STUB_HEATMAP_SIZE
        val h = STUB_HEATMAP_SIZE
        val heatmap = FloatArray(w * h)
        val cx = w / 2f + Random.nextFloat() * 12f - 6f
        val cy = h / 2f + Random.nextFloat() * 12f - 6f
        val sigma = 11f + Random.nextFloat() * 6f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = (x - cx) / sigma
                val dy = (y - cy) / sigma
                heatmap[y * w + x] = exp(-(dx * dx + dy * dy)).toFloat()
            }
        }

        val draw = Random.nextFloat()
        val (cls, conf) = when {
            draw < 0.62f -> ViaClassification.NEGATIVE to (0.78f + Random.nextFloat() * 0.18f)
            draw < 0.88f -> ViaClassification.POSITIVE to (0.71f + Random.nextFloat() * 0.22f)
            else         -> ViaClassification.INCONCLUSIVE to (0.42f + Random.nextFloat() * 0.18f)
        }

        return ViaResult(
            classification = cls,
            confidence = conf,
            heatmap = heatmap,
            heatmapWidth = w,
            heatmapHeight = h,
            processingTimeMs = System.currentTimeMillis() - startMs
        )
    }

    private const val STUB_HEATMAP_SIZE = 64
    private const val STUB_INFERENCE_DELAY_MS = 800L
}
