package com.mira.screening

import android.app.Application
import com.mira.screening.data.Prefs
import com.mira.screening.gemma.GemmaInference
import com.mira.screening.inference.ViaModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class MiraApp : Application() {

    // Application-scoped coroutine scope for long-lived background work like
    // the Gemma model load. Survives across screen lifecycles and is cancelled
    // automatically when the process dies.
    private val applicationScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        // Try to load the trained TFLite cervical classifier from assets. If
        // not present, ViaModel silently falls back to the stub classifier.
        ViaModel.init(this)

        // Reset the cervix-validity gate to ON at every app launch. Even in
        // debug builds where the toggle exists, this guarantees a developer
        // can't accidentally leave the gate off across sessions, flip it off,
        // test, restart the app, it's back on. In release builds the setter
        // is a no-op (the gate is always on regardless), so this line is
        // effectively for debug-build safety only but harmless in release.
        // DEMO: cervix-validity gate temporarily disabled at launch so that
        // images shot through the front-facing webcam pass-through (cervix
        // photo displayed on a phone, captured by the Mac webcam) run through
        // the full classifier path and produce a heatmap. With the gate on,
        // those frames are rejected as non-cervix and the heatmap is null.
        // Restore production behaviour by changing `false` back to `true`.
        Prefs(this).validityGateEnabled = false

        // Kick off background initialization of Gemma 4 (LiteRT-LM). The first
        // launch extracts the .litertlm asset to internal storage (~30 to 60
        // seconds) and then loads the engine (~10 seconds on a mid-range
        // phone). Subsequent launches reuse the extracted file and only pay
        // the engine load cost. Screens that need Gemma wait via
        // GemmaInference.waitUntilReady or check GemmaInference.isReady.
        GemmaInference.startInitialization(this, applicationScope)
    }
}
