package com.mira.screening.preprocessing

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max

// Multi-frame averaging for noise reduction. Capturing N frames and averaging
// pixel-wise reduces shot noise by sqrt(N), which matters at low light in
// LMIC clinics. The motion-aware variant (averageAligned) drops frames that
// differ too much from the reference, so a single move during burst doesn't
// blur the composite, only the misaligned frame is dropped, the rest are
// still averaged.
//
// Phase-2: replace MSE-based motion detection with phase correlation that
// can also realign small shifts before averaging.
object MultiFrameStack {

    /** Plain pixel-wise average. Caller guarantees frames are aligned. */
    fun average(frames: List<Bitmap>): Bitmap {
        require(frames.isNotEmpty()) { "Need at least one frame to average" }
        val w = frames[0].width
        val h = frames[0].height
        require(frames.all { it.width == w && it.height == h }) {
            "All frames must have identical dimensions"
        }

        val n = frames.size
        val sumR = LongArray(w * h)
        val sumG = LongArray(w * h)
        val sumB = LongArray(w * h)
        val pixels = IntArray(w * h)

        for (frame in frames) {
            frame.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                val p = pixels[i]
                sumR[i] += Color.red(p)
                sumG[i] += Color.green(p)
                sumB[i] += Color.blue(p)
            }
        }

        val out = IntArray(w * h)
        for (i in out.indices) {
            val r = (sumR[i] / n).toInt().coerceIn(0, 255)
            val g = (sumG[i] / n).toInt().coerceIn(0, 255)
            val b = (sumB[i] / n).toInt().coerceIn(0, 255)
            out[i] = Color.argb(255, r, g, b)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Motion-aware average. The first frame is the reference; each subsequent
     * frame is compared to it via mean-squared per-channel pixel error
     * (subsampled for speed). Frames whose MSE exceeds [mseThreshold] are
     * dropped, the user moved between shots, and including the misaligned
     * frame would blur the composite.
     *
     * Always produces a result: if every subsequent frame is bad, the
     * reference frame alone is returned. Returns the count of frames
     * actually kept via [BurstResult] so the UI can surface it.
     */
    fun averageAligned(
        frames: List<Bitmap>,
        mseThreshold: Float = DEFAULT_MSE_THRESHOLD,
        sampleStride: Int = DEFAULT_SAMPLE_STRIDE
    ): BurstResult {
        require(frames.isNotEmpty()) { "Need at least one frame to average" }
        if (frames.size == 1) return BurstResult(frames[0], kept = 1, total = 1)

        val reference = frames[0]
        val kept = mutableListOf(reference)
        val droppedIndices = mutableListOf<Int>()
        for (i in 1 until frames.size) {
            val mse = meanSquaredError(reference, frames[i], sampleStride)
            if (mse <= mseThreshold) {
                kept.add(frames[i])
            } else {
                droppedIndices.add(i)
            }
        }
        val composite = if (kept.size == 1) reference else average(kept)
        return BurstResult(
            bitmap = composite,
            kept = kept.size,
            total = frames.size,
            droppedIndices = droppedIndices
        )
    }

    private fun meanSquaredError(a: Bitmap, b: Bitmap, sampleStride: Int): Float {
        require(a.width == b.width && a.height == b.height) {
            "Frame dimensions must match for MSE computation"
        }
        val w = a.width
        val h = a.height
        val pixelsA = IntArray(w * h)
        val pixelsB = IntArray(w * h)
        a.getPixels(pixelsA, 0, w, 0, 0, w, h)
        b.getPixels(pixelsB, 0, w, 0, 0, w, h)

        var sumSqErr = 0.0
        var count = 0
        for (y in 0 until h step sampleStride) {
            val rowOffset = y * w
            for (x in 0 until w step sampleStride) {
                val idx = rowOffset + x
                val pa = pixelsA[idx]
                val pb = pixelsB[idx]
                val dr = (Color.red(pa) - Color.red(pb))
                val dg = (Color.green(pa) - Color.green(pb))
                val db = (Color.blue(pa) - Color.blue(pb))
                sumSqErr += (dr * dr + dg * dg + db * db).toDouble() / 3.0
                count++
            }
        }
        return (sumSqErr / max(count, 1)).toFloat()
    }

    // Tuning knobs, generous defaults so we drop only on real motion. Real
    // cervix-image bursts at ~300ms per shot under stable speculum lighting
    // typically have MSE < 1500 frame-to-frame; a clear move spikes well
    // above 5000.
    private const val DEFAULT_MSE_THRESHOLD = 5000f
    private const val DEFAULT_SAMPLE_STRIDE = 8
}

/** Result of a motion-aware burst average. */
data class BurstResult(
    val bitmap: Bitmap,
    val kept: Int,
    val total: Int,
    val droppedIndices: List<Int> = emptyList()
)
