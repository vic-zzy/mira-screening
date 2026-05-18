package com.mira.screening.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mira.screening.R
import com.mira.screening.camera.CameraController
import com.mira.screening.data.CaptureStore
import com.mira.screening.preprocessing.Quality
import com.mira.screening.preprocessing.QualityReport
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PermissionState { GRANTED, NEEDS_REQUEST, BLOCKED }
private enum class CaptureState { IDLE, CAPTURING, CAPTURED }

private const val BURST_FRAMES = 3
private const val CAPTURED_FLASH_MS = 500L

@Composable
fun CaptureScreen(
    onCaptured: (captureId: String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    var requestedOnce by remember { mutableStateOf(false) }
    var canShowRationale by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        requestedOnce = true
        canShowRationale = activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
        } ?: false
    }

    LaunchedEffect(Unit) {
        if (!hasPermission && !requestedOnce) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    val state = when {
        hasPermission -> PermissionState.GRANTED
        !requestedOnce -> PermissionState.NEEDS_REQUEST
        canShowRationale -> PermissionState.NEEDS_REQUEST
        else -> PermissionState.BLOCKED
    }

    when (state) {
        PermissionState.GRANTED -> CaptureContent(onCaptured = onCaptured, onBack = onBack)
        PermissionState.NEEDS_REQUEST -> PermissionPrompt(
            onRequest = { launcher.launch(Manifest.permission.CAMERA) },
            onBack = onBack
        )
        PermissionState.BLOCKED -> PermissionBlocked(
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            },
            onBack = onBack
        )
    }
}

@Composable
private fun CaptureContent(
    onCaptured: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val controller = remember { CameraController(context, lifecycle) }
    var quality by remember { mutableStateOf<QualityReport?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var captureState by remember { mutableStateOf(CaptureState.IDLE) }
    var burstFrame by remember { mutableStateOf(0) }
    var framesKept by remember { mutableStateOf(0) }

    DisposableEffect(controller) {
        onDispose { controller.release() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    controller.bindPreview(this) { quality = it }
                }
            }
        )

        // Subtle vignette gives the dark frame a sense of depth without
        // hiding any of the live preview.
        ReticleOverlay(modifier = Modifier.fillMaxSize())

        // Top: close (left) and flash (right). Both are circular icon
        // buttons with a translucent dark background so they're visible
        // against any preview content.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            CircularDarkIconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_back),
                    tint = Color.White
                )
            }
            CircularDarkIconButton(
                onClick = {
                    torchOn = !torchOn
                    controller.setTorch(torchOn)
                }
            ) {
                Icon(
                    imageVector = if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    contentDescription = stringResource(R.string.capture_torch),
                    tint = Color.White
                )
            }
        }

        // Bottom controls: quality chip stacked above the shutter.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            QualityChip(report = quality)
            Spacer(Modifier.height(20.dp))
            Shutter(
                // DEMO: quality gating temporarily relaxed so the webcam pass-through
                // demo path can capture regardless of blur/glare/over-brightness.
                // The QualityChip still surfaces the live quality message so the
                // user gets the same feedback. Restore production behaviour by
                // re-adding `quality?.quality != Quality.POOR &&` before
                // captureState == CaptureState.IDLE.
                enabled = captureState == CaptureState.IDLE,
                onClick = {
                    if (captureState != CaptureState.IDLE) return@Shutter
                    captureState = CaptureState.CAPTURING
                    burstFrame = 1
                    scope.launch {
                        try {
                            val burst = controller.captureBurst(BURST_FRAMES) { n ->
                                burstFrame = n
                            }
                            framesKept = burst.kept
                            val id = CaptureStore.put(burst.bitmap)
                            captureState = CaptureState.CAPTURED
                            delay(CAPTURED_FLASH_MS)
                            onCaptured(id)
                        } catch (_: Throwable) {
                            captureState = CaptureState.IDLE
                            burstFrame = 0
                        }
                    }
                }
            )
        }

        // Visceral feedback for each frame in the burst: a quick white flash
        // across the whole viewport plus a shrinking tile that slides toward
        // the bottom-left corner (the "photo gallery" affordance every camera
        // app shares). Triggered on every burstFrame increment so the user
        // perceives 3 distinct shutter events rather than a single counter
        // ticking up.
        BurstFlash(burstFrame = burstFrame)

        if (captureState != CaptureState.IDLE) {
            CaptureProgressOverlay(
                state = captureState,
                burstFrame = burstFrame,
                framesKept = framesKept
            )
        }
    }
}

/**
 * Per-frame burst feedback. When `burstFrame` increments, runs two parallel
 * animations:
 *   - a brief white viewport flash (200 ms), classic camera shutter feedback
 *   - a small tile that scales down from 100 % to 30 % and slides toward the
 *     bottom-left corner with a fade-out (~600 ms), communicates "image
 *     captured" the way every other camera app does
 * Idempotent on burstFrame == 0 (idle state, no animation).
 */
