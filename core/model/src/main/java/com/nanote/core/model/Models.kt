package com.nanote.core.model

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/**
 * Nanote core data models.
 * All element coordinates are WORLD coordinates in PDF-point space relative to
 * the page they belong to (page index + local coords). Annotations extracted
 * from PDF text use page-relative [0..1] coords so they stay correct at any zoom.
 *
 * Format v3 (Part 2): stroke points carry tilt + timestamps, strokes can be
 * grouped, text/shapes gained rich properties, pages carry per-page templates.
 * Every v3 field has a default so v2 files keep loading unchanged.
 */

const val CURRENT_FORMAT_VERSION = 3

enum class ToolType {
    PEN, PENCIL, BRUSH, HIGHLIGHTER, ERASER, LASER,
    LASSO, SELECT_OBJECT, SELECT_TEXT,
    TEXT, IMAGE, CAMERA, PDF, SHAPE, STICKY_NOTE,
    HAND, UNDO, REDO, ZOOM, MORE,
    FOUNTAIN
}

val ToolType.isInkTool: Boolean
    get() = this == ToolType.PEN || this == ToolType.PENCIL || this == ToolType.BRUSH ||
        this == ToolType.HIGHLIGHTER || this == ToolType.FOUNTAIN

val ToolType.isInstantAction: Boolean
    get() = this == ToolType.UNDO || this == ToolType.REDO || this == ToolType.ZOOM || this == ToolType.MORE

enum class ShapeKind {
    LINE, ARROW, RECT, CIRCLE, TRIANGLE,
    DOUBLE_ARROW, ROUNDED_RECT, ELLIPSE, POLYGON
}

enum class EraserMode { PIXEL, STROKE, OBJECT }
enum class ToolbarPosition { BOTTOM, LEFT, RIGHT }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class AppLanguage { SYSTEM, ENGLISH, ARABIC }
enum class NoteKind { NOTE, PDF }

/** What a finger does on the canvas (§5 palm rejection / finger behavior). */
enum class FingerAction {
    /** Fingers pan/zoom — only the stylus draws (default, safest). */
    SCROLL,
    /** Fingers also draw with the active ink tool. */
    DRAW,
    /** Fingers are ignored on the canvas entirely. */
    IGNORE
}

/** Pressure response curve applied to raw stylus pressure (§3). */
enum class PressureCurve { LINEAR, SOFT, FIRM }

/** Canvas engine mode (§9). PAGED = fixed pages, INFINITE = endless canvas. */
enum class CanvasMode { PAGED, INFINITE }

/** How the lasso tool selects (§11). */
enum class SelectionMode { LASSO, RECTANGLE }

/** List style applied to text blocks (§12). */
enum class TextListStyle { NONE, BULLET, NUMBERED }

/** Paragraph alignment for text blocks (§12). START/END resolve via bidi. */
enum class TextAlignMode { START, CENTER, END }

/** Named font families available to the text engine (§12). */
enum class EditorFont { DEFAULT, SERIF, MONOSPACE, CASUAL, CURSIVE, SANS_SERIF_SMALL_CAPS }

/** Maps raw stylus pressure through the selected response curve (§3). */
fun pressureCurveApply(curve: PressureCurve, p: Float): Float = when (curve) {
    PressureCurve.LINEAR -> p
    PressureCurve.SOFT -> p * p * (3f - 2f * p)          // smoothstep — gentle start
    PressureCurve.FIRM -> kotlin.math.sqrt(p)             // reaches width sooner
}

@Serializable
data class StrokePoint(
    val x: Float,
    val y: Float,
    val p: Float = 1f,
    /** Stylus tilt in radians when the hardware reports it (0 when unavailable). */
    val tilt: Float = 0f,
    /** Event timestamp in nanos from input (System.nanoTime) — used for velocity. */
    val t: Long = 0L
)

