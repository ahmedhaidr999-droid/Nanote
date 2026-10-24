package com.nanote.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * v1.1: built-in stylus database detection. The user's Xiaomi Pad 6S Pro
 * (24018RPACG) maps to the Xiaomi Focus Pen; detection must stay
 * conservative so phones and pen-less tablets are never mis-detected.
 */
class StylusRegistryTest {

    @Test
    fun `detects Xiaomi Pad 6S Pro as the Focus Pen`() {
        val pen = StylusRegistry.detect("Xiaomi", "24018RPACG", "manet")
        assertThat(pen).isNotNull()
        assertThat(pen!!.id).isEqualTo("xiaomi_focus_pen")
        assertThat(pen.pressureLevels).isEqualTo(8192)
        assertThat(pen.buttons).isEqualTo(3)
        assertThat(pen.palmRejection).isTrue()
    }

    @Test
    fun `detects Samsung Galaxy Tab S as the S Pen`() {
        val pen = StylusRegistry.detect("samsung", "SM-X910", "p6s")
        assertThat(pen).isNotNull()
        assertThat(pen!!.id).isEqualTo("samsung_s_pen")
        assertThat(pen.buttons).isEqualTo(1)
    }

    @Test
    fun `detects Huawei MatePad as an M-Pencil`() {
        val pen = StylusRegistry.detect("HUAWEI", "HUAWEIMatePad 11.5", "GOA")
        assertThat(pen).isNotNull()
        assertThat(pen!!.brand).isEqualTo("Huawei")
    }

    @Test
    fun `detects Chromebooks as USI 2.0`() {
        val pen = StylusRegistry.detect("acer", "Acer Chromebook Spin 511", "vorticon")
        assertThat(pen).isNotNull()
        assertThat(pen!!.id).isEqualTo("usi_2")
    }

    @Test
    fun `unknown phones are never mis-detected`() {
        // random Redmi/Xiaomi phone models must not trigger Focus Pen defaults
        assertThat(StylusRegistry.detect("Xiaomi", "2312DRA50G", "")).isNull()
        assertThat(StylusRegistry.detect("Xiaomi", "23049PD8C", "")).isNull()
        assertThat(StylusRegistry.detect("Sony", "XQ-CT72", "")).isNull()
    }

    @Test
    fun `all registry entries have sane capabilities`() {
        assertThat(StylusRegistry.all.size).isAtLeast(10)
        StylusRegistry.all.forEach { p ->
            assertThat(p.pressureLevels).isAtLeast(1000)
            assertThat(p.buttons).isAtLeast(1)
            assertThat(StylusRegistry.byId(p.id)).isEqualTo(p)
        }
    }
}
