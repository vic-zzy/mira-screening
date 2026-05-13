package com.mira.screening.data

import android.content.Context
import com.mira.screening.BuildConfig

// Lightweight SharedPreferences wrapper. Room/datastore upgrade lands once we have
// reactive UI state plumbed through ViewModels.
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("mira", Context.MODE_PRIVATE)

    var onboardingComplete: Boolean
        get() = sp.getBoolean(KEY_ONBOARDING, false)
        set(value) { sp.edit().putBoolean(KEY_ONBOARDING, value).apply() }

    var saveImages: Boolean
        get() = sp.getBoolean(KEY_SAVE_IMAGES, true)
        set(value) { sp.edit().putBoolean(KEY_SAVE_IMAGES, value).apply() }

    // The validity gate is a clinical safety lever. In release builds it is
    // ALWAYS on, there is no UI to disable it and SharedPreferences are
    // ignored. In debug builds it can be toggled off for testing (e.g. to
    // verify saliency rendering on the emulator's synthetic scene), but the
    // toggle is session-only, MiraApp.onCreate resets it to ON every launch
    // so a developer can never accidentally leave it off across runs.
    var validityGateEnabled: Boolean
        get() = if (BuildConfig.DEBUG) sp.getBoolean(KEY_VALIDITY_GATE, true) else true
        set(value) {
            if (BuildConfig.DEBUG) sp.edit().putBoolean(KEY_VALIDITY_GATE, value).apply()
            // In release builds, the setter is a no-op. The gate cannot be
            // disabled regardless of how SharedPreferences is manipulated.
        }

    var language: String
        get() = sp.getString(KEY_LANGUAGE, "en") ?: "en"
        set(value) { sp.edit().putString(KEY_LANGUAGE, value).apply() }

    var confidenceThreshold: Float
        get() = sp.getFloat(KEY_THRESHOLD, 0.65f)
        set(value) { sp.edit().putFloat(KEY_THRESHOLD, value).apply() }

    companion object {
        private const val KEY_ONBOARDING = "onboarding_complete"
        private const val KEY_SAVE_IMAGES = "save_images"
        private const val KEY_VALIDITY_GATE = "validity_gate_enabled"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_THRESHOLD = "confidence_threshold"
    }
}