@Serializable
data class Stroke(
    val id: String,
    val page: Int,
    val tool: ToolType,
    val color: Long = 0xFF2563EB,
    val width: Float = 4f,
    val opacity: Int = 100,
    val points: List<StrokePoint> = emptyList(),
    /** Wall-clock creation time (ms) — ordering & debugging. */
    val timestamp: Long = 0L,
    /** Non-null → the stroke belongs to a selection group (§8 group/ungroup). */
    val groupId: String? = null,
    /** Pressure response baked at capture time so later setting changes never
     *  alter the look of already-written ink. */
    val pressureResponse: Float = 0.6f
) {
    @kotlinx.serialization.Transient
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
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    /** Fill color used when [filled] is true. */
    val fillColor: Long = 0x332563EB,
    val filled: Boolean = false,
    /** Rotation in degrees around the shape center (§14). */
    val rotation: Float = 0f,
    /** POLYGON only: normalized [0..1] vertex pairs inside the bbox. */
    val vertices: List<Float> = emptyList(),
    val timestamp: Long = 0L,
    val groupId: String? = null
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
    val width: Float = 260f,
    // ---- v3 rich text (§12) ----
    val font: EditorFont = EditorFont.DEFAULT,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val align: TextAlignMode = TextAlignMode.START,
    val listStyle: TextListStyle = TextListStyle.NONE,
    /** 0 → fully transparent (no background). */
    val bgColor: Long = 0L,
    val rotation: Float = 0f,
    val timestamp: Long = 0L,
    val groupId: String? = null
)

