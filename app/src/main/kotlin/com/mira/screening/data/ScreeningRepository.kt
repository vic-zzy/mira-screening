package com.mira.screening.data

import android.content.Context
import android.graphics.Bitmap
import com.mira.screening.inference.ViaClassification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

// File-backed persistence for V1: JSON manifest + JPEG-on-disk for images.
// Room migration lands when we need indexed queries / reactive flows.
//
// All public IO entry points are suspend + withContext(Dispatchers.IO) so the
// composition / main thread never blocks on JSON readText/writeText or JPEG
// compression. Calling these from a non-coroutine context (button onClick) is
// a build error, which catches Main-thread regressions at compile time.
class ScreeningRepository(private val context: Context) {
    private val recordsFile: File get() = File(context.filesDir, "records.json")
    private val imagesDir: File
        get() = File(context.filesDir, "images").also { it.mkdirs() }

    suspend fun save(
        record: ScreeningRecord,
        bitmap: Bitmap?,
        persistImage: Boolean
    ): ScreeningRecord = withContext(Dispatchers.IO) {
        // Only write a new JPEG (and overwrite imagePath) when this call is
        // actually persisting a fresh image. Update-style calls from
        // ResultScreen (narration save, user override) pass persistImage=false
        // with bitmap=null, in which case we MUST preserve the imagePath that
        // was set on the original insert, otherwise the History detail view
        // loses its image because record.imagePath gets nulled out on every
        // subsequent update.
        val updated = if (persistImage && bitmap != null) {
            val file = File(imagesDir, "${record.id}.jpg")
            FileOutputStream(file).use { os ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, os)
            }
            record.copy(imagePath = file.absolutePath)
        } else {
            // Preserve whatever imagePath the caller already had on the record.
            // If they passed null deliberately (e.g. saveImages pref was off
            // at insert time), that null is what gets persisted.
            record
        }

        val current = listBlocking().toMutableList()
        current.removeAll { it.id == updated.id }
        current += updated
        write(current)
        updated
    }

    suspend fun list(): List<ScreeningRecord> = withContext(Dispatchers.IO) {
        listBlocking()
    }

    // Used internally by save/delete/deleteAll, which are already inside a
    // withContext(IO) block. Extracted so we don't pay the dispatcher dance
    // twice for the read-then-write sequence those methods perform.
    private fun listBlocking(): List<ScreeningRecord> {
        if (!recordsFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(recordsFile.readText())
            (0 until arr.length()).map { i -> arr.getJSONObject(i).toRecord() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val all = listBlocking()
        all.firstOrNull { it.id == id }?.imagePath?.let {
            runCatching { File(it).delete() }
        }
        write(all.filterNot { it.id == id })
    }

    /**
     * Wipe all records and any saved images. Used by the "Clear all" action in
     * History, destructive, gated behind a confirm dialog at the call site.
     */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        listBlocking().forEach { rec ->
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
