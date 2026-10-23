package com.nanote.ink.engine

import kotlin.math.sqrt
import kotlin.math.hypot

/**
 * Ramer-Douglas-Peucker simplification.
 *  - [simplify] works on STRIDE-packed stroke points and keeps pressure/timing.
 *  - [rdpXY] works on plain x,y pairs and returns the kept corners.
 * Used at stroke end (epsilon ~0.15 pt) to cut storage and with a larger
 * epsilon for shape recognition.
 */
fun simplify(src: FloatArray, eps: Float): FloatArray {
    val n = src.size / STRIDE
    if (n < 3) return src
    val keep = BooleanArray(n)
    keep[0] = true
    keep[n - 1] = true
    val stack = ArrayDeque<IntArray>()
    stack.addLast(intArrayOf(0, n - 1))
    while (stack.isNotEmpty()) {
        val (a, b) = stack.removeLast()
        var far = -1
        var best = eps
        for (i in a + 1 until b) {
            val d = sqrt(
                distPointSegSq(
                    src[i * STRIDE], src[i * STRIDE + 1],
                    src[a * STRIDE], src[a * STRIDE + 1],
                    src[b * STRIDE], src[b * STRIDE + 1]
                )
            )
            if (d > best) {
                best = d
                far = i
            }
        }
        if (far >= 0) {
            keep[far] = true
            stack.addLast(intArrayOf(a, far))
            stack.addLast(intArrayOf(far, b))
        }
    }
    val out = ArrayList<Float>(keep.count { it } * STRIDE)
    for (i in 0 until n) {
        if (keep[i]) {
            for (k in 0 until STRIDE) out.add(src[i * STRIDE + k])
        }
    }
    return out.toFloatArray()
}

/** RDP over x0,y0,x1,y1,... pairs; returns kept pairs in order. */
fun rdpXY(xy: FloatArray, eps: Float): FloatArray {
    val n = xy.size / 2
    if (n < 3) return xy
    val keep = BooleanArray(n)
    keep[0] = true
    keep[n - 1] = true
    val stack = ArrayDeque<IntArray>()
    stack.addLast(intArrayOf(0, n - 1))
    while (stack.isNotEmpty()) {
        val (a, b) = stack.removeLast()
        var far = -1
        var best = eps
        for (i in a + 1 until b) {
            val d = sqrt(distPointSegSq(xy[2 * i], xy[2 * i + 1], xy[2 * a], xy[2 * a + 1], xy[2 * b], xy[2 * b + 1]))
            if (d > best) {
                best = d
                far = i
            }
        }
        if (far >= 0) {
            keep[far] = true
            stack.addLast(intArrayOf(a, far))
            stack.addLast(intArrayOf(far, b))
        }
    }
    val out = ArrayList<Float>(keep.count { it } * 2)
    for (i in 0 until n) {
        if (keep[i]) {
            out.add(xy[2 * i])
            out.add(xy[2 * i + 1])
        }
    }
    return out.toFloatArray()
}

/** Bounding box of x,y pairs into [out]; returns false for empty input. */
fun boundsOf(xy: FloatArray, out: Bounds): Boolean {
    out.reset()
    val n = xy.size / 2
    if (n == 0) return false
    for (i in 0 until n) out.union(xy[2 * i], xy[2 * i + 1])
    return true
}

/** Polyline length of x,y pairs. */
fun pathLength(xy: FloatArray): Float {
    var len = 0f
    val n = xy.size / 2
    for (i in 1 until n) {
        len += hypot(xy[2 * i] - xy[2 * i - 2], xy[2 * i + 1] - xy[2 * i - 1])
    }
    return len
}

/** Resamples a polyline to (roughly) evenly spaced points. */
fun resampleXY(xy: FloatArray, step: Float): FloatArray {
    val n = xy.size / 2
    if (n < 2 || step <= 0f) return xy
    val out = ArrayList<Float>(n * 4)
    out.add(xy[0]); out.add(xy[1])
    var prevX = xy[0]
    var prevY = xy[1]
    var carry = 0f
    for (i in 1 until n) {
        var ax = prevX
        var ay = prevY
        val bx = xy[2 * i]
        val by = xy[2 * i + 1]
        var seg = hypot(bx - ax, by - ay)
        while (carry + seg >= step) {
            val need = step - carry
            val dirx = (bx - ax) / seg
            val diry = (by - ay) / seg
            ax += dirx * need
            ay += diry * need
            out.add(ax)
            out.add(ay)
            seg -= need
            carry = 0f
        }
        carry += seg
        prevX = bx
        prevY = by
    }
    // Always keep the exact final point.
    out.add(xy[2 * n - 2])
    out.add(xy[2 * n - 1])
    return out.toFloatArray()
}



