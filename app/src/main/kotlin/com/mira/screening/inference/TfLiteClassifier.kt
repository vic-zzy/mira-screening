package com.mira.screening.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp

// TFLite-backed classifier. Loads via_model.tflite from assets at app start;
// if the file is absent (or fails to load), isReady stays false and ViaModel
// falls back to the stub. Once the trained model is exported via
// python/export_tflite.py and dropped into app/src/main/assets/, this becomes
// the live inference path with no other code change.
//
// Input layout:  NHWC float32 [1, 224, 224, 3], ImageNet-normalized.
// Output layout: depends on the export, we autodetect at load time.
//   - Single output [1, 2]:        classification logits only
//   - Two outputs [1, 2] + [1, S, S]: classification logits + per-patch saliency
//
// When saliency is present, the per-patch L2-norm map (e.g. 16x16 for
// DINOv2-S/14 at 224 input) is flattened row-major into ViaResult.heatmap so
// the Result screen renders it via the existing viridis colormap overlay.
class TfLiteClassifier(context: Context) {
    private val interpreter: Interpreter? = loadInterpreter(context)
    private val logitsIndex: Int
    private val saliencyIndex: Int
    private val saliencySide: Int

    init {
        // Detect which output is which by tensor shape, order isn't
        // guaranteed across TFLite conversions, so we don't assume index 0.
        var logitsIdx = -1
        var saliencyIdx = -1
        var sSide = 0
        val itp = interpreter
        if (itp != null) {
            for (i in 0 until itp.outputTensorCount) {
                val shape = itp.getOutputTensor(i).shape()
                when (shape.size) {
                    2 -> if (shape[1] == NUM_CLASSES) logitsIdx = i
                    3 -> if (shape[1] == shape[2] && shape[1] >= 4) {
                        saliencyIdx = i
                        sSide = shape[1]
                    }
                    4 -> if (shape[1] == shape[2] && shape[3] == 1 && shape[1] >= 4) {
                        // Some converters emit [1, S, S, 1]
                        saliencyIdx = i
                        sSide = shape[1]
                    }
                }
            }
        }
        logitsIndex = logitsIdx
        saliencyIndex = saliencyIdx
        saliencySide = sSide
        if (itp != null) {
            Log.i(
                TAG,
                "outputs: logits@$logitsIndex, saliency@$saliencyIndex (${saliencySide}x$saliencySide)"
            )
        }
    }

    val isReady: Boolean get() = interpreter != null && logitsIndex >= 0
    val hasSaliency: Boolean get() = saliencyIndex >= 0 && saliencySide > 0

    fun classify(bitmap: Bitmap): ViaResult {
        val itp = interpreter
            ?: throw IllegalStateException("TfLiteClassifier called when isReady=false")

        val started = System.currentTimeMillis()
        val input = preprocess(bitmap, INPUT_SIZE)

        val logitsBuf = Array(1) { FloatArray(NUM_CLASSES) }

        val (heatmapFlat, hmW, hmH) = if (hasSaliency) {
            val side = saliencySide
            val saliencyBuf = Array(1) { Array(side) { FloatArray(side) } }
            val outputs: MutableMap<Int, Any> = HashMap()
            outputs[logitsIndex] = logitsBuf
            outputs[saliencyIndex] = saliencyBuf
            itp.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs)

            val flat = FloatArray(side * side)
            for (y in 0 until side) for (x in 0 until side) {
                flat[y * side + x] = saliencyBuf[0][y][x]
            }
            Triple(flat, side, side)
        } else {
            itp.run(input, logitsBuf)
            Triple(null, 0, 0)
        }

        val probs = softmax(logitsBuf[0])
        val classification =
            if (probs[1] > probs[0]) ViaClassification.POSITIVE
            else ViaClassification.NEGATIVE
        val confidence = maxOf(probs[0], probs[1])

        return ViaResult(
            classification = classification,
            confidence = confidence,
            heatmap = heatmapFlat,
            heatmapWidth = hmW,
            heatmapHeight = hmH,
            processingTimeMs = System.currentTimeMillis() - started
        )
    }

    private fun preprocess(bitmap: Bitmap, size: Int): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val buffer = ByteBuffer
            .allocateDirect(4 * size * size * 3)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(size * size)
        resized.getPixels(pixels, 0, size, 0, 0, size, size)

        for (p in pixels) {
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            buffer.putFloat((r - IMAGENET_MEAN[0]) / IMAGENET_STD[0])
            buffer.putFloat((g - IMAGENET_MEAN[1]) / IMAGENET_STD[1])
            buffer.putFloat((b - IMAGENET_MEAN[2]) / IMAGENET_STD[2])
        }
        buffer.rewind()
        return buffer
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val maxL = logits.max()
        val exps = logits.map { exp((it - maxL).toDouble()).toFloat() }
        val sum = exps.sum()
        return exps.map { it / sum }.toFloatArray()
    }

    fun close() {
        interpreter?.close()
    }

    companion object {
        private const val TAG = "TfLiteClassifier"
        private const val MODEL_FILE = "via_model.tflite"
        private const val INPUT_SIZE = 224
        private const val NUM_CLASSES = 2

        private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        private fun loadInterpreter(context: Context): Interpreter? = try {
            val fd = context.assets.openFd(MODEL_FILE)
            val buffer = FileInputStream(fd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.startOffset,
                fd.declaredLength
            )
            Interpreter(buffer).also {
                Log.i(TAG, "Loaded $MODEL_FILE (${fd.declaredLength} bytes)")
            }
        } catch (t: Throwable) {
            Log.i(TAG, "$MODEL_FILE not in assets, using stub classifier")
            null
        }
    }
}
