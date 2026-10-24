package com.nanote.engine

import com.google.common.truth.Truth.assertThat
import com.nanote.ink.engine.PenResponse
import com.nanote.ink.engine.PRESSURE_LUT
import org.junit.Test

/**
 * v1.3: pen latency presets (user request: maximum stylus sensitivity with
 * almost zero delay) and the more sensitive pressure curve.
 */
class PenResponseTest {

    @Test
    fun `ULTRA is raw input with no filter and the fastest width follow`() {
        assertThat(PenResponse.ULTRA.raw).isTrue()
        assertThat(PenResponse.ULTRA.newFilter()).isNull()
        // width must react fastest of all presets
        assertThat(PenResponse.ULTRA.widthFollow)
            .isAtLeast(PenResponse.HIGH.widthFollow)
        assertThat(PenResponse.HIGH.widthFollow)
            .isAtLeast(PenResponse.SMOOTH.widthFollow)
    }

    @Test
    fun `non-raw presets build a fresh filter per axis per stroke`() {
        val a = PenResponse.SMOOTH.newFilter()
        val b = PenResponse.SMOOTH.newFilter()
        assertThat(a).isNotSameInstanceAs(b)

        // a filtered sample stays between raw and the previous estimate:
        // the filter must never overshoot (no added "energy")
        val f = a!!
        f.apply(0f, 0f)
        for (i in 1 until 50) {
            val out = f.apply(100f, i / 120f)
            assertThat(out).isAtLeast(0f)
            assertThat(out).isAtMost(100f)
        }
        // after half a second of constant input the filter converges
        assertThat(f.apply(100f, 0.5f)).isWithin(1f).of(100f)
    }

    @Test
    fun `HIGH smooths less than SMOOTH`() {
        // same step input (0 -> 100) at stylus rate: HIGH must track closer to raw
        val high = PenResponse.HIGH.newFilter()!!
        val smooth = PenResponse.SMOOTH.newFilter()!!
        high.apply(0f, 0f)
        smooth.apply(0f, 0f)
        var highOut = 0f
        var smoothOut = 0f
        // 3 samples into the step: HIGH is clearly closer to the raw value
        for (i in 1 until 4) {
            val t = i / 120f
            highOut = high.apply(100f, t)
            smoothOut = smooth.apply(100f, t)
        }
        assertThat(highOut).isGreaterThan(smoothOut)
    }

    @Test
    fun `pressure curve stays a monotone gamma with sensitive light touches`() {
        assertThat(PRESSURE_LUT[0]).isEqualTo(0f)
        assertThat(PRESSURE_LUT[255]).isEqualTo(1f)
        for (i in 1 until PRESSURE_LUT.size) {
            assertThat(PRESSURE_LUT[i]).isAtLeast(PRESSURE_LUT[i - 1])
        }
        // v1.3 sensitivity: a light touch (25% pressure) now maps to more
        // than a third of full width — light strokes register immediately
        assertThat(PRESSURE_LUT[64]).isGreaterThan(0.33f)
    }
}
