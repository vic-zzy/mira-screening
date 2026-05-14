package com.mira.screening.ui.screens

import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mira.screening.R
import com.mira.screening.data.CaptureStore
import com.mira.screening.data.ScreeningRepository
import com.mira.screening.inference.ViaClassification
import com.mira.screening.inference.ViaResult
import com.mira.screening.ui.components.MiraExplainsCard
import com.mira.screening.ui.theme.miraStatus
import com.mira.screening.ui.util.heatmapToBitmap
import java.util.Locale
import kotlin.math.sqrt

@Composable
fun ResultScreen(
    captureId: String,
    onDone: () -> Unit
) {
    val result = CaptureStore.getResult(captureId)
    val capture = CaptureStore.get(captureId)

    if (result == null || capture == null) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.result_unavailable))
            }
        }
        return
    }

    var showHeatmap by remember { mutableStateOf(true) }
    var override by remember { mutableStateOf<ViaClassification?>(null) }
    var showOverrideDialog by remember { mutableStateOf(false) }
    var showHeatmapHelp by remember { mutableStateOf(false) }
    var showConfidenceHelp by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val repo = remember { ScreeningRepository(context) }
    var ttsReady by remember { mutableStateOf(false) }
    var speaking by remember { mutableStateOf(false) }
    val tts = remember {
        TextToSpeech(context) { status -> ttsReady = status == TextToSpeech.SUCCESS }
    }
    DisposableEffect(tts) {
        tts.language = Locale.getDefault()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { speaking = true }
            override fun onDone(utteranceId: String?) { speaking = false }
            @Deprecated("Deprecated in API 21")
            override fun onError(utteranceId: String?) { speaking = false }
        })
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }

    val effectiveClassification = override ?: result.classification
    val label = stringResource(labelRes(effectiveClassification))
    val action = stringResource(actionRes(effectiveClassification))

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Header: title only, no back nav (the "Done" button is the only
            // forward-navigation hook from this screen).
            Text(
                text = stringResource(R.string.result_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )

            // The middle section (image, chips, result card, Mira explains,
            // disagree button) needs to scroll because the Mira explains
            // card grows with the generated narration, and on smaller
            // viewports the combined height exceeds the screen. The
            // header and Done button stay fixed; only the body scrolls.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Image(
                        bitmap = capture.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (showHeatmap && result.heatmap != null) {
                        val hmBitmap = remember(result) {
                            heatmapToBitmap(
                                heatmap = result.heatmap,
                                width = result.heatmapWidth,
                                height = result.heatmapHeight,
                                alpha = 150
                            )
                        }
                        Image(
                            bitmap = hmBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.result_heatmap_a11y),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (result.heatmap != null) {
                        FilterChip(
                            selected = showHeatmap,
                            onClick = { showHeatmap = !showHeatmap },
                            shape = RoundedCornerShape(50),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            label = {
                                Text(
                                    if (showHeatmap) stringResource(R.string.result_hide_heatmap)
                                    else stringResource(R.string.result_show_heatmap)
                                )
                            }
                        )
                        IconButton(
                            onClick = { showHeatmapHelp = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = stringResource(R.string.help_heatmap_title),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    AssistChip(
                        onClick = {
                            if (speaking) {
                                tts.stop()
                                speaking = false
                            } else {
                                // Play loud and clear: max volume on the
                                // music stream, neutral pan. STREAM_MUSIC
                                // is the standard TTS stream and what the
                                // user controls with the side volume rocker
                                // by default. Some emulators and devices
                                // default the engine volume below 1.0 so
                                // we set it explicitly.
                                val params = Bundle().apply {
                                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "mira-result")
                                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                                    putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, 0.0f)
                                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                                }
                                tts.speak("$label. $action", TextToSpeech.QUEUE_FLUSH, params, "mira-result")
                            }
                        },
                        enabled = ttsReady,
                        shape = RoundedCornerShape(50),
                        colors = AssistChipDefaults.assistChipColors(),
                        label = {
                            Text(
                                if (speaking) stringResource(R.string.result_stop)
                                else stringResource(R.string.result_play)
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (speaking) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.height(18.dp)
                            )
                        }
                    )
                }

                ResultCard(
                    result = result,
                    effectiveClassification = effectiveClassification,
                    label = label,
                    action = action,
                    isOverridden = override != null,
                    onConfidenceHelp = { showConfidenceHelp = true }
                )

                // Gemma 4 multilingual narration of the result. Owns its own
                // generation lifecycle; TTS playback is routed through the
                // existing TextToSpeech engine via the onPlayPressed callback.
                MiraExplainsCard(
                    resultLabel = label,
                    confidencePercent = (result.confidence * 100).toInt(),
                    heatmapFocus = remember(result) {
                        heatmapFocusPhrase(
                            heatmap = result.heatmap,
                            width = result.heatmapWidth,
                            height = result.heatmapHeight
                        )
                    },
                    languageName = remember {
                        localeToLanguageName(Locale.getDefault().language)
                    },
                    onPlayPressed = { textToSpeak ->
                        if (speaking) {
                            tts.stop()
                            speaking = false
                        } else if (ttsReady) {
                            val params = Bundle().apply {
                                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "mira-explain")
                                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                                putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, 0.0f)
                                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                            }
                            tts.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, "mira-explain")
                        }
                    }
                )

                TextButton(
                    onClick = { showOverrideDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.result_disagree),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Button(
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(
                        stringResource(R.string.action_done),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }

    if (showOverrideDialog) {
        OverrideDialog(
            current = override,
            onSelect = { picked ->
                override = picked
                showOverrideDialog = false
                val existing = repo.list().firstOrNull { it.id == captureId }
                if (existing != null) {
                    repo.save(existing.copy(userOverride = picked), bitmap = null, persistImage = false)
                }
            },
            onDismiss = { showOverrideDialog = false }
        )
    }

    if (showHeatmapHelp) {
        HelpDialog(
            title = stringResource(R.string.help_heatmap_title),
            body = stringResource(R.string.help_heatmap_body),
            onDismiss = { showHeatmapHelp = false }
        )
    }
    if (showConfidenceHelp) {
        HelpDialog(
            title = stringResource(R.string.help_confidence_title),
            body = stringResource(R.string.help_confidence_body),
            onDismiss = { showConfidenceHelp = false }
        )
    }
}

@Composable
private fun HelpDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = { Text(title) },
        text = { Text(body, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.help_dialog_close))
            }
        }
    )
}

