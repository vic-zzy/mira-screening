package com.mira.screening.data

import com.mira.screening.inference.ViaClassification

data class ScreeningRecord(
    val id: String,
    val timestampMs: Long,
    val classification: ViaClassification,
    val confidence: Float,
    val patientId: String?,
    val imagePath: String?,
    val notes: String?,
    // Set when the nurse explicitly overrides Mira's classification ("I disagree").
    // The model output (classification + confidence) is preserved so we can audit
    // the model vs human-override agreement rate for the prize-submission report.
    val userOverride: ViaClassification? = null,
    // Mira's generated patient-facing explanation for this screening, saved at the
    // moment the result-screen narration finishes streaming. Persisted alongside
    // the rest of the record so History can re-display the original explanation
    // without paying the 10-30s on-device generation cost a second time, and so
    // the wording the CHW originally read to the patient is preserved exactly.
    val narration: String? = null,
    // The English display name of the language Mira used for [narration]
    // (e.g. "English", "Spanish", "Swahili"). Captured at generation time so
    // History can show the right language label even if the device locale has
    // changed since.
    val narrationLanguage: String? = null
) {
    val effectiveClassification: ViaClassification get() = userOverride ?: classification
}
