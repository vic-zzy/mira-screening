package com.mira.screening.data

import android.graphics.Bitmap
import com.mira.screening.inference.ViaResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// In-memory captures keyed by UUID, used to pass bitmaps between nav destinations
// without serializing them. Persisted Room storage lands in the next chunk.
object CaptureStore {
    private val captures = ConcurrentHashMap<String, Bitmap>()
    private val results = ConcurrentHashMap<String, ViaResult>()

    @Volatile
    private var pendingPatientId: String? = null

    fun put(bitmap: Bitmap): String {
        val id = UUID.randomUUID().toString()
        captures[id] = bitmap
        return id
    }

    fun get(id: String): Bitmap? = captures[id]

    fun putResult(id: String, result: ViaResult) {
        results[id] = result
    }

    fun getResult(id: String): ViaResult? = results[id]

    fun clear(id: String) {
        captures.remove(id)
        results.remove(id)
    }

    // Patient ID is captured on PreCapture before a captureId exists; we hold it
    // here as a single pending slot and consume it when ProcessingScreen saves.
    fun setPendingPatientId(id: String?) {
        pendingPatientId = id
    }

    fun consumePendingPatientId(): String? {
        val id = pendingPatientId
        pendingPatientId = null
        return id
    }
}
