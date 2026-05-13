package com.mira.screening

import android.app.Application
import com.mira.screening.data.Prefs
import com.mira.screening.inference.ViaModel

class MiraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Try to load the trained TFLite model from assets. If not present,
        // ViaModel silently falls back to the stub classifier.
        ViaModel.init(this)

        // Reset the cervix-validity gate to ON at every app launch. Even in
        // debug builds where the toggle exists, this guarantees a developer
        // can't accidentally leave the gate off across sessions, flip it off,
        // test, restart the app, it's back on. In release builds the setter
        // is a no-op (the gate is always on regardless), so this line is
        // effectively for debug-build safety only but harmless in release.
        Prefs(this).validityGateEnabled = true
    }
}
