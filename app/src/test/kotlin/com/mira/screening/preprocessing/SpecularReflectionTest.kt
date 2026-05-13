package com.mira.screening.preprocessing

import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpecularReflectionTest {

    @Test
    fun mask_pureWhite_isSpecular() {
        val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        for (y in 0 until 2) for (x in 0 until 2) {
            bmp.setPixel(x, y, Color.argb(255, 255, 255, 255))
        }
        val mask = SpecularReflection.mask(bmp)
        assertThat(mask.toList()).containsExactly(1, 1, 1, 1)
    }

    @Test
    fun mask_saturatedRed_isNotSpecular() {
        val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        for (y in 0 until 2) for (x in 0 until 2) {
            bmp.setPixel(x, y, Color.argb(255, 200, 50, 50))
        }
        val mask = SpecularReflection.mask(bmp)
        assertThat(mask.toList()).containsExactly(0, 0, 0, 0)
    }

    @Test
    fun mask_darkGray_isNotSpecular() {
        val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        for (y in 0 until 2) for (x in 0 until 2) {
            bmp.setPixel(x, y, Color.argb(255, 60, 60, 60))
        }
        val mask = SpecularReflection.mask(bmp)
        assertThat(mask.toList()).containsExactly(0, 0, 0, 0)
    }

    @Test
    fun suppress_replacesGlareWithMeanOfNonGlare() {
        val bmp = Bitmap.createBitmap(4, 1, Bitmap.Config.ARGB_8888)
        bmp.setPixel(0, 0, Color.argb(255, 100, 100, 100))
        bmp.setPixel(1, 0, Color.argb(255, 100, 100, 100))
        bmp.setPixel(2, 0, Color.argb(255, 100, 100, 100))
        bmp.setPixel(3, 0, Color.argb(255, 255, 255, 255))

        val out = SpecularReflection.suppress(bmp)
        val replaced = out.getPixel(3, 0)
        assertThat(Color.red(replaced)).isEqualTo(100)
        assertThat(Color.green(replaced)).isEqualTo(100)
        assertThat(Color.blue(replaced)).isEqualTo(100)
        // Non-specular pixels untouched.
        val first = out.getPixel(0, 0)
        assertThat(Color.red(first)).isEqualTo(100)
    }

    @Test
    fun suppress_allSpecular_returnsBitmapUnchanged() {
        val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        for (y in 0 until 2) for (x in 0 until 2) {
            bmp.setPixel(x, y, Color.argb(255, 255, 255, 255))
        }
        val out = SpecularReflection.suppress(bmp)
        // No non-specular pixels to compute mean from; suppress returns input unchanged.
        for (y in 0 until 2) for (x in 0 until 2) {
            assertThat(out.getPixel(x, y)).isEqualTo(Color.argb(255, 255, 255, 255))
        }
    }
}
