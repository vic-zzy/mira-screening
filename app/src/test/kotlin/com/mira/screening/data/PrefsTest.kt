package com.mira.screening.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrefsTest {

    private lateinit var context: Context
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Reset prefs between tests
        context.getSharedPreferences("mira", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
    }

    @Test
    fun defaultValues_areSensible() {
        assertThat(prefs.onboardingComplete).isFalse()
        assertThat(prefs.saveImages).isTrue()
        assertThat(prefs.language).isEqualTo("en")
        assertThat(prefs.confidenceThreshold).isEqualTo(0.65f)
    }

    @Test
    fun setValues_persistAcrossInstances() {
        prefs.onboardingComplete = true
        prefs.saveImages = false
        prefs.language = "es"
        prefs.confidenceThreshold = 0.75f

        val again = Prefs(context)
        assertThat(again.onboardingComplete).isTrue()
        assertThat(again.saveImages).isFalse()
        assertThat(again.language).isEqualTo("es")
        assertThat(again.confidenceThreshold).isEqualTo(0.75f)
    }
}
