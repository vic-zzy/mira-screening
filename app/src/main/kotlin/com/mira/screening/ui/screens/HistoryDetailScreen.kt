package com.mira.screening.ui.screens

import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mira.screening.R
import com.mira.screening.data.ScreeningRecord
import com.mira.screening.data.ScreeningRepository
import com.mira.screening.inference.ViaClassification
import com.mira.screening.ui.components.MiraExplainsCard
import com.mira.screening.ui.theme.miraStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HistoryDetailScreen(
    recordId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { ScreeningRepository(context) }
    // Scope used to launch suspend repo.delete from the confirm-dialog onClick.
    val coroutineScope = rememberCoroutineScope()
    var record by remember { mutableStateOf<ScreeningRecord?>(null) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var showConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(recordId) {
        val r = repo.list().firstOrNull { it.id == recordId }
        record = r
        // BitmapFactory.decodeFile hits the disk, do it off Main.
        bitmap = r?.imagePath?.let { path ->
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
        }
    }

    // TTS plumbing for the "Play" button inside the saved Mira-explains card.
    // Mirrors ResultScreen's setup: prefer Google's higher-quality engine
    // when installed, fall back to system default. ttsConfigured gates the
    // button so the first click does not race against voice selection. Voice
    // is locked to the saved narrationLanguage if we can map it back to a
    // locale, so a Spanish narration plays through a Spanish voice even if
    // the device has since been switched to English.
    var ttsReady by remember { mutableStateOf(false) }
    var ttsConfigured by remember { mutableStateOf(false) }
    var speakingUtteranceId by remember { mutableStateOf<String?>(null) }
    val preferredTtsEngine = remember {
        try {
            context.packageManager.getPackageInfo("com.google.android.tts", 0)
            "com.google.android.tts"
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
    val tts = remember {
        TextToSpeech(
            context,
            { status -> ttsReady = status == TextToSpeech.SUCCESS },
            preferredTtsEngine
        )
    }
    DisposableEffect(tts) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                speakingUtteranceId = utteranceId
            }
            override fun onDone(utteranceId: String?) {
                speakingUtteranceId = null
            }
            @Deprecated("Deprecated in API 21")
            override fun onError(utteranceId: String?) {
                speakingUtteranceId = null
            }
        })
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }
    LaunchedEffect(ttsReady, record?.narrationLanguage) {
        if (!ttsReady) return@LaunchedEffect
        // Prefer the locale the narration was originally generated in, so a
        // Spanish text gets read by a Spanish voice even when the device is
        // currently English. Fall back to device locale if we have no saved
        // language or cannot map it.
        val targetLocale = record?.narrationLanguage
            ?.let { languageNameToLocale(it) }
            ?: Locale.getDefault()
        tts.language = targetLocale
        // tts.voices enumeration is slow (lazy index of installed voice
        // packs) and was running on Main. Move it to Default; hop back to
        // Main only to apply the chosen voice. See ResultScreen for context.
        val best = withContext(Dispatchers.Default) {
            tts.voices?.filter { v ->
                v.locale.language == targetLocale.language && !v.isNetworkConnectionRequired
            }?.maxByOrNull { it.quality }
        }
        if (best != null) {
            tts.voice = best
        }
        // Pre-warm the synthesizer (silent utterance, volume 0) so the
        // first Play click on the history record lands on a primed engine
        // and starts speaking immediately. See the longer comment in
        // ResultScreen for the rationale.
        val warmupParams = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f)
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "tts-prewarm")
        }
        tts.speak(" ", TextToSpeech.QUEUE_ADD, warmupParams, "tts-prewarm")
        ttsConfigured = true
    }

    val r = record
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

            Text(
                text = stringResource(R.string.history_detail_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            if (r == null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.result_unavailable))
                }
                return@Scaffold
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    val bmp = bitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.history_no_image),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                DetailCard(record = r)

                // Replay the saved Mira narration if this record has one.
                // Records captured before the persistence feature shipped have
                // narration = null and the card is hidden entirely rather
                // than running a fresh generation, because re-generating in
                // History is expensive (10-30s on emulator) and the original
                // wording is what the CHW actually communicated to the
                // patient. We pass placeholder values for resultLabel /
                // confidence / heatmapFocus because the cached-narration
                // path short-circuits the generation prompt and never reads
                // those fields.
                val savedNarration = r.narration
                if (savedNarration != null) {
                    MiraExplainsCard(
                        resultLabel = "",
                        confidencePercent = (r.confidence * 100).toInt(),
                        heatmapFocus = "",
                        languageName = r.narrationLanguage ?: "English",
                        isSpeaking = speakingUtteranceId == "mira-history",
                        cachedNarration = savedNarration,
                        onPlayPressed = { textToSpeak ->
                            if (speakingUtteranceId == "mira-history") {
                                tts.stop()
                                speakingUtteranceId = null
                            } else if (ttsConfigured) {
                                val params = Bundle().apply {
                                    putString(
                                        TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,
                                        "mira-history"
                                    )
                                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                                    putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, 0.0f)
                                    putInt(
                                        TextToSpeech.Engine.KEY_PARAM_STREAM,
                                        AudioManager.STREAM_MUSIC
                                    )
                                }
                                tts.speak(
                                    textToSpeak,
                                    TextToSpeech.QUEUE_FLUSH,
                                    params,
                                    "mira-history"
                                )
                            }
                        }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                OutlinedButton(
                    onClick = { showConfirm = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(
                        stringResource(R.string.history_action_delete),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            shape = RoundedCornerShape(20.dp),
            title = { Text(stringResource(R.string.history_delete_confirm_title)) },
            text = { Text(stringResource(R.string.history_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        // repo.delete is suspend; launch it on the composition
                        // scope so the Main thread is not blocked on disk IO.
                        // Navigate away immediately, the delete completes in
                        // the background and the history list re-reads from
                        // disk on next open.
                        coroutineScope.launch { repo.delete(recordId) }
                        onDeleted()
                    }
                ) {
                    Text(
                        stringResource(R.string.history_delete_confirm_yes),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.history_delete_confirm_cancel))
                }
            }
        )
    }
}

