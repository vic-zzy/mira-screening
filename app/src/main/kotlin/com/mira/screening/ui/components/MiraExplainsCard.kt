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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudDownload
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
import kotlinx.coroutines.flow.catch

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
    isSpeaking: Boolean = false,
    // When non-null, the card skips generation entirely and renders this text
    // immediately as a Loaded narration. Used by the History detail view to
    // replay a previously-saved narration without paying generation cost.
    cachedNarration: String? = null,
    // Fires exactly once when a fresh generation completes successfully,
    // carrying the final text and the language it was written in so the
    // caller can persist it. Never fires when [cachedNarration] is set.
    onNarrationReady: (text: String, languageName: String) -> Unit = { _, _ -> },
    onPlayPressed: (text: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val gemmaState by GemmaInference.state.collectAsState()
    var narration by remember { mutableStateOf<NarrationState>(NarrationState.Idle) }

    // Kick off generation only once Gemma is ready and we haven't already
    // run for this (resultLabel, confidencePercent, language) triple.
    // Streaming, not blocking. Switching from a single generate() call to
    // generateStream gives us the same total wall time but a dramatically
    // better perceived latency: instead of staring at "Preparing an
    // explanation" for 20+ seconds while Gemma cranks on emulator CPU, the
    // user watches the narration write itself one token at a time. The
    // play button is gated on the Streaming -> Loaded transition so users
    // cannot accidentally TTS-read a half-generated explanation.
    //
    // Short-circuit: if the caller supplied a cachedNarration (History
    // detail view replaying a saved one), skip generation entirely and
    // render it directly as Loaded.
    LaunchedEffect(
        gemmaState is GemmaInference.State.Ready,
        resultLabel,
        confidencePercent,
        heatmapFocus,
        languageName,
        cachedNarration
    ) {
        if (cachedNarration != null) {
            narration = NarrationState.Loaded(text = cachedNarration)
            return@LaunchedEffect
        }
        if (gemmaState !is GemmaInference.State.Ready) return@LaunchedEffect
        narration = NarrationState.Loading
        val builder = StringBuilder()
        var streamErrored = false
        try {
            GemmaInference.generateStream(
                prompt = PromptTemplates.ResultNarration.userPrompt(
                    resultLabel = resultLabel,
                    confidencePercent = confidencePercent,
                    heatmapFocus = heatmapFocus,
                    languageName = languageName
                ),
                systemInstruction = PromptTemplates.ResultNarration.systemInstruction
            ).catch { t ->
                streamErrored = true
                narration = NarrationState.Error(message = t.message.orEmpty())
            }.collect { token ->
                builder.append(token)
                narration = NarrationState.Streaming(partial = builder.toString().trim())
            }
            if (!streamErrored && builder.isNotEmpty()) {
                val finalText = builder.toString().trim()
                narration = NarrationState.Loaded(text = finalText)
                // Notify the caller exactly once on a clean completion so they
                // can persist the narration alongside the rest of the record.
                onNarrationReady(finalText, languageName)
            }
        } catch (t: Throwable) {
            // Upstream throw before the Flow even starts (e.g. engine
            // disappeared between Ready and our send). Land as a friendly
            // error rather than crashing the card.
            narration = NarrationState.Error(message = t.message.orEmpty())
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
                isSpeaking = isSpeaking,
                hasCachedNarration = cachedNarration != null,
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
    isSpeaking: Boolean,
    hasCachedNarration: Boolean,
    onPlayPressed: (text: String) -> Unit
) {
    // Cached-narration path (History detail view): we already have the text
    // in hand, so Gemma's engine state is irrelevant. Skip the download /
    // loading / error dispatch entirely and render the narration directly.
    // Without this, a user who opens History while Gemma is still
    // downloading would see a download progress bar inside the card instead
    // of their saved narration, which makes no sense.
    if (hasCachedNarration) {
        NarrationBody(
            narration = narration,
            isSpeaking = isSpeaking,
            onPlayPressed = onPlayPressed
        )
        return
    }
    when (gemmaState) {
        is GemmaInference.State.Downloading -> DownloadingBody(state = gemmaState)
        is GemmaInference.State.LoadingEngine -> LoadingEngineBody()
        is GemmaInference.State.Error -> EngineErrorBody(message = gemmaState.message)
        GemmaInference.State.Idle -> LoadingEngineBody()
        GemmaInference.State.Ready -> NarrationBody(
            narration = narration,
            isSpeaking = isSpeaking,
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
    isSpeaking: Boolean,
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
                // The current language is already displayed as a label in
                // the card header, so we deliberately do not repeat it here.
                text = "Preparing an explanation.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        is NarrationState.Streaming -> MarkdownText(
            // Show the partial text as it streams in. No play button yet:
            // the user should not be able to TTS-read a half-finished
            // explanation. The button reappears in the Loaded branch
            // below as soon as generation completes.
            text = narration.partial,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        is NarrationState.Loaded -> Column {
            MarkdownText(
                text = narration.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                // Single button that pivots between Play and Stop based on
                // whether THIS narration is currently being spoken. The
                // parent (ResultScreen) tracks the active utterance and
                // tells us via isSpeaking. Matching the play-chip pattern
                // for the upper "Play result" control so both buttons feel
                // like the same gesture.
                TextButton(onClick = { onPlayPressed(narration.text) }) {
                    Icon(
                        imageVector = if (isSpeaking) {
                            Icons.Filled.Stop
                        } else {
                            Icons.Filled.PlayArrow
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isSpeaking) "Stop" else "Play")
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
    /** Generation is in progress; [partial] holds whatever text has streamed so far. */
    data class Streaming(val partial: String) : NarrationState
    /** Generation finished cleanly. Play button is enabled here. */
    data class Loaded(val text: String) : NarrationState
    data class Error(val message: String) : NarrationState
}
