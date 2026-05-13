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
    val userOverride: ViaClassification? = null
) {
    val effectiveClassification: ViaClassification get() = userOverride ?: classification
}
