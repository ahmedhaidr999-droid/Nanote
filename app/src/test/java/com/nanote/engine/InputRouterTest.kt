package com.nanote.engine

import com.google.common.truth.Truth.assertThat
import com.nanote.ink.engine.Buttons
import com.nanote.ink.engine.InputRouter
import com.nanote.ink.engine.PointerSpec
import com.nanote.ink.engine.Route
import com.nanote.ink.engine.ToolTypes
import org.junit.Test

class InputRouterTest {

    private fun pointer(
        tool: Int,
        buttons: Int = 0,
        touchMajor: Float = 10f,
        time: Long = 10_000L,
        pointers: Int = 1
    ) = PointerSpec(tool, buttons, touchMajor, time, pointers)

    @Test
    fun `stylus draws, barrel button erases`() {
        val r = InputRouter(fingersDraw = { false })
        assertThat(r.route(pointer(ToolTypes.STYLUS))).isEqualTo(Route.DRAW)
        assertThat(r.route(pointer(ToolTypes.STYLUS, buttons = Buttons.STYLUS_PRIMARY))).isEqualTo(Route.ERASE)
    }

    @Test
    fun `eraser tool erases`() {
        val r = InputRouter(fingersDraw = { false })
        assertThat(r.route(pointer(ToolTypes.ERASER))).isEqualTo(Route.ERASE)
    }

    @Test
    fun `palm is always ignored`() {
        val r = InputRouter(fingersDraw = { true })
        assertThat(r.route(pointer(ToolTypes.PALM))).isEqualTo(Route.IGNORE)
        assertThat(r.route(pointer(ToolTypes.PALM, touchMajor = 90f))).isEqualTo(Route.IGNORE)
    }

    @Test
    fun `large contact patch is treated as palm`() {
        val r = InputRouter(fingersDraw = { true }, palmMajorPx = 30f)
        assertThat(r.route(pointer(ToolTypes.FINGER, touchMajor = 45f))).isEqualTo(Route.IGNORE)
        assertThat(r.route(pointer(ToolTypes.FINGER, touchMajor = 12f))).isEqualTo(Route.DRAW)
    }

    @Test
    fun `finger shortly after stylus is ignored (palm rejection)`() {
        val r = InputRouter(fingersDraw = { true })
        // stylus at t=10000
        r.route(pointer(ToolTypes.STYLUS, time = 10_000L))
        // palm lands at t=10200 while the pen lifts
        assertThat(r.route(pointer(ToolTypes.FINGER, time = 10_200L))).isEqualTo(Route.IGNORE)
        // ... but 500 ms later a deliberate finger draws again
        assertThat(r.route(pointer(ToolTypes.FINGER, time = 10_500L))).isEqualTo(Route.DRAW)
    }

    @Test
    fun `fingers draw toggle off routes finger to navigate`() {
        val r = InputRouter(fingersDraw = { false })
        assertThat(r.route(pointer(ToolTypes.FINGER))).isEqualTo(Route.NAVIGATE)
    }

    @Test
    fun `two finger touch navigates even when fingers draw`() {
        val r = InputRouter(fingersDraw = { true })
        assertThat(r.route(pointer(ToolTypes.FINGER, pointers = 2))).isEqualTo(Route.NAVIGATE)
    }

    @Test
    fun `hovering stylus silences fingers`() {
        val r = InputRouter(fingersDraw = { true })
        r.onHoverEvent(true, 10_000L)
        assertThat(r.route(pointer(ToolTypes.FINGER, time = 11_000L))).isEqualTo(Route.IGNORE)
        r.onHoverEvent(false, 12_000L)
        // last stylus activity was 1000 ms ago -> beyond the 350 ms window
        assertThat(r.route(pointer(ToolTypes.FINGER, time = 13_000L))).isEqualTo(Route.DRAW)
    }

    @Test
    fun `stylus down silences fingers`() {
        val r = InputRouter(fingersDraw = { true })
        r.onStylusDownChanged(true, 10_000L)
        assertThat(r.route(pointer(ToolTypes.FINGER, time = 10_100L))).isEqualTo(Route.IGNORE)
        r.onStylusDownChanged(false, 10_400L)
        // still within the 350 ms window -> ignored until it lapses
        assertThat(r.route(pointer(ToolTypes.FINGER, time = 10_500L))).isEqualTo(Route.IGNORE)
        assertThat(r.route(pointer(ToolTypes.FINGER, time = 11_000L))).isEqualTo(Route.DRAW)
    }

    @Test
    fun `mouse navigates, primary draws`() {
        val r = InputRouter(fingersDraw = { true })
        assertThat(r.route(pointer(ToolTypes.MOUSE, buttons = Buttons.PRIMARY))).isEqualTo(Route.DRAW)
        assertThat(r.route(pointer(ToolTypes.MOUSE, buttons = Buttons.SECONDARY))).isEqualTo(Route.NAVIGATE)
        assertThat(r.route(pointer(ToolTypes.MOUSE, buttons = Buttons.TERTIARY))).isEqualTo(Route.NAVIGATE)

        val noMouseDraw = InputRouter(fingersDraw = { false })
        assertThat(noMouseDraw.route(pointer(ToolTypes.MOUSE, buttons = Buttons.PRIMARY))).isEqualTo(Route.NAVIGATE)
    }
}
