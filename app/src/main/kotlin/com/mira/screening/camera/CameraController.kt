package com.mira.screening.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.mira.screening.preprocessing.BurstResult
import com.mira.screening.preprocessing.MultiFrameStack
import com.mira.screening.preprocessing.QualityGate
import com.mira.screening.preprocessing.QualityReport
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    fun bindPreview(
        previewView: PreviewView,
        onQuality: (QualityReport) -> Unit
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().apply {
                surfaceProvider = previewView.surfaceProvider
            }

            // CAPTURE_MODE_MINIMIZE_LATENCY rather than MAXIMIZE_QUALITY. The
            // quality-mode does extra per-frame post-processing (HDR-style
            // blending and sharpening) that is mostly wasted work for our
            // pipeline, because we already average three frames downstream
            // via MultiFrameStack.averageAligned. Averaging recovers roughly
            // sqrt(N) in SNR which matches what the quality-mode buys per
            // single frame, so the composite is the same quality in the
            // end, and the burst returns 2 to 3 times faster on real
            // hardware. AWB lock and HIGH_QUALITY noise reduction stay (see
            // applyClinicalCaptureRequestOptions) because those govern
            // color stability and fine-detail preservation, not throughput.
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply {
                    setAnalyzer(analysisExecutor) { proxy ->
                        try {
                            onQuality(QualityGate.analyze(proxy))
                        } catch (t: Throwable) {
                            Log.w(TAG, "quality analysis failed", t)
                        } finally {
                            proxy.close()
                        }
                    }
                }

            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture,
                analysis
            )
            imageCapture = capture
            applyClinicalCaptureRequestOptions()
        }, ContextCompat.getMainExecutor(context))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyClinicalCaptureRequestOptions() {
        val cam = camera ?: return
        val ctl = Camera2CameraControl.from(cam.cameraControl)
        // Lock auto-white-balance for color stability across frames; clinical-grade
        // acetowhite detection requires consistent color rendering.
        val opts = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
            .setCaptureRequestOption(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            )
            .setCaptureRequestOption(
                CaptureRequest.EDGE_MODE,
                CaptureRequest.EDGE_MODE_HIGH_QUALITY
            )
            .build()
        ctl.captureRequestOptions = opts
    }

    fun setTorch(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }

    @SuppressLint("UnsafeOptInUsageError")
    suspend fun capture(): Bitmap = suspendCancellableCoroutine { cont ->
        val capture = imageCapture
        if (capture == null) {
            cont.resumeWithException(IllegalStateException("Camera not bound"))
            return@suspendCancellableCoroutine
        }
        capture.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bytes = image.planes[0].buffer.let { buf ->
                            ByteArray(buf.remaining()).also { buf.get(it) }
                        }
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) cont.resume(bmp)
                        else cont.resumeWithException(IllegalStateException("Bitmap decode failed"))
                    } catch (t: Throwable) {
                        cont.resumeWithException(t)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            }
        )
    }

    /**
     * Capture [frameCount] frames in rapid succession and return a motion-aware
     * average via [MultiFrameStack.averageAligned]. Reduces shot noise by
     * roughly sqrt(N) at the cost of N x shutter time, but resilient to nurse
     * movement during the burst, frames that diverge too much from the
     * reference are dropped instead of blurring the composite.
     *
     * [onProgress] fires before each shutter with the 1-based frame number,
     * so the UI can show "Burst 1 of N", "Burst 2 of N" etc. and tell the
     * nurse to hold steady.
     */
    suspend fun captureBurst(
        frameCount: Int = 3,
        onProgress: (frameNumber: Int) -> Unit = {}
    ): BurstResult {
        require(frameCount >= 1) { "frameCount must be >= 1" }

        val frames = mutableListOf<Bitmap>()
        var lastError: Throwable? = null
        for (i in 0 until frameCount) {
            onProgress(i + 1)
            try {
                frames += capture()
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "burst frame failed (${frames.size}/$frameCount captured)", t)
            }
        }
        if (frames.isEmpty()) {
            throw lastError ?: IllegalStateException("Burst capture produced no frames")
        }
        val result = MultiFrameStack.averageAligned(frames)
        Log.i(TAG, "burst kept ${result.kept}/${result.total} frames (dropped: ${result.droppedIndices})")
        return result
    }

    fun release() {
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraController"
    }
}
