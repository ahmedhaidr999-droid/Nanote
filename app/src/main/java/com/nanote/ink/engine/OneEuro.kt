package com.nanote.ink.engine

import kotlin.math.PI
import kotlin.math.abs

/**
 * 1-euro filter: heavy smoothing when slow (kills jitter), light when fast
 * (kills lag). Run on x and y separately, one instance per axis per stroke.
 *
 * Adapted from the reference implementation in the product spec; tuned
 * defaults for stylus input at ~120 Hz.
 */
class OneEuro(
    private val minCutoff: Float = 1.5f,
    private val beta: Float = 0.01f,
    private val dCutoff: Float = 1f
) {
    private var x = 0f
    private var dx = 0f
    private var t = -1f
    private var inited = false

    fun reset() {
        inited = false
        t = -1f
        x = 0f
        dx = 0f
    }

    private fun alpha(cutoff: Float, dt: Float): Float =
        1f / (1f + 1f / (2f * PI.toFloat() * cutoff * dt))

    fun apply(v: Float, tSec: Float): Float {
        if (!inited) {
            inited = true
            t = tSec
            x = v
            return v
        }
        val dt = maxOf(tSec - t, 1e-3f)
        dx += alpha(dCutoff, dt) * ((v - x) / dt - dx)
        x += alpha(minCutoff + beta * abs(dx), dt) * (v - x)
        t = tSec
        return x
    }
}
