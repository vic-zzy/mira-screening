package com.mira.screening.preprocessing

import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WhiteBalanceTest {

    @Test
    fun grayWorld_neutralImage_stillNeutral() {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        for (y in 0 until 4) for (x in 0 until 4) {
            bmp.setPixel(x, y, Color.argb(255, 128, 128, 128))
        }
        val balanced = WhiteBalance.grayWorld(bmp)
        for (y in 0 until 4) for (x in 0 until 4) {
            val p = balanced.getPixel(x, y)
            assertThat(Color.red(p)).isWithin(1).of(128)
            assertThat(Color.green(p)).isWithin(1).of(128)
            assertThat(Color.blue(p)).isWithin(1).of(128)
        }
    }

    @Test
    fun grayWorld_uniformColorCast_balancesToGray() {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        for (y in 0 until 4) for (x in 0 until 4) {
            bmp.setPixel(x, y, Color.argb(255, 200, 100, 100))
        }
        val balanced = WhiteBalance.grayWorld(bmp)
        // All pixels are identical, so after balance they all approach the channel mean.
        // Mean = (200 + 100 + 100) / 3 ~= 133.
        val p = balanced.getPixel(0, 0)
        assertThat(Color.red(p)).isWithin(2).of(133)
        assertThat(Color.green(p)).isWithin(2).of(133)
        assertThat(Color.blue(p)).isWithin(2).of(133)
    }

    @Test
    fun grayWorld_preservesAlpha() {
        val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        for (y in 0 until 2) for (x in 0 until 2) {
            bmp.setPixel(x, y, Color.argb(180, 100, 200, 50))
        }
        val balanced = WhiteBalance.grayWorld(bmp)
        for (y in 0 until 2) for (x in 0 until 2) {
            assertThat(Color.alpha(balanced.getPixel(x, y))).isEqualTo(180)
        }
    }
}
