package com.mira.screening.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mira.screening.R
import com.mira.screening.data.CaptureStore
import kotlinx.coroutines.delay

private const val ACETIC_WAIT_SECONDS = 60

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreCaptureScreen(
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    var patientId by remember { mutableStateOf("") }
    var speculumDone by remember { mutableStateOf(false) }
    var aceticDone by remember { mutableStateOf(false) }
    var timerStarted by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf(ACETIC_WAIT_SECONDS) }

    LaunchedEffect(timerStarted) {
        if (timerStarted) {
            while (secondsLeft > 0) {
                delay(1000)
                secondsLeft -= 1
            }
        }
    }

    val timerDone = timerStarted && secondsLeft == 0
    val canContinue = speculumDone && aceticDone && timerDone

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    stringResource(R.string.precapture_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )

                PatientIdField(
                    value = patientId,
                    onValueChange = { patientId = it }
                )

                StepCard(
                    stepNumber = 1,
                    icon = Icons.Outlined.MedicalServices,
                    title = stringResource(R.string.precapture_step_speculum),
                    done = speculumDone,
                    onClick = { speculumDone = !speculumDone }
                )
                StepCard(
                    stepNumber = 2,
                    icon = Icons.Outlined.WaterDrop,
                    title = stringResource(R.string.precapture_step_acetic),
                    done = aceticDone,
                    onClick = {
                        aceticDone = !aceticDone
                        if (aceticDone && !timerStarted) timerStarted = true
                    }
                )

                TimerHintCard(
                    started = timerStarted,
                    secondsLeft = secondsLeft,
                    done = timerDone
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Button(
                    onClick = {
                        CaptureStore.setPendingPatientId(patientId.trim().ifBlank { null })
                        onContinue()
                    },
                    enabled = canContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(
                        if (canContinue) stringResource(R.string.precapture_continue)
                        else stringResource(R.string.precapture_continue_disabled),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatientIdField(value: String, onValueChange: (String) -> Unit) {
    Column {
        Text(
            stringResource(R.string.precapture_patient_id_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(stringResource(R.string.precapture_patient_id_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Step cards have two visual states. Idle: surface-variant fill with subtle
 * border. Done: full primary-teal fill with white content, strong visual
 * confirmation that the step is complete. Both states preserve the same
 * geometry (uniform layout) for visual consistency, but the strong fill is
 * what reads as "selected" for the busy CHW.
 */
@Composable
private fun StepCard(
    stepNumber: Int,
    icon: ImageVector,
    title: String,
    done: Boolean,
    onClick: () -> Unit
) {
    val cardBg = if (done) MaterialTheme.colorScheme.primary
                 else MaterialTheme.colorScheme.surfaceVariant
    val iconBg = if (done) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)
                 else MaterialTheme.colorScheme.surface
    val titleColor = if (done) MaterialTheme.colorScheme.onPrimary
                     else MaterialTheme.colorScheme.onBackground
    val labelColor = if (done) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                     else MaterialTheme.colorScheme.onSurfaceVariant
    val iconTint = if (done) MaterialTheme.colorScheme.onPrimary
                   else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cardBg)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (done) Icons.Filled.Check else icon,
                contentDescription = null,
                tint = iconTint
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "STEP $stepNumber",
                style = MaterialTheme.typography.labelMedium,
                color = labelColor
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = titleColor
            )
        }
    }
}

/**
 * Inline non-interactive hint. Deliberately not styled as a card, the step
 * cards above it ARE tappable, and we don't want users mistaking this hint
 * for another step they need to tap. Always shows context (why we wait,
 * not just the seconds remaining).
 */
@Composable
private fun TimerHintCard(
    started: Boolean,
    secondsLeft: Int,
    done: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (done) Icons.Filled.Check else Icons.Outlined.HourglassEmpty,
            contentDescription = null,
            tint = if (done) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when {
                    done -> stringResource(R.string.precapture_timer_done)
                    started -> stringResource(R.string.precapture_timer_running)
                    else -> stringResource(R.string.precapture_timer_idle)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (started && !done) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { 1f - (secondsLeft.toFloat() / ACETIC_WAIT_SECONDS) },
                        modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.precapture_seconds_left, secondsLeft),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
    }
}
