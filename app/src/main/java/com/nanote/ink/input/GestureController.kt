package com.nanote.ink.input

import kotlin.math.hypot

/**
 * Two-pointer pinch-zoom + pan tracking with zero per-event allocations:
 * the view reads [focusDx]/[focusDy]/[zoomFactor] after each [update].
 */
class GestureController {

    var isActive: Boolean = false
        private set

    var focusDx: Float = 0f
        private set
    var focusDy: Float = 0f
        private set
    var zoomFactor: Float = 1f
        private set

    /** Current centroid (used as the zoom focus). */
    var focusX: Float = 0f
        private set
    var focusY: Float = 0f
        private set

    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastSpan = 0f

    fun begin(x0: Float, y0: Float, x1: Float, y1: Float) {
        isActive = true
        lastFocusX = (x0 + x1) / 2f
        lastFocusY = (y0 + y1) / 2f
        focusX = lastFocusX
        focusY = lastFocusY
        lastSpan = if (x1 == x0 && y1 == y0) 1f else hypot(x1 - x0, y1 - y0)
        focusDx = 0f
        focusDy = 0f
        zoomFactor = 1f
    }

    fun update(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (!isActive) return
        val fx = (x0 + x1) / 2f
        val fy = (y0 + y1) / 2f
        val span = if (x1 == x0 && y1 == y0) lastSpan else hypot(x1 - x0, y1 - y0)
        focusDx = fx - lastFocusX
        focusDy = fy - lastFocusY
        zoomFactor = if (lastSpan > 1e-3f) span / lastSpan else 1f
        lastFocusX = fx
        lastFocusY = fy
        focusX = fx
        focusY = fy
        lastSpan = span
    }

    fun end() {
        isActive = false
    }
}
