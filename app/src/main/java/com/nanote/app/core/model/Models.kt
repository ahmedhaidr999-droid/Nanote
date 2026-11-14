package com.nanote.app.core.model

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/**
 * Nanote core data models.
 * All element coordinates are WORLD coordinates in PDF-point space relative to
 * the page they belong to (page index + local coords). Annotations extracted
 * from PDF text use page-relative [0..1] coords so they stay correct at any zoom.
 */

const val CURRENT_FORMAT_VERSION = 2

enum class ToolType {
    PEN, PENCIL, BRUSH, HIGHLIGHTER, ERASER, LASER,
    LASSO, SELECT_OBJECT, SELECT_TEXT,
    TEXT, IMAGE, CAMERA, PDF, SHAPE, STICKY_NOTE,
    HAND, UNDO, REDO, ZOOM, MORE
}

val ToolType.isInkTool: Boolean
    get() = this == ToolType.PEN || this == ToolType.PENCIL || this == ToolType.BRUSH || this == ToolType.HIGHLIGHTER

val ToolType.isInstantAction: Boolean
    get() = this == ToolType.UNDO || this == ToolType.REDO || this == ToolType.ZOOM || this == ToolType.MORE

enum class ShapeKind { LINE, ARROW, RECT, CIRCLE, TRIANGLE }
enum class EraserMode { PIXEL, STROKE, OBJECT }
enum class ToolbarPosition { BOTTOM, LEFT, RIGHT }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class AppLanguage { SYSTEM, ENGLISH, ARABIC }
enum class NoteKind { NOTE, PDF }

@Serializable
data class StrokePoint(val x: Float, val y: Float, val p: Float = 1f)

@Serializable
data class Stroke(
    val id: String,
    val page: Int,
    val tool: ToolType,
    val color: Long = 0xFF2563EB,
    val width: Float = 4f,
    val opacity: Int = 100,
    val points: List<StrokePoint> = emptyList()
) {
    @Transient
    private var cachedBBox: FloatArray? = null

    /** Cached bounding box [minX, minY, maxX, maxY] in world units. */
    fun bbox(): FloatArray {
        cachedBBox?.let { return it }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (pt in points) {
            minX = min(minX, pt.x); minY = min(minY, pt.y)
            maxX = max(maxX, pt.x); maxY = max(maxY, pt.y)
        }
        if (points.isEmpty()) { minX = 0f; minY = 0f; maxX = 0f; maxY = 0f }
        val r = floatArrayOf(minX, minY, maxX, maxY)
        cachedBBox = r
        return r
    }
}

