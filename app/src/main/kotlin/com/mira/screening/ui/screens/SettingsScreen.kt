package com.mira.screening.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.mira.screening.BuildConfig
import com.mira.screening.R
import com.mira.screening.data.Prefs

private const val APP_VERSION = "0.1.0-alpha"

private data class LanguageOption(val tag: String?, val labelRes: Int)

private val LANGUAGE_OPTIONS = listOf(
    LanguageOption(null, R.string.settings_language_system),
    LanguageOption("en", R.string.settings_language_en),
    LanguageOption("es", R.string.settings_language_es),
    LanguageOption("pt", R.string.settings_language_pt),
    LanguageOption("fr", R.string.settings_language_fr),
    LanguageOption("sw", R.string.settings_language_sw),
    LanguageOption("ha", R.string.settings_language_ha),
    LanguageOption("yo", R.string.settings_language_yo),
    LanguageOption("ig", R.string.settings_language_ig),
    LanguageOption("lg", R.string.settings_language_lg),
    LanguageOption("qu", R.string.settings_language_qu)
)

private val SUPPORTED_LANGUAGES: Set<String> = setOf(
    "en", "es", "pt", "fr", "sw", "ha", "yo", "ig", "lg", "qu"
)

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    ExperimentalFoundationApi::class
)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    var saveImages by remember { mutableStateOf(prefs.saveImages) }
    var validityGate by remember { mutableStateOf(prefs.validityGateEnabled) }
    var threshold by remember { mutableFloatStateOf(prefs.confidenceThreshold) }
    var currentLocaleTag by remember { mutableStateOf(currentAppLocaleTag()) }
    // The Developer section is invisible by default even in debug builds.
    // Long-press the version string in the About card to reveal it. Resets
    // every time Settings is opened. In release builds the section is gone
    // from the binary entirely (see BuildConfig.DEBUG gate below), no
    // gesture in production reveals anything.
    var devUnlocked by remember { mutableStateOf(false) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
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
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )

            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier.padding(
                    start = 24.dp,
                    end = 24.dp,
                    top = 16.dp,
                    // Generous bottom padding so the threshold slider isn't
                    // clipped by the system gesture bar on devices that don't
                    // honor windowInsetsPadding cleanly.
                    bottom = 48.dp
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                SectionCard(title = stringResource(R.string.settings_section_language)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LANGUAGE_OPTIONS.forEach { option ->
                            FilterChip(
                                selected = currentLocaleTag == option.tag,
                                onClick = {
                                    applyAppLocale(option.tag)
                                    currentLocaleTag = option.tag
                                    prefs.language = option.tag ?: "system"
                                },
                                shape = RoundedCornerShape(50),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                label = { Text(stringResource(option.labelRes)) }
                            )
                        }
                    }
                }

                SectionCard(title = stringResource(R.string.settings_section_privacy)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_save_images),
                        body = stringResource(R.string.settings_save_images_body),
                        checked = saveImages,
                        onCheckedChange = {
                            saveImages = it
                            prefs.saveImages = it
                        }
                    )
                }

                if (BuildConfig.DEBUG && devUnlocked) {
                    SectionCard(title = stringResource(R.string.settings_section_developer)) {
                        SwitchRow(
                            title = stringResource(R.string.settings_validity_gate),
                            body = stringResource(R.string.settings_validity_gate_body),
                            checked = validityGate,
                            onCheckedChange = {
                                validityGate = it
                                prefs.validityGateEnabled = it
                            }
                        )
                    }
                }

                SectionCard(title = stringResource(R.string.settings_section_threshold)) {
                    Text(
                        stringResource(R.string.settings_threshold_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.settings_threshold_value, (threshold * 100).toInt()),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Slider(
                        value = threshold,
                        onValueChange = { threshold = it },
                        onValueChangeFinished = { prefs.confidenceThreshold = threshold },
                        valueRange = 0.5f..0.9f,
                        steps = 7,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                }

                SectionCard(title = stringResource(R.string.settings_section_about)) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(4.dp))
                    // Long-press the version line in debug builds to toggle
                    // the Developer section. No-op in release builds (the
                    // section does not exist in the release binary at all).
                    Text(
                        stringResource(R.string.settings_version, APP_VERSION),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                if (BuildConfig.DEBUG) devUnlocked = !devUnlocked
                            }
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.settings_disclaimer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(20.dp)
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.surface,
                uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant
            )
        )
    }
}

private fun currentAppLocaleTag(): String? {
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return null
    val tag = locales.toLanguageTags().substringBefore(',')
    return tag.substringBefore('-').takeIf { it in SUPPORTED_LANGUAGES }
}

private fun applyAppLocale(tag: String?) {
    val locales = if (tag == null) LocaleListCompat.getEmptyLocaleList()
    else LocaleListCompat.forLanguageTags(tag)
    AppCompatDelegate.setApplicationLocales(locales)
}
