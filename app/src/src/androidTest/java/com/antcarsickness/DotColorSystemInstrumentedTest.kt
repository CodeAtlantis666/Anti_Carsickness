package com.antcarsickness

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DotColorSystemInstrumentedTest {

    @Test
    fun blackWhiteContrast_shouldBeNear21() {
        val ratio = DotColorSystem.contrastRatio(Color.BLACK, Color.WHITE)
        assertTrue(ratio > 20.0f)
    }

    @Test
    fun chooseDotColor_shouldMeetContrastOnDarkBackground() {
        val bg = Color.rgb(10, 10, 10)
        val style = DotColorSystem.chooseDotColor(
            backgroundRgb = bg,
            state = DotVisualState.BRAKING,
            params = DotColorParams(minContrast = 4.5f)
        )
        assertTrue(style.contrastValue >= 4.5f || style.metContrast)
    }

    @Test
    fun chooseDotColor_highContrastMode_shouldPreferStrongStyle() {
        val bg = Color.rgb(220, 220, 220)
        val style = DotColorSystem.chooseDotColor(
            backgroundRgb = bg,
            state = DotVisualState.IDLE,
            params = DotColorParams(minContrast = 4.5f, highContrastMode = true)
        )
        assertTrue(style.contrastValue >= 7.0f || style.metContrast)
    }
}
