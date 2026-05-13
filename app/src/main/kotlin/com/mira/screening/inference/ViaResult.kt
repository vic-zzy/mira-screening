package com.mira.screening.inference

enum class ViaClassification {
    POSITIVE,      // Acetowhite detected, refer for treatment
    NEGATIVE,      // No acetowhite, follow up in 3–5 years
    INCONCLUSIVE   // Confidence below threshold, re-image
}

data class ViaResult(
    val classification: ViaClassification,
    val confidence: Float,
    val heatmap: FloatArray?,
    val heatmapWidth: Int = 0,
    val heatmapHeight: Int = 0,
    val processingTimeMs: Long = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ViaResult) return false
        return classification == other.classification &&
            confidence == other.confidence &&
            heatmap.contentEqualsOrNull(other.heatmap) &&
            heatmapWidth == other.heatmapWidth &&
            heatmapHeight == other.heatmapHeight &&
            processingTimeMs == other.processingTimeMs
    }

    override fun hashCode(): Int {
        var result = classification.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + (heatmap?.contentHashCode() ?: 0)
        result = 31 * result + heatmapWidth
        result = 31 * result + heatmapHeight
        result = 31 * result + processingTimeMs.hashCode()
        return result
    }
}

private fun FloatArray?.contentEqualsOrNull(other: FloatArray?): Boolean = when {
    this == null && other == null -> true
    this == null || other == null -> false
    else -> this.contentEquals(other)
}
