package com.mira.screening.preprocessing

import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LaplacianVarianceTest {

    @Test
    fun uniformImage_hasLowVariance() {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        for (y in 0 until 8) for (x in 0 until 8) {
            bmp.setPixel(x, y, Color.argb(255, 128, 128, 128))
        }
        val variance = LaplacianVariance.compute(bmp)
        assertThat(variance).isLessThan(1f)
    }

    @Test
    fun checkerboardImage_hasHighVariance() {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        for (y in 0 until 8) for (x in 0 until 8) {
            val v = if ((x + y) % 2 == 0) 255 else 0
            bmp.setPixel(x, y, Color.argb(255, v, v, v))
        }
        val variance = LaplacianVariance.compute(bmp)
        assertThat(variance).isGreaterThan(1000f)
    }

    @Test
    fun smoothGradient_hasModerateVariance() {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        for (y in 0 until 16) for (x in 0 until 16) {
            val v = (x * 16).coerceAtMost(255)
            bmp.setPixel(x, y, Color.argb(255, v, v, v))
        }
        val variance = LaplacianVariance.compute(bmp)
        // A linear gradient has constant first derivative, so the second derivative
        // (Laplacian) is approximately zero, variance should still be small.
        assertThat(variance).isLessThan(50f)
    }

    @Test
    fun tooSmallImage_returnsZero() {
        val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        assertThat(LaplacianVariance.compute(bmp)).isEqualTo(0f)
    }
}