@Serializable
data class ShapeElement(
    val id: String,
    val page: Int,
    val kind: ShapeKind,
    val color: Long = 0xFF2563EB,
    val width: Float = 3f,
    val opacity: Int = 100,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float
) {
    fun bbox(): FloatArray {
        return floatArrayOf(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    }
}

@Serializable
data class TextElement(
    val id: String,
    val page: Int,
    val text: String = "",
    val x: Float, val y: Float,
    val size: Float = 18f,
    val color: Long = 0xFF0F172A,
    val sticky: Boolean = false,
    val width: Float = 260f
)

@Serializable
data class ImageElement(
    val id: String,
    val page: Int,
    val x: Float, val y: Float,
    val w: Float, val h: Float,
    val rotation: Float = 0f,
    /** Path relative to the document directory. */
    val path: String
)

/** Rect encoded as [x0, y0, x1, y1] in page-relative coordinates [0..1]. */
typealias RelRect = List<Float>

@Serializable
data class HighlightAnnotation(
    val id: String,
    val page: Int,
    val color: Long = 0xFFFDE047,
    val opacity: Int = 40,
    val rects: List<RelRect> = emptyList()
)

@Serializable
data class UnderlineAnnotation(
    val id: String,
    val page: Int,
    val color: Long = 0xFFEF4444,
    val opacity: Int = 100,
    val rects: List<RelRect> = emptyList()
)

@Serializable
data class PageDef(
    val index: Int,
    val width: Float = 595f,
    val height: Float = 842f,
    /** >= 0 → this page maps to that page of the source PDF. */
    val pdfPage: Int = -1
)

@Serializable
data class DocumentContent(
    val version: Int = CURRENT_FORMAT_VERSION,
    val pages: List<PageDef> = emptyList(),
    val strokes: List<Stroke> = emptyList(),
    val shapes: List<ShapeElement> = emptyList(),
    val texts: List<TextElement> = emptyList(),
    val images: List<ImageElement> = emptyList(),
    val highlights: List<HighlightAnnotation> = emptyList(),
    val underlines: List<UnderlineAnnotation> = emptyList()
)

// ---------------------------------------------------------------------------
// Tool runtime configuration (persisted, editable live from popovers)
// ---------------------------------------------------------------------------

@Serializable
data class ToolConfig(
    // Pen
    val penColor: Long = 0xFF2563EB,
    val penWidth: Float = 4f,
    val penOpacity: Int = 100,
    // Pencil
    val pencilColor: Long = 0xFF475569,
    val pencilWidth: Float = 2.5f,
    val pencilOpacity: Int = 100,
    // Brush
    val brushColor: Long = 0xFF1D4ED8,
    val brushWidth: Float = 14f,
    val brushOpacity: Int = 100,
    // Highlighter — opacity UI 0..100 maps to at most ~65% visual alpha so
    // text under the highlight ALWAYS stays readable (per product spec).
    val highlighterColor: Long = 0xFFFDE047,
    val highlighterWidth: Float = 26f,
    val highlighterOpacity: Int = 40,
    // Shapes
    val shapeKind: ShapeKind = ShapeKind.RECT,
    val shapeColor: Long = 0xFF2563EB,
    val shapeWidth: Float = 3f,
    // Eraser
    val eraserMode: EraserMode = EraserMode.STROKE,
    val eraserSize: Float = 36f,
    // Text
    val textColor: Long = 0xFF0F172A,
    val textSize: Float = 18f
)

/**
 * Highlighter alpha: UI opacity percent → alpha channel.
 * Capped at 165/255 (≈65%) so highlighted PDF text remains readable even at 100%.
 */
fun highlighterAlphaOf(opacityPct: Int): Int = (opacityPct.coerceIn(0, 100) * 165) / 100

/** Regular paint alpha: UI opacity percent 0..100 → 0..255. */
fun paintAlphaOf(opacityPct: Int): Int = (opacityPct.coerceIn(0, 100) * 255) / 100

@Serializable
data class ToolPreset(
    val name: String,
    val tool: ToolType,
    val color: Long,
    val width: Float,
    val opacity: Int
)

@Serializable
data class ToolbarConfig(
    val visible: List<ToolType> = DEFAULT_TOOLBAR,
    val hidden: List<ToolType> = defaultHidden(),
    val position: ToolbarPosition = ToolbarPosition.BOTTOM
)

val DEFAULT_TOOLBAR = listOf(
    ToolType.PEN, ToolType.HIGHLIGHTER, ToolType.ERASER, ToolType.SHAPE,
    ToolType.TEXT, ToolType.IMAGE, ToolType.LASSO,
    ToolType.UNDO, ToolType.REDO, ToolType.HAND, ToolType.ZOOM, ToolType.MORE
)

fun defaultHidden(): List<ToolType> = ToolType.entries.filter { it !in DEFAULT_TOOLBAR && it != ToolType.CAMERA && it != ToolType.PDF }

// ---------------------------------------------------------------------------
// Quick palette used across pen popovers (Notewise-like swatch row)
// ---------------------------------------------------------------------------

val QUICK_PALETTE: List<Long> = listOf(
    0xFF0F172A, 0xFF64748B, 0xFFFFFFFF, 0xFFEF4444, 0xFFF97316, 0xFFFDE047,
    0xFF22C55E, 0xFF2563EB, 0xFF7C3AED, 0xFFEC4899, 0xFF78350F, 0xFF06B6D4
)

fun newId(): String =
    java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16)
