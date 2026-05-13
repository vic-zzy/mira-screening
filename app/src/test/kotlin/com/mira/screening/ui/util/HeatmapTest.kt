package com.mira.screening.ui.util

import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HeatmapTest {

    @Test
    fun viridisColor_atZero_isLowEnd() {
        val c = viridisColor(0f)
        assertThat(Color.red(c)).isEqualTo(68)
        assertThat(Color.green(c)).isEqualTo(1)
        assertThat(Color.blue(c)).isEqualTo(84)
    }

    @Test
    fun viridisColor_atOne_isHighEnd() {
        val c = viridisColor(1f)
        assertThat(Color.red(c)).isEqualTo(253)
        assertThat(Color.green(c)).isEqualTo(231)
        assertThat(Color.blue(c)).isEqualTo(37)
    }

    @Test
    fun viridisColor_clampsBelowZero() {
        assertThat(viridisColor(-0.5f)).isEqualTo(viridisColor(0f))
    }

    @Test
    fun viridisColor_clampsAboveOne() {
        assertThat(viridisColor(1.5f)).isEqualTo(viridisColor(1f))
    }

    @Test
    fun viridisColor_appliesAlpha() {
        val c = viridisColor(0.5f, alpha = 128)
        assertThat(Color.alpha(c)).isEqualTo(128)
    }

    @Test
    fun heatmapToBitmap_correctDimensions() {
        val w = 8
        val h = 6
        val data = FloatArray(w * h) { 0.5f }
        val bmp = heatmapToBitmap(data, w, h)
        assertThat(bmp.width).isEqualTo(w)
        assertThat(bmp.height).isEqualTo(h)
    }

    @Test
    fun heatmapToBitmap_belowThreshold_isTransparent() {
        val w = 4
        val h = 4
        val data = FloatArray(w * h) { 0.05f }
        val bmp = heatmapToBitmap(data, w, h, minActivation = 0.15f)
        for (y in 0 until h) for (x in 0 until w) {
            assertThat(bmp.getPixel(x, y)).isEqualTo(Color.TRANSPARENT)
        }
    }

    @Test
    fun heatmapToBitmap_aboveThreshold_isOpaqueAtRequestedAlpha() {
        val data = FloatArray(4) { 0.9f }
        val bmp = heatmapToBitmap(data, 2, 2, alpha = 200, minActivation = 0.1f)
        val alphas = (0..1).flatMap { y -> (0..1).map { x -> Color.alpha(bmp.getPixel(x, y)) } }
        assertThat(alphas.toSet()).containsExactly(200)
    }

    @Test(expected = IllegalArgumentException::class)
    fun heatmapToBitmap_dimensionMismatch_throws() {
        heatmapToBitmap(FloatArray(10), width = 4, height = 4)
    }
}
