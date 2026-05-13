package com.mira.screening.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mira.screening.R
import com.mira.screening.data.CaptureStore
import com.mira.screening.data.Prefs
import com.mira.screening.data.ScreeningRecord
import com.mira.screening.data.ScreeningRepository
import com.mira.screening.inference.ViaClassification
import com.mira.screening.inference.ViaModel
import com.mira.screening.inference.ViaResult
import com.mira.screening.preprocessing.CervixValidity
import com.mira.screening.preprocessing.CervixValidityGate
import com.mira.screening.preprocessing.Preprocessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class ProcessingStep { COLOR, READING, HEATMAP }

@Composable
fun ProcessingScreen(
    captureId: String,
    onDone: (captureId: String) -> Unit,
    onError: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { ScreeningRepository(context) }
    val prefs = remember { Prefs(context) }

    var step by remember { mutableStateOf(ProcessingStep.COLOR) }

    LaunchedEffect(captureId) {
        val bmp = CaptureStore.get(captureId)
        if (bmp == null) {
            onError()
            return@LaunchedEffect
        }

        step = ProcessingStep.COLOR
        val processed = withContext(Dispatchers.Default) { Preprocessor.process(bmp) }
        delay(200)

        // Cervix-validity gate runs BEFORE the model so we never produce a
        // confident-looking classification on obvious non-cervix inputs (a
        // hand, a wall, the emulator's synthetic scene). Failed validity
        // surfaces as an honest INCONCLUSIVE result with a specific reason
        // captured in the record's notes, clinical credibility wins.
        //
        // Can be disabled via Settings ("Cervix-validity check") for testing.
        // Defaults ON to protect production use.
        val validity = if (prefs.validityGateEnabled) {
            withContext(Dispatchers.Default) { CervixValidityGate.analyze(processed) }
        } else {
            null
        }
        val result: ViaResult
        val notes: String?
        if (validity != null && validity.validity != CervixValidity.LOOKS_VALID) {
            result = ViaResult(
                classification = ViaClassification.INCONCLUSIVE,
                confidence = 0f,
                heatmap = null,
                heatmapWidth = 0,
                heatmapHeight = 0,
                processingTimeMs = 0
            )
            notes = "Validity gate: ${validity.message}"
            step = ProcessingStep.HEATMAP
            delay(200)
        } else {
            step = ProcessingStep.READING
            result = ViaModel.classify(processed)
            // Tightened confidence floor, anything under the threshold becomes
            // INCONCLUSIVE rather than a low-confidence positive/negative.
            val gated = if (result.confidence < prefs.confidenceThreshold) {
                result.copy(classification = ViaClassification.INCONCLUSIVE)
            } else result
            CaptureStore.putResult(captureId, gated)
            step = ProcessingStep.HEATMAP
            delay(400)
            repo.save(
                record = ScreeningRecord(
                    id = captureId,
                    timestampMs = System.currentTimeMillis(),
                    classification = gated.classification,
                    confidence = gated.confidence,
                    patientId = CaptureStore.consumePendingPatientId(),
                    imagePath = null,
                    notes = null
                ),
                bitmap = bmp,
                persistImage = prefs.saveImages
            )
            onDone(captureId)
            return@LaunchedEffect
        }

        // Validity-fail path: store the inconclusive result with the validity
        // message as notes, then move on to the result screen.
        CaptureStore.putResult(captureId, result)
        repo.save(
            record = ScreeningRecord(
                id = captureId,
                timestampMs = System.currentTimeMillis(),
                classification = result.classification,
                confidence = result.confidence,
                patientId = CaptureStore.consumePendingPatientId(),
                imagePath = null,
                notes = notes
            ),
            bitmap = bmp,
            persistImage = prefs.saveImages
        )

        onDone(captureId)
    }

    val stepLabel = when (step) {
        ProcessingStep.COLOR -> stringResource(R.string.processing_step_color)
        ProcessingStep.READING -> stringResource(R.string.processing_step_reading)
        ProcessingStep.HEATMAP -> stringResource(R.string.processing_step_heatmap)
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Mark sits on a soft round backdrop with a thin progress ring
            // around it. Communicates "Mira is looking" without the cold
            // techy feel of a bare spinner.
            Box(
                modifier = Modifier.size(180.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(180.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant
                )
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.mira_mark),
                        contentDescription = null,
                        modifier = Modifier.size(96.dp)
                    )
                }
            }
            Spacer(Modifier.height(40.dp))
            Text(
                stepLabel,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.processing_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
