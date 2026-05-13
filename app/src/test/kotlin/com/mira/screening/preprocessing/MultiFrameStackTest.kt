package com.mira.screening.preprocessing

import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MultiFrameStackTest {

    private fun bitmapOf(w: Int, h: Int, color: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) b.setPixel(x, y, color)
        return b
    }

    @Test
    fun average_singleFrame_returnsEquivalent() {
        val src = bitmapOf(2, 2, Color.argb(255, 100, 50, 200))
        val out = MultiFrameStack.average(listOf(src))
        for (y in 0 until 2) for (x in 0 until 2) {
            val p = out.getPixel(x, y)
            assertThat(Color.red(p)).isEqualTo(100)
            assertThat(Color.green(p)).isEqualTo(50)
            assertThat(Color.blue(p)).isEqualTo(200)
        }
    }

    @Test
    fun average_twoFrames_isPerChannelMean() {
        val a = bitmapOf(1, 1, Color.argb(255, 100, 100, 100))
        val b = bitmapOf(1, 1, Color.argb(255, 200, 200, 200))
        val out = MultiFrameStack.average(listOf(a, b))
        val p = out.getPixel(0, 0)
        assertThat(Color.red(p)).isEqualTo(150)
        assertThat(Color.green(p)).isEqualTo(150)
        assertThat(Color.blue(p)).isEqualTo(150)
    }

    @Test
    fun average_fiveFrames_reducesNoise() {
        // Five frames with values around 128 ± noise; average should be close to 128.
        val frames = listOf(120, 130, 125, 135, 128).map { bitmapOf(2, 2, Color.argb(255, it, it, it)) }
        val out = MultiFrameStack.average(frames)
        val p = out.getPixel(0, 0)
        assertThat(Color.red(p)).isWithin(1).of(127)
    }

    @Test(expected = IllegalArgumentException::class)
    fun average_emptyList_throws() {
        MultiFrameStack.average(emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun average_dimensionMismatch_throws() {
        val a = bitmapOf(2, 2, Color.WHITE)
        val b = bitmapOf(3, 3, Color.WHITE)
        MultiFrameStack.average(listOf(a, b))
    }
}
