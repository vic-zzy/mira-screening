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
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mira.screening.gemma.GemmaInference
import com.mira.screening.gemma.PromptTemplates

/**
 * A drop-in result-screen card that asks Gemma 4 for a patient-friendly,
 * locale-aware explanation of the just-completed screening, then surfaces it
 * alongside a Play button so the CHW can read it aloud to the patient via
 * text-to-speech.
 *
 * The card owns its own Gemma lifecycle. It kicks off generation in a
 * LaunchedEffect keyed on the inputs, shows a loading state during the call,
 * and reaches a Loaded state with the generated text. TTS playback is
 * delegated upward via onPlayPressed so the host screen can route through
 * its existing TextToSpeech engine.
 *
 * Designed to be embedded inside the existing ResultScreen, below the
 * classifier headline + confidence row. Adds a clear "Mira explains"
 * visual label so the CHW understands this is AI-generated narration, not
 * a clinical quote.
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
    var state by remember { mutableStateOf<NarrationState>(NarrationState.Loading) }

    LaunchedEffect(resultLabel, confidencePercent, heatmapFocus, languageName) {
        state = NarrationState.Loading
        state = try {
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
                state = state,
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
    state: NarrationState,
    languageName: String,
    onPlayPressed: (text: String) -> Unit
) {
    when (state) {
        is NarrationState.Loading -> LoadingBody(languageName = languageName)
        is NarrationState.Loaded -> LoadedBody(
            text = state.text,
            languageName = languageName,
            onPlayPressed = onPlayPressed
        )
        is NarrationState.Error -> ErrorBody()
    }
}

@Composable
private fun LoadingBody(languageName: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
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
}

@Composable
private fun LoadedBody(
    text: String,
    languageName: String,
    onPlayPressed: (text: String) -> Unit
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(onClick = { onPlayPressed(text) }) {
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

@Composable
private fun ErrorBody() {
    Text(
        text = "Mira could not prepare an explanation right now. The screening result above is still valid.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private sealed interface NarrationState {
    data object Loading : NarrationState
    data class Loaded(val text: String) : NarrationState
    data class Error(val message: String) : NarrationState
}
