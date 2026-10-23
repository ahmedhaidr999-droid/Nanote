package com.nanote.ink.engine

import kotlin.math.floor

/**
 * Rendering-relevant pen behavior, declared without any android.graphics
 * types so the engine stays pure Kotlin. The render layer maps [capOrdinal]
 * to Paint.Cap and [multiply] to a BlendMode.
 */
class PenSpec(
    val minScale: Float,
    val maxScale: Float,
    val speedThin: Float,
    val alpha: Float,
    val capOrdinal: Int, // 0 = ROUND, 1 = SQUARE, 2 = BUTT
    val multiply: Boolean
) {
    companion object {
        const val CAP_ROUND = 0
        const val CAP_SQUARE = 1
        const val CAP_BUTT = 2
    }
}

val PEN_SPECS: Map<PenType, PenSpec> = mapOf(
    PenType.BALLPOINT to PenSpec(0.90f, 1.10f, 0.00f, 1.00f, PenSpec.CAP_ROUND, false),
    PenType.FOUNTAIN to PenSpec(0.35f, 1.60f, 0.30f, 1.00f, PenSpec.CAP_ROUND, false), // calligraphic
    PenType.PENCIL to PenSpec(0.60f, 1.20f, 0.00f, 0.75f, PenSpec.CAP_ROUND, false),
    PenType.MARKER to PenSpec(1.00f, 1.00f, 0.00f, 0.90f, PenSpec.CAP_ROUND, false),
    PenType.HIGHLIGHTER to PenSpec(1.00f, 1.00f, 0.00f, 0.35f, PenSpec.CAP_SQUARE, true) // MULTIPLY, drawn under ink
)

fun specOf(pen: PenType): PenSpec = PEN_SPECS[pen] ?: PEN_SPECS.getValue(PenType.BALLPOINT)

val PALETTE = intArrayOf(
    0xFF000000.toInt(), 0xFF424242.toInt(), 0xFF9E9E9E.toInt(), 0xFFD32F2F.toInt(),
    0xFFF57C00.toInt(), 0xFFFBC02D.toInt(), 0xFF388E3C.toInt(), 0xFF00897B.toInt(),
    0xFF1976D2.toInt(), 0xFF303F9F.toInt(), 0xFF7B1FA2.toInt(), 0xFFC2185B.toInt()
)

val HIGHLIGHTS = intArrayOf(
    0xFFFFEB3B.toInt(), 0xFF69F0AE.toInt(), 0xFF40C4FF.toInt(),
    0xFFFF80AB.toInt(), 0xFFB388FF.toInt(), 0xFFFFAB40.toInt()
)

val PEN_WIDTHS_PT = floatArrayOf(0.5f, 1f, 1.5f, 2.5f, 4f, 6f, 10f)
val HIGHLIGHT_WIDTHS_PT = floatArrayOf(8f, 12f, 18f, 26f)
val ERASER_RADII_PT = floatArrayOf(4f, 8f, 14f, 24f)

/** Page sizes in PDF points. */
val PAGE_SIZES_PT: Map<String, Pair<Float, Float>> = mapOf(
    "A4" to (595f to 842f),
    "A5" to (420f to 595f),
    "Letter" to (612f to 792f),
    "Long" to (595f to 1684f)
)

class Paper(
    val id: String,
    val spacing: Float,
    val argb: Int,
    val lineW: Float,
    val margin: Float
)

/** 5 mm = 14.17 pt. */
val PAPERS = listOf(
    Paper("blank", 0f, 0, 0f, 0f),
    Paper("lined_narrow", 18f, 0xFFB0BEC5.toInt(), 0.6f, 56f),
    Paper("lined_wide", 26f, 0xFFB0BEC5.toInt(), 0.6f, 56f),
    Paper("grid_5mm", 14.17f, 0xFFCFD8DC.toInt(), 0.5f, 0f),
    Paper("dots_5mm", 14.17f, 0xFF90A4AE.toInt(), 1.2f, 0f),
    Paper("cornell", 26f, 0xFFB0BEC5.toInt(), 0.6f, 56f)
)

fun paperById(id: String): Paper = PAPERS.firstOrNull { it.id == id } ?: PAPERS[0]

/**
 * Uniform spatial grid so hit-tests only touch nearby strokes even on pages
 * with 10,000+ strokes. Cell size in page points.
 */
class SpatialGrid(private val cell: Float = 64f) {

    private val map = HashMap<Long, MutableList<Int>>()

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)

    private inline fun cells(r: Bounds, f: (Long) -> Unit) {
        if (r.isEmpty) return
        val x0 = floor(r.left / cell).toInt()
        val x1 = floor(r.right / cell).toInt()
        val y0 = floor(r.top / cell).toInt()
        val y1 = floor(r.bottom / cell).toInt()
        for (cy in y0..y1) {
            for (cx in x0..x1) {
                f(key(cx, cy))
            }
        }
    }

    fun insert(id: Int, b: Bounds) = cells(b) { map.getOrPut(it) { mutableListOf() }.add(id) }

    fun remove(id: Int, b: Bounds) = cells(b) { map[it]?.remove(id) }

    fun query(b: Bounds, out: MutableSet<Int>) {
        out.clear()
        cells(b) { map[it]?.let(out::addAll) }
    }

    fun clear() = map.clear()

    val size: Int get() = map.size
}
