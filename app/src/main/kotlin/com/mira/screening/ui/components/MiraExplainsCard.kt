package com.mira.screening.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mira.screening.gemma.GemmaInference
import com.mira.screening.gemma.PromptTemplates

/**
 * A drop-in result-screen card that asks Gemma 4 for a patient-friendly,
 * locale-aware explanation of the just-completed screening, then surfaces it
 * alongside a Play button so the CHW can read it aloud to the patient via
 * text-to-speech.
 *
 * The card observes [GemmaInference.state] so it shows the right thing at
 * every step of the initialization pipeline: a download progress bar on
 * first launch while the model is being fetched, an engine-loading spinner
 * while LiteRT-LM warms up, the generated explanation once everything is
 * ready, or a friendly error if any step failed.
 *
 * TTS playback is delegated upward via onPlayPressed so the host screen can
 * route through its existing TextToSpeech engine.
 */
@Composable
fun MiraExplainsCard(
    resultLabel: String,
    confidencePercent: Int,
    heatmapFocus: String,
    languageName: String,
    onPlayPressed: (text: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val gemmaState by GemmaInference.state.collectAsState()
    var narration by remember { mutableStateOf<NarrationState>(NarrationState.Idle) }

    // Kick off generation only once Gemma is ready and we haven't already
    // run for this (resultLabel, confidencePercent, language) triple.
    LaunchedEffect(
        gemmaState is GemmaInference.State.Ready,
        resultLabel,
        confidencePercent,
        heatmapFocus,
        languageName
    ) {
        if (gemmaState !is GemmaInference.State.Ready) return@LaunchedEffect
        narration = NarrationState.Loading
        narration = try {
            val generated = GemmaInference.generate(
                prompt = PromptTemplates.ResultNarration.userPrompt(
                    resultLabel = resultLabel,
                    confidencePercent = confidencePercent,
                    heatmapFocus = heatmapFocus,
                    languageName = languageName
                ),
                systemInstruction = PromptTemplates.ResultNarration.systemInstruction
            )
            NarrationState.Loaded(text = generated.trim())
        } catch (t: Throwable) {
            NarrationState.Error(message = t.message.orEmpty())
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            HeaderRow(languageName = languageName)
            Spacer(Modifier.height(12.dp))
            BodyContent(
                gemmaState = gemmaState,
                narration = narration,
                languageName = languageName,
                onPlayPressed = onPlayPressed
            )
        }
    }
}

@Composable
private fun HeaderRow(languageName: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Mira explains",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = languageName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BodyContent(
    gemmaState: GemmaInference.State,
    narration: NarrationState,
    languageName: String,
    onPlayPressed: (text: String) -> Unit
) {
    when (gemmaState) {
        is GemmaInference.State.Downloading -> DownloadingBody(state = gemmaState)
        is GemmaInference.State.LoadingEngine -> LoadingEngineBody()
        is GemmaInference.State.Error -> EngineErrorBody(message = gemmaState.message)
        GemmaInference.State.Idle -> LoadingEngineBody()
        GemmaInference.State.Ready -> NarrationBody(
            narration = narration,
            languageName = languageName,
            onPlayPressed = onPlayPressed
        )
    }
}

@Composable
private fun DownloadingBody(state: GemmaInference.State.Downloading) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Downloading Mira's reasoning model",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${state.percent}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { state.percent / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outlineVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "One-time download. The model stays on this phone afterwards. " +
                "${formatBytes(state.bytesDownloaded)} of ${formatBytes(state.totalBytes)}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LoadingEngineBody() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Loading the on-device reasoning model.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EngineErrorBody(message: String) {
    Text(
        text = "Mira could not load her reasoning model. The screening result above " +
            "is still valid, but the multilingual explanation is unavailable. " +
            "(${message.take(100)})",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun NarrationBody(
    narration: NarrationState,
    languageName: String,
    onPlayPressed: (text: String) -> Unit
) {
    when (narration) {
        is NarrationState.Idle, is NarrationState.Loading -> Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Preparing an explanation in $languageName.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        is NarrationState.Loaded -> Column {
            Text(
                text = narration.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { onPlayPressed(narration.text) }) {
                    Icon(
                        imageVector = Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Play in $languageName")
                }
            }
        }
        is NarrationState.Error -> Text(
            text = "Mira could not prepare an explanation right now. The screening " +
                "result above is still valid.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 1.0) return "%.2f GB".format(gb)
    val mb = bytes / (1024.0 * 1024.0)
    return "%.0f MB".format(mb)
}

private sealed interface NarrationState {
    data object Idle : NarrationState
    data object Loading : NarrationState
    data class Loaded(val text: String) : NarrationState
    data class Error(val message: String) : NarrationState
}