@Serializable
data class ImageElement(
    val id: String,
    val page: Int,
    val x: Float, val y: Float,
    val w: Float, val h: Float,
    val rotation: Float = 0f,
    /** Path relative to the document directory. */
    val path: String,
    /** Optional normalized crop rect [x0,y0,x1,y1] within the source image. */
    val crop: List<Float> = emptyList(),
    val timestamp: Long = 0L,
    val groupId: String? = null
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

/** Named page templates paintable as backgrounds (§10). */
enum class PageTemplateKind {
    BLANK, RULED, COLLEGE_RULED, WIDE_RULED, GRID, SMALL_GRID, DOTTED,
    CORNELL, MUSIC_STAFF, PLANNER, TODO, CUSTOM
}

@Serializable
data class PageDef(
    val index: Int,
    val width: Float = 595f,
    val height: Float = 842f,
    /** >= 0 → this page maps to that page of the source PDF. */
    val pdfPage: Int = -1,
    // ---- v3 per-page settings (§10) ----
    val template: PageTemplateKind = PageTemplateKind.BLANK,
    /** 0 → default template ink color. */
    val templateColor: Long = 0L,
    val title: String = ""
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
    val underlines: List<UnderlineAnnotation> = emptyList(),
    /** v3: canvas engine mode for this document (§9). */
    val canvasMode: CanvasMode = CanvasMode.PAGED
)

// ---------------------------------------------------------------------------
// Tool runtime configuration (persisted, editable live from popovers)
// ---------------------------------------------------------------------------

@Serializable
data class ToolConfig(
    // Pen (ballpoint)
    val penColor: Long = 0xFF2563EB,
    val penWidth: Float = 4f,
    val penOpacity: Int = 100,
    val penPressure: Float = 0.6f,
    val penSmoothing: Float = 0.5f,
    // Fountain
    val fountainColor: Long = 0xFF1E3A8A,
    val fountainWidth: Float = 7f,
    val fountainOpacity: Int = 100,
    val fountainPressure: Float = 0.85f,
    val fountainSmoothing: Float = 0.65f,
    // Pencil
    val pencilColor: Long = 0xFF475569,
    val pencilWidth: Float = 2.5f,
    val pencilOpacity: Int = 100,
    val pencilPressure: Float = 0.75f,
    val pencilSmoothing: Float = 0.4f,
    // Brush
    val brushColor: Long = 0xFF1D4ED8,
    val brushWidth: Float = 14f,
    val brushOpacity: Int = 100,
    val brushPressure: Float = 0.95f,
    val brushSmoothing: Float = 0.7f,
    // Highlighter — opacity UI 0..100 maps to at most ~65% visual alpha so
    // text under the highlight ALWAYS stays readable (per product spec).
    val highlighterColor: Long = 0xFFFDE047,
    val highlighterWidth: Float = 26f,
    val highlighterOpacity: Int = 40,
    val highlighterPressure: Float = 0f,
    val highlighterSmoothing: Float = 0.8f,
    // Shapes
    val shapeKind: ShapeKind = ShapeKind.RECT,
    val shapeColor: Long = 0xFF2563EB,
    val shapeWidth: Float = 3f,
    val shapeFillColor: Long = 0x332563EB,
    val shapeFilled: Boolean = false,
    val shapeOpacity: Int = 100,
    val shapeSnap: Boolean = true,
    /** Deterministic stroke → shape conversion (no AI, pure geometry). */
    val shapeRecognition: Boolean = true,
    // Eraser
    val eraserMode: EraserMode = EraserMode.STROKE,
    val eraserSize: Float = 36f,
    // Text
    val textColor: Long = 0xFF0F172A,
    val textSize: Float = 18f,
    // Selection (§11)
    val selectionMode: SelectionMode = SelectionMode.LASSO
) {
    /** Live width/opacity/pressure/smoothing of the given ink tool. */
    fun inkStyleOf(tool: ToolType): InkStyle = when (tool) {
        ToolType.PEN -> InkStyle(penColor, penWidth, penOpacity, penPressure, penSmoothing)
        ToolType.FOUNTAIN -> InkStyle(fountainColor, fountainWidth, fountainOpacity, fountainPressure, fountainSmoothing)
        ToolType.PENCIL -> InkStyle(pencilColor, pencilWidth, pencilOpacity, pencilPressure, pencilSmoothing)
        ToolType.BRUSH -> InkStyle(brushColor, brushWidth, brushOpacity, brushPressure, brushSmoothing)
        ToolType.HIGHLIGHTER -> InkStyle(highlighterColor, highlighterWidth, highlighterOpacity, highlighterPressure, highlighterSmoothing)
        else -> InkStyle(penColor, penWidth, penOpacity, penPressure, penSmoothing)
    }
}

/** Per-tool ink appearance + input response (§2 tool configuration). */
@Serializable
data class InkStyle(
    val color: Long,
    val width: Float,
    val opacity: Int,
    /** 0..1 — how strongly pressure modulates stroke width. */
    val pressureResponse: Float,
    /** 0..1 — endpoint smoothing applied on commit. */
    val smoothing: Float
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
    val opacity: Int,
    val pressureResponse: Float = 0.65f,
    val smoothing: Float = 0.5f
)

/** Recent + favorite ink colors (§3) — persisted alongside tool config. */
@Serializable
data class ColorMemory(
    val recent: List<Long> = emptyList(),
    val favorites: List<Long> = emptyList()
) {
    companion object {
        const val MAX_RECENT = 10
        const val MAX_FAVORITES = 14
    }

    fun remember(color: Long): ColorMemory {
        if (color == 0L) return this
        val next = (listOf(color) + recent.filterNot { it == color }).take(MAX_RECENT)
        return copy(recent = next)
    }

    fun toggleFavorite(color: Long): ColorMemory {
        if (color == 0L) return this
        val next = if (color in favorites) {
            favorites.filterNot { it == color }
        } else {
            (favorites + color).take(MAX_FAVORITES)
        }
        return copy(favorites = next)
    }
}

@Serializable
data class ToolbarConfig(
    val visible: List<ToolType> = DEFAULT_TOOLBAR,
    val hidden: List<ToolType> = defaultHidden(),
    val position: ToolbarPosition = ToolbarPosition.BOTTOM,
    /** §15: compact shows icons only, expanded adds labels + recent colors. */
    val compact: Boolean = false
)

val DEFAULT_TOOLBAR = listOf(
    ToolType.PEN, ToolType.FOUNTAIN, ToolType.HIGHLIGHTER, ToolType.ERASER, ToolType.SHAPE,
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
