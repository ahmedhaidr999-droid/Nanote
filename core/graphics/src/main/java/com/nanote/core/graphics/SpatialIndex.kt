package com.nanote.core.graphics

import com.nanote.core.model.Stroke
import kotlin.math.floor

/**
 * Uniform-grid spatial hash over stroke bounding boxes (§9 / §16).
 *
 * Rebuilt incrementally: [rebuild] from the live document lists, then
 * [insert] / [remove] as commands mutate them. Queries return candidate
 * strokes for a world rect so erasing, lassoing and viewport culling never
 * scan the whole document.
 *
 * Not thread-safe — owned by the input/render thread like the rest of the
 * engine state.
 */
class SpatialIndex(
    private val cellSize: Float = 256f
) {

    companion object {
        /** Safe world bounds for the infinite canvas (±150k PDF points). */
        const val INFINITE_LIMIT = 150_000f
    }

    private val cells = HashMap<Long, MutableList<Stroke>>()
    private val strokeCells = HashMap<String, MutableList<Long>>()

    private fun keyOf(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

    fun clear() {
        cells.clear()
        strokeCells.clear()
    }

    fun rebuild(strokes: List<Stroke>) {
        clear()
        for (s in strokes) insert(s)
    }

    fun insert(stroke: Stroke) {
        if (stroke.id in strokeCells) return
        val b = stroke.bbox()
        // clamp indices to sane range even for stray coordinates
        val x0 = clampCell(floor((b[0] - stroke.width) / cellSize).toInt())
        val x1 = clampCell(floor((b[2] + stroke.width) / cellSize).toInt())
        val y0 = clampCell(floor((b[1] - stroke.width) / cellSize).toInt())
        val y1 = clampCell(floor((b[3] + stroke.width) / cellSize).toInt())
        val list = ArrayList<Long>(4)
        for (cx in x0..x1) {
            for (cy in y0..y1) {
                val k = keyOf(cx, cy)
                cells.getOrPut(k) { ArrayList(8) }.add(stroke)
                list.add(k)
            }
        }
        strokeCells[stroke.id] = list
    }

    fun remove(stroke: Stroke) {
        val list = strokeCells.remove(stroke.id) ?: return
        for (k in list) {
            cells[k]?.let { bucket ->
                bucket.removeAll { it.id == stroke.id }
                if (bucket.isEmpty()) cells.remove(k)
            }
        }
    }

    /**
     * Candidate strokes whose bbox overlaps the query rect (l, t, r, b).
     * Callers must still do exact geometric tests — this is a broad phase.
     */
    fun query(l: Float, t: Float, r: Float, b: Float): List<Stroke> {
        if (cells.isEmpty()) return emptyList()
        val x0 = clampCell(floor(l / cellSize).toInt())
        val x1 = clampCell(floor(r / cellSize).toInt())
        val y0 = clampCell(floor(t / cellSize).toInt())
        val y1 = clampCell(floor(b / cellSize).toInt())
        // avoid absurd iteration when zoomed far out
        val wide = (x1 - x0 + 1) * (y1 - y0 + 1)
        if (wide > 4096 || cells.size < 24) {
            // degenerate zoom-out: scanning everything is cheaper
            val seen = LinkedHashSet<Stroke>()
            for (bucket in cells.values) seen.addAll(bucket)
            return seen.filter { s ->
                val bb = s.bbox()
                bb[2] >= l && bb[0] <= r && bb[3] >= t && bb[1] <= b
            }
        }
        val seen = LinkedHashSet<Stroke>()
        for (cx in x0..x1) {
            for (cy in y0..y1) {
                val bucket = cells[keyOf(cx, cy)] ?: continue
                for (s in bucket) {
                    val bb = s.bbox()
                    if (bb[2] >= l && bb[0] <= r && bb[3] >= t && bb[1] <= b) seen.add(s)
                }
            }
        }
        return seen.toList()
    }

    private fun clampCell(v: Int): Int = v.coerceIn(-4096, 4096)
}
