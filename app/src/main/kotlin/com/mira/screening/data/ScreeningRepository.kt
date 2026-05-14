package com.mira.screening.data

import android.content.Context
import android.graphics.Bitmap
import com.mira.screening.inference.ViaClassification
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

// File-backed persistence for V1: JSON manifest + JPEG-on-disk for images.
// Room migration lands when we need indexed queries / reactive flows.
class ScreeningRepository(private val context: Context) {
    private val recordsFile: File get() = File(context.filesDir, "records.json")
    private val imagesDir: File
        get() = File(context.filesDir, "images").also { it.mkdirs() }

    fun save(
        record: ScreeningRecord,
        bitmap: Bitmap?,
        persistImage: Boolean
    ): ScreeningRecord {
        val imagePath = if (persistImage && bitmap != null) {
            val file = File(imagesDir, "${record.id}.jpg")
            FileOutputStream(file).use { os ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, os)
            }
            file.absolutePath
        } else null

        val updated = record.copy(imagePath = imagePath)
        val current = list().toMutableList()
        current.removeAll { it.id == updated.id }
        current += updated
        write(current)
        return updated
    }

    fun list(): List<ScreeningRecord> {
        if (!recordsFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(recordsFile.readText())
            (0 until arr.length()).map { i -> arr.getJSONObject(i).toRecord() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun delete(id: String) {
        val all = list()
        all.firstOrNull { it.id == id }?.imagePath?.let {
            runCatching { File(it).delete() }
        }
        write(all.filterNot { it.id == id })
    }

    /**
     * Wipe all records and any saved images. Used by the "Clear all" action in
     * History, destructive, gated behind a confirm dialog at the call site.
     */
    fun deleteAll() {
        list().forEach { rec ->
            rec.imagePath?.let { runCatching { File(it).delete() } }
        }
        write(emptyList())
    }

    private fun write(list: List<ScreeningRecord>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        recordsFile.writeText(arr.toString())
    }

    private fun ScreeningRecord.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("timestampMs", timestampMs)
        put("classification", classification.name)
        put("confidence", confidence.toDouble())
        put("patientId", patientId ?: JSONObject.NULL)
        put("imagePath", imagePath ?: JSONObject.NULL)
        put("notes", notes ?: JSONObject.NULL)
        put("userOverride", userOverride?.name ?: JSONObject.NULL)
        put("narration", narration ?: JSONObject.NULL)
        put("narrationLanguage", narrationLanguage ?: JSONObject.NULL)
    }

    private fun JSONObject.toRecord(): ScreeningRecord = ScreeningRecord(
        id = getString("id"),
        timestampMs = getLong("timestampMs"),
        classification = ViaClassification.valueOf(getString("classification")),
        confidence = getDouble("confidence").toFloat(),
        patientId = if (isNull("patientId")) null else optString("patientId").ifBlank { null },
        imagePath = if (isNull("imagePath")) null else optString("imagePath").ifBlank { null },
        notes = if (isNull("notes")) null else optString("notes").ifBlank { null },
        userOverride = if (!has("userOverride") || isNull("userOverride")) null
        else ViaClassification.valueOf(getString("userOverride")),
        // Read narration fields defensively: records written by older builds
        // without these keys still decode cleanly with narration = null and
        // narrationLanguage = null, in which case the History detail view
        // simply hides the Mira-explains card for that record.
        narration = if (!has("narration") || isNull("narration")) null
        else optString("narration").ifBlank { null },
        narrationLanguage = if (!has("narrationLanguage") || isNull("narrationLanguage")) null
        else optString("narrationLanguage").ifBlank { null }
    )
}
