package com.nanote.ink.engine

import kotlin.math.hypot
import kotlin.math.pow

/**
 * Pressure lookup table: gamma curve so light pressure stays thin without
 * crushing the whole usable range. v1.3: exponent 0.8 -> 0.65 (max pen
 * sensitivity) — the lightest touches grow visible width sooner.
 */
val PRESSURE_LUT = FloatArray(256) { (it / 255f).pow(0.65f) }

/**
 * Per-point width of a stroke in page points:
 *  - pressure mapped through the LUT, scaled between spec.minScale..maxScale
 *  - fountain pens thinned by speed
 *  - low-pass filtered so width never jumps frame to frame
 * When pressure is constant (finger, stylus without pressure) the only
 * variation comes from speed, as required by the spec.
 */
fun widths(s: Stroke, spec: PenSpec): FloatArray {
    val w = FloatArray(s.count)
    var prev = s.baseWidth
    for (i in 0 until s.count) {
        val pr = PRESSURE_LUT[(s.p(i).coerceIn(0f, 1f) * 255f).toInt()]
        val speed = if (i == 0) 0f
        else hypot(s.x(i) - s.x(i - 1), s.y(i) - s.y(i - 1)) / (maxOf(s.t(i) - s.t(i - 1), 1f) / 1000f) // pt/s
        val thin = 1f - spec.speedThin * (speed / 1500f).coerceIn(0f, 1f)
        val target = s.baseWidth * (spec.minScale + (spec.maxScale - spec.minScale) * pr) * thin
        prev += 0.35f * (target - prev)
        w[i] = prev
    }
    return w
}