/**
 * Result card: a colored squircle status icon on the left, headline + action
 * stacked next to it, and a confidence bar at the bottom. Pulls colors from
 * the named status palette so positive/negative/inconclusive read consistently
 * across History and here.
 */
@Composable
private fun ResultCard(
    result: ViaResult,
    effectiveClassification: ViaClassification,
    label: String,
    action: String,
    isOverridden: Boolean,
    onConfidenceHelp: () -> Unit
) {
    val s = MaterialTheme.miraStatus
    val (bg, dot) = when (effectiveClassification) {
        ViaClassification.POSITIVE -> s.positiveBg to s.positiveDot
        ViaClassification.NEGATIVE -> s.negativeBg to s.negativeDot
        ViaClassification.INCONCLUSIVE -> s.inconclusiveBg to s.inconclusiveDot
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(20.dp)
    ) {
        if (isOverridden) {
            Text(
                stringResource(R.string.result_override_banner),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(12.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(dot)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = action,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onConfidenceHelp)
        ) {
            Text(
                text = stringResource(
                    R.string.result_confidence,
                    (result.confidence * 100).toInt()
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = stringResource(R.string.help_confidence_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { result.confidence },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surface
        )
    }
}

@Composable
private fun OverrideDialog(
    current: ViaClassification?,
    onSelect: (ViaClassification) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = { Text(stringResource(R.string.result_override_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.result_override_dialog_body))
                Spacer(Modifier.height(12.dp))
                OverrideOption(
                    label = stringResource(R.string.result_override_negative),
                    selected = selected == ViaClassification.NEGATIVE,
                    onClick = { selected = ViaClassification.NEGATIVE }
                )
                OverrideOption(
                    label = stringResource(R.string.result_override_positive),
                    selected = selected == ViaClassification.POSITIVE,
                    onClick = { selected = ViaClassification.POSITIVE }
                )
                OverrideOption(
                    label = stringResource(R.string.result_override_inconclusive),
                    selected = selected == ViaClassification.INCONCLUSIVE,
                    onClick = { selected = ViaClassification.INCONCLUSIVE }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let { onSelect(it) } },
                enabled = selected != null
            ) { Text(stringResource(R.string.result_override_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.result_override_cancel))
            }
        }
    )
}

@Composable
private fun OverrideOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun labelRes(c: ViaClassification): Int = when (c) {
    ViaClassification.POSITIVE -> R.string.result_positive
    ViaClassification.NEGATIVE -> R.string.result_negative
    ViaClassification.INCONCLUSIVE -> R.string.result_inconclusive
}

private fun actionRes(c: ViaClassification): Int = when (c) {
    ViaClassification.POSITIVE -> R.string.result_action_refer
    ViaClassification.NEGATIVE -> R.string.result_action_followup
    ViaClassification.INCONCLUSIVE -> R.string.result_action_reimage
}

/**
 * Map an ISO 639-1 locale tag (the part before any region suffix) to the
 * English name of the language. Gemma takes the English name in the prompt
 * and responds in that language. Defaults to English if unknown.
 */
private fun localeToLanguageName(tag: String): String =
    when (tag.substringBefore('-').lowercase()) {
        "en" -> "English"
        "es" -> "Spanish"
        "pt" -> "Portuguese"
        "fr" -> "French"
        "sw" -> "Swahili"
        "ha" -> "Hausa"
        "yo" -> "Yoruba"
        "ig" -> "Igbo"
        "lg" -> "Luganda"
        "qu" -> "Quechua"
        else -> "English"
    }

/**
 * Quick characterization of where the attention heatmap is concentrated.
 * Computes the weighted centroid of the heatmap and reports whether it's
 * near the image center (where the cervix should be framed) or off to a
 * side (which is a sanity-check signal for the CHW that the model may be
 * reading speculum walls or other artifacts rather than the cervix).
 *
 * This phrase goes into the Gemma narration prompt and the decision-support
 * prompt, so the language is written for direct use in those contexts.
 */
private fun heatmapFocusPhrase(
    heatmap: FloatArray?,
    width: Int,
    height: Int
): String {
    if (heatmap == null || heatmap.isEmpty() || width <= 0 || height <= 0) {
        return "in the central area of the image"
    }
    var sumX = 0.0
    var sumY = 0.0
    var sumW = 0.0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val w = heatmap[y * width + x].coerceAtLeast(0f).toDouble()
            sumX += x * w
            sumY += y * w
            sumW += w
        }
    }
    if (sumW == 0.0) return "in the central area of the image"
    val cx = (sumX / sumW) / width
    val cy = (sumY / sumW) / height
    val dx = cx - 0.5
    val dy = cy - 0.5
    val dist = sqrt(dx * dx + dy * dy)
    return if (dist < 0.15) {
        "centered on the cervix region of the image"
    } else {
        "off-center, away from the expected cervix region"
    }
}

