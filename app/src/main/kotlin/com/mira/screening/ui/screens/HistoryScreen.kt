package com.mira.screening.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mira.screening.R
import com.mira.screening.data.ScreeningRecord
import com.mira.screening.data.ScreeningRepository
import com.mira.screening.inference.ViaClassification
import com.mira.screening.ui.theme.miraStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenRecord: (recordId: String) -> Unit = {}
) {
    val context = LocalContext.current
    val repo = remember { ScreeningRepository(context) }
    // Used to launch suspend repo.deleteAll from the clear-all dialog onClick.
    val coroutineScope = rememberCoroutineScope()
    var records by remember { mutableStateOf(emptyList<ScreeningRecord>()) }
    var showClearAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        records = repo.list().sortedByDescending { it.timestampMs }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Header row: back left, "Clear all" trash icon right (only when
            // the list has any records to clear).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back)
                    )
                }
                Spacer(Modifier.weight(1f))
                if (records.isNotEmpty()) {
                    IconButton(onClick = { showClearAll = true }) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.history_action_clear_all),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.padding(horizontal = 24.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = stringResource(R.string.history_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (records.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "(${records.size})",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            if (records.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                RecordList(records = records, onOpenRecord = onOpenRecord)
            }
        }
    }

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            title = { Text(stringResource(R.string.history_clear_all_confirm_title)) },
            text = { Text(stringResource(R.string.history_clear_all_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearAll = false
                        // Optimistically clear the UI list; the actual disk
                        // wipe (suspend, runs on Dispatchers.IO inside the
                        // repository) completes in the background.
                        records = emptyList()
                        coroutineScope.launch { repo.deleteAll() }
                    }
                ) {
                    Text(
                        stringResource(R.string.history_clear_all_confirm_yes),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAll = false }) {
                    Text(stringResource(R.string.history_delete_confirm_cancel))
                }
            }
        )
    }
}

@Composable
private fun RecordList(
    records: List<ScreeningRecord>,
    onOpenRecord: (String) -> Unit
) {
    val groups = remember(records) { groupByDay(records) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        groups.forEach { (label, items) ->
            item(key = "header-$label") {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp
                    )
                )
            }
            items(items, key = { it.id }) { record ->
                RecordRow(record = record, onClick = { onOpenRecord(record.id) })
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun RecordRow(record: ScreeningRecord, onClick: () -> Unit) {
    val (bg, dot) = statusColors(record.effectiveClassification)
    val label = labelFor(record.effectiveClassification)
    val timeStr = remember(record.timestampMs) { formatTime(record.timestampMs) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusSquircle(bg = bg, dot = dot)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (record.userOverride != null) {
                    Spacer(Modifier.width(8.dp))
                    OverrideTag()
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildMeta(timeStr, record.confidence, record.patientId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun StatusSquircle(bg: Color, dot: Color) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dot)
        )
    }
}

@Composable
private fun OverrideTag() {
    // Small chip with a pen icon, signaling a manual override is attached.
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Edit,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp)
        )
    }
}

@Composable
private fun statusColors(c: ViaClassification): Pair<Color, Color> {
    val s = MaterialTheme.miraStatus
    return when (c) {
        ViaClassification.POSITIVE -> s.positiveBg to s.positiveDot
        ViaClassification.NEGATIVE -> s.negativeBg to s.negativeDot
        ViaClassification.INCONCLUSIVE -> s.inconclusiveBg to s.inconclusiveDot
    }
}

@Composable
private fun labelFor(c: ViaClassification): String = when (c) {
    ViaClassification.POSITIVE -> stringResource(R.string.result_positive)
    ViaClassification.NEGATIVE -> stringResource(R.string.result_negative)
    ViaClassification.INCONCLUSIVE -> stringResource(R.string.history_label_inconclusive)
}

private fun buildMeta(time: String, confidence: Float, patientId: String?): String {
    val pct = (confidence * 100).toInt()
    val base = "$time  ·  $pct%"
    return if (patientId != null) "$base  ·  $patientId" else base
}

/**
 * Group records into "Today", "Yesterday", and date-labeled buckets like "Apr 25".
 * Preserves the input order within each group.
 */
private fun groupByDay(records: List<ScreeningRecord>): List<Pair<String, List<ScreeningRecord>>> {
    val now = Calendar.getInstance()
    val today = stripTime(now.clone() as Calendar)
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -1) }
    val labelFmt = SimpleDateFormat("MMM d", Locale.getDefault())
    val fullFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    return records
        .groupBy { record ->
            val cal = Calendar.getInstance().apply { timeInMillis = record.timestampMs }
            val day = stripTime(cal)
            when {
                day.timeInMillis == today.timeInMillis -> "TODAY"
                day.timeInMillis == yesterday.timeInMillis -> "YESTERDAY"
                day.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
                    labelFmt.format(Date(record.timestampMs)).uppercase()
                else ->
                    fullFmt.format(Date(record.timestampMs)).uppercase()
            }
        }
        .toList()
}

private fun stripTime(cal: Calendar): Calendar {
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(ms))
