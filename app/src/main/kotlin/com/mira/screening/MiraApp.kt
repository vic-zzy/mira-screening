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
        Prefs(this).validityGateEnabled = true

        // Kick off background initialization of Gemma 4 (LiteRT-LM). The first
        // launch extracts the .litertlm asset to internal storage (~30 to 60
        // seconds) and then loads the engine (~10 seconds on a mid-range
        // phone). Subsequent launches reuse the extracted file and only pay
        // the engine load cost. Screens that need Gemma wait via
        // GemmaInference.waitUntilReady or check GemmaInference.isReady.
        GemmaInference.startInitialization(this, applicationScope)
    }
}