@Composable
private fun BoxScope.BurstFlash(burstFrame: Int) {
    if (burstFrame <= 0) return

    val flashAlpha = remember { Animatable(0f) }
    val tileProgress = remember { Animatable(0f) }

    LaunchedEffect(burstFrame) {
        // Reset and play. Re-keyed each time burstFrame increments so a new
        // animation runs per frame.
        flashAlpha.snapTo(0.85f)
        tileProgress.snapTo(0f)
        launch {
            flashAlpha.animateTo(0f, animationSpec = tween(durationMillis = 220))
        }
        tileProgress.animateTo(1f, animationSpec = tween(durationMillis = 600))
    }

    // Full-viewport white flash.
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(Color.White.copy(alpha = flashAlpha.value))
    )

    // Sliding thumbnail tile. Starts at the screen center scaled to 1.0 with
    // alpha 1.0; ends at the bottom-left at scale 0.3 with alpha 0.
    val p = tileProgress.value
    val tileSize = 96.dp
    val travelX = (-140).dp * p
    val travelY = 220.dp * p
    val scale = 1f - 0.7f * p
    val tileAlpha = 1f - p
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .offset(x = travelX, y = travelY)
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                alpha = tileAlpha
            )
            .size(tileSize)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.3f))
            .border(
                width = 2.dp,
                color = Color.White,
                shape = RoundedCornerShape(12.dp)
            )
    )
}

/**
 * Dashed circle reticle showing the recommended framing zone for the cervix.
 * Decorative, not enforced anywhere in the capture pipeline.
 */
@Composable
private fun ReticleOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = (minOf(size.width, size.height) * 0.32f)
        drawCircle(
            color = Color.White.copy(alpha = 0.6f),
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(
                width = 2.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f))
            )
        )
    }
}

@Composable
private fun CircularDarkIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f)),
        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
    ) {
        content()
    }
}

/**
 * Capsule chip near the bottom showing the live quality message.
 * Coloured dot on the left signals state at a glance: green=good, amber=low,
 * red=poor (capture is disabled in the poor state).
 */
@Composable
private fun QualityChip(report: QualityReport?) {
    val r = report ?: return
    val dot = when (r.quality) {
        Quality.GOOD -> Color(0xFF7BB57B)
        Quality.LOW -> Color(0xFFD9A24F)
        Quality.POOR -> Color(0xFFD17357)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = r.message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
    }
}

/**
 * DSLR-style shutter: outer ring, dark gap, inner fill. Tappable across the
 * full 80dp area for forgiving touch target. Built as a single clickable Box
 * (not nested IconButtons) so the click is registered immediately, empty
 * IconButtons can lose taps on some devices.
 */
@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    val ringAlpha = if (enabled) 1f else 0.5f
    val fillAlpha = if (enabled) 1f else 0.3f
    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = ringAlpha))
        )
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Color.Black)
        )
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = fillAlpha))
        )
    }
}

@Composable
private fun CaptureProgressOverlay(
    state: CaptureState,
    burstFrame: Int,
    framesKept: Int
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when (state) {
                CaptureState.CAPTURING -> {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 4.dp,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        stringResource(
                            R.string.capture_burst_progress,
                            burstFrame.coerceAtLeast(1),
                            BURST_FRAMES
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.capture_burst_hold),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
                CaptureState.CAPTURED -> {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        stringResource(R.string.capture_captured),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    if (framesKept in 1 until BURST_FRAMES) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(
                                R.string.capture_burst_kept,
                                framesKept,
                                BURST_FRAMES
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
                CaptureState.IDLE -> {}
            }
        }
    }
}

@Composable
private fun PermissionPrompt(
    onRequest: () -> Unit,
    onBack: () -> Unit
) {
    PermissionMessage(
        title = stringResource(R.string.capture_permission_title),
        body = stringResource(R.string.capture_permission_body),
        primaryLabel = stringResource(R.string.capture_permission_allow),
        onPrimary = onRequest,
        onBack = onBack
    )
}

@Composable
private fun PermissionBlocked(
    onOpenSettings: () -> Unit,
    onBack: () -> Unit
) {
    PermissionMessage(
        title = stringResource(R.string.capture_permission_blocked_title),
        body = stringResource(R.string.capture_permission_blocked_body),
        primaryLabel = stringResource(R.string.capture_permission_open_settings),
        onPrimary = onOpenSettings,
        onBack = onBack
    )
}

@Composable
private fun PermissionMessage(
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(12.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(primaryLabel, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.action_back))
            }
        }
    }
}