@Composable
private fun DetailCard(record: ScreeningRecord) {
    val effective = record.effectiveClassification
    val s = MaterialTheme.miraStatus
    val (bg, dot) = when (effective) {
        ViaClassification.POSITIVE -> s.positiveBg to s.positiveDot
        ViaClassification.NEGATIVE -> s.negativeBg to s.negativeDot
        ViaClassification.INCONCLUSIVE -> s.inconclusiveBg to s.inconclusiveDot
    }
    val label = when (effective) {
        ViaClassification.POSITIVE -> stringResource(R.string.result_positive)
        ViaClassification.NEGATIVE -> stringResource(R.string.result_negative)
        ViaClassification.INCONCLUSIVE -> stringResource(R.string.history_label_inconclusive)
    }
    val action = when (effective) {
        ViaClassification.POSITIVE -> stringResource(R.string.result_action_refer)
        ViaClassification.NEGATIVE -> stringResource(R.string.result_action_followup)
        ViaClassification.INCONCLUSIVE -> stringResource(R.string.result_action_reimage)
    }
    val timeStr = remember(record.timestampMs) { formatDateTime(record.timestampMs) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(20.dp)
    ) {
        Text(
            text = timeStr,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        if (record.userOverride != null) {
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
        if (record.patientId != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.history_patient_id, record.patientId),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.result_confidence, (record.confidence * 100).toInt()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { record.confidence },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surface
        )
    }
}

private fun formatDateTime(ms: Long): String =
    SimpleDateFormat("MMM d, yyyy · hh:mm a", Locale.getDefault()).format(Date(ms))

/**
 * Inverse of ResultScreen's localeToLanguageName: turn an English language
 * label saved with a screening record (e.g. "Spanish", "Swahili") back into a
 * Locale we can hand to TextToSpeech.setLanguage. Returns null on unknown
 * inputs so the caller can fall back to the device locale rather than
 * misroute the TTS voice. The list mirrors the ten languages Mira ships in.
 */
private fun languageNameToLocale(name: String): Locale? = when (name.lowercase()) {
    "english" -> Locale.ENGLISH
    "spanish" -> Locale("es")
    "portuguese" -> Locale("pt")
    "french" -> Locale.FRENCH
    "swahili" -> Locale("sw")
    "hausa" -> Locale("ha")
    "yoruba" -> Locale("yo")
    "igbo" -> Locale("ig")
    "luganda" -> Locale("lg")
    "quechua" -> Locale("qu")
    else -> null
}
