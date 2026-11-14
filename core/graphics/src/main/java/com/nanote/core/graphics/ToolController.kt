package com.nanote.core.graphics

import android.graphics.PointF
import com.nanote.core.model.Command
import com.nanote.core.model.DocumentModel
import com.nanote.core.model.EraserMode
import com.nanote.core.model.FingerAction
import com.nanote.core.model.SelectionMode
import com.nanote.core.model.ShapeRecognition
import com.nanote.core.model.StrokeOps
import com.nanote.core.model.isInkTool
import com.nanote.core.model.HighlightAnnotation
import com.nanote.core.model.ImageElement
import com.nanote.core.model.ShapeElement
import com.nanote.core.model.ShapeKind
import com.nanote.core.model.Stroke
import com.nanote.core.model.StrokePoint
import com.nanote.core.model.TextElement
import com.nanote.core.model.ToolConfig
import com.nanote.core.model.ToolType
import com.nanote.core.model.UnderlineAnnotation
import com.nanote.core.model.distToPolyline
import com.nanote.core.model.newId
import com.nanote.core.model.pointInPolygon
import com.nanote.core.pdf.PdfTextExtractor
import com.nanote.core.pdf.PdfWord
import com.nanote.core.common.AppLog
import java.io.File
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Touch/pen input brain — one per canvas pane (§1: input handling, editing).
 *
 * Part 2 responsibilities:
 *  - per-tool stroke capture with pressure/tilt/timestamps + smoothing (§2/§4),
 *  - configurable finger behavior & palm rejection policy (§5),
 *  - stroke / object / partial eraser with geometry preservation (§6),
 *  - lasso + rectangle geometric selection with move/resize/rotate (§11),
 *  - copy/cut/paste + group/ungroup through the command pipeline (§8),
 *  - deterministic shape recognition (§14),
 *  - spatial-index backed broad-phase queries (§16).
 */
class ToolController(
    val doc: DocumentModel,
    val state: CanvasState,
    val renderer: ElementRenderer,
    private val textExtractor: PdfTextExtractor?,
    private val imageResolver: (String) -> File?,
    private val scope: CoroutineScope,
    private val listener: Listener
) {

    interface Listener {
        fun invalidate()
        fun commit(command: Command)
        fun onTextMenuVisibility(show: Boolean)
        fun onObjectMenuVisibility(show: Boolean)
        fun onInsertTextRequest(page: Int, worldX: Float, worldY: Float, existing: TextElement?, sticky: Boolean)
        fun onTextPreparing()
        fun onSelectionCountChanged(strokes: Int, texts: Int)
    }

    companion object {
        private const val TAG = "ToolController"
        private const val MIN_POINT_DIST = 1.1f // world units
        private const val LASER_LIFE_MS = 700L
        private const val HANDLE_HIT_RADIUS = 34f // screen px
    }

    // ------------- runtime config -------------

    @Volatile
    var activeTool: ToolType = ToolType.PEN

    @Volatile
    var toolConfig: ToolConfig = ToolConfig()

    @Volatile
    var pressureEnabled: Boolean = true

    /** Hard palm guard: ignore fingers while a stylus was recently active. */
    @Volatile
    var palmRejection: Boolean = true

    /** §5 — what a finger does when no stylus is around. */
    @Volatile
    var fingerAction: FingerAction = FingerAction.SCROLL

    /** Barrel-button held → the stylus temporarily erases. */
    @Volatile
    var tempEraserActive: Boolean = false

    /** doc page index → pdf page index, or -1 when the page is a plain note page. */
    var pdfPageOf: (Int) -> Int = { -1 }

    // ------------- selection state -------------

    val textSelection = TextSelectionEngine()
    val objectSelection = ObjectSelection()
    private var selectedIds: HashSet<String> = HashSet()

    // ------------- spatial index (§16) -------------

    private val index = SpatialIndex()
    private var indexedRevision = -1L

    /** Broad-phase candidates for a world rect (rebuilds the index when stale). */
    fun strokesNear(l: Float, t: Float, r: Float, b: Float): List<Stroke> {
        ensureIndex()
        return index.query(l, t, r, b)
    }

    private fun ensureIndex() {
        if (indexedRevision != doc.revision.value) {
            index.rebuild(doc.strokes)
            indexedRevision = doc.revision.value
        }
    }

    // ------------- live input state -------------

    private var livePoints: ArrayList<StrokePoint>? = null
    private var livePage = -1
    private var liveTool: ToolType = ToolType.PEN
    private var liveWidth = 4f
    private var liveVelocity = -1f
    private var lastEventNanos = 0L

    private val erasedStrokes = ArrayList<Stroke>()
    private val eraserPathWorld = ArrayList<Float>()

    private var shapeStart: FloatArray? = null
    private var shapeCur: FloatArray? = null

    private var lassoPoints: ArrayList<Float>? = null

    /** RECTANGLE selection mode live rect [l, t, r, b] in world coords. */
    var rectSelectionLive: FloatArray? = null
        private set

    private var draggingTextHandle = 0 // 1 start, 2 end

    private var movingSelection = false
    private var scalingSelection = false
    private var rotatingSelection = false
    private var moveLastWorld = PointF()
    private var scaleStartBBox: FloatArray? = null
    private var scaleAnchorWorld = PointF()
    private var scaleStartHandleWorld = PointF()
    private var scaleStartElements: List<Any> = emptyList()
    private var rotateCenter = PointF()
    private var rotateStartAngle = 0f

    /** Scale factor for dragging from [start] relative to [anchor]; guards degenerate divides. */
    private fun scaleAlong(pos: Float, anchor: Float, start: Float): Float {
        val span = start - anchor
        val denom = if (kotlin.math.abs(span) < 2f) 2f * if (span < 0) -1f else 1f else span
        var f = (pos - anchor) / denom
        if (kotlin.math.abs(f) < 0.05f) f = 0.05f * if (f < 0) -1f else 1f
        return f.coerceIn(-8f, 8f)
    }

    private var tapDownWorld = PointF()
    private var tapDownTime = 0L
    private var movedBeyondSlop = false

    // ------------- laser -------------

    class LaserTrail(var points: ArrayList<Float>, val startTime: Long)

    val laserTrails = ArrayList<LaserTrail>()
    private var currentLaser: LaserTrail? = null

    // =====================================================================
    // Input entry points (screen coords; world conversions via state)
    // =====================================================================

    fun onActionDown(
        screenX: Float, screenY: Float, isStylus: Boolean, pressure: Float,
        tilt: Float = 0f, eventNanos: Long = 0L
    ): Boolean {
        movedBeyondSlop = false
        tapDownWorld.set(state.transform.screenToWorldX(screenX), state.transform.screenToWorldY(screenY))
        tapDownTime = System.currentTimeMillis()

        // Text selection handles have priority when a selection is active.
        if (textSelection.isActive) {
            val h = hitTextHandle(screenX, screenY)
            if (h != 0) {
                draggingTextHandle = h
                return true
            }
        }

        // Object selection interactions
        if (objectSelection.isActive) {
            val bbox = objectSelection.bbox(doc, renderer) ?: run { objectSelection.clear(); syncSelectedIds(); listener.onObjectMenuVisibility(false); return false }
            val w = bboxWorldToScreen(bbox)

            // rotation handle (above top-center)
            val rotHx = (w[0] + w[2]) / 2f
            val rotHy = w[1] - 46f
            if (distScreen(screenX, screenY, rotHx, rotHy) < HANDLE_HIT_RADIUS) {
                rotatingSelection = true
                rotateCenter.set((bbox[0] + bbox[2]) / 2f, (bbox[1] + bbox[3]) / 2f)
                rotateStartAngle = angleAt(screenX, screenY)
                return true
            }

            // corner resize handles: 0=TL 1=TR 2=BR 3=BL
            val corners = arrayOf(
                PointF(w[0], w[1]), PointF(w[2], w[1]),
                PointF(w[2], w[3]), PointF(w[0], w[3])
            )
            for ((i, c) in corners.withIndex()) {
                if (distScreen(screenX, screenY, c.x, c.y) < HANDLE_HIT_RADIUS) {
                    scalingSelection = true
                    scaleStartBBox = bbox
                    // anchor = opposite corner (world)
                    val opp = corners[(i + 2) % 4]
                    scaleAnchorWorld.set(state.transform.screenToWorldX(opp.x), state.transform.screenToWorldY(opp.y))
                    scaleStartHandleWorld.set(state.transform.screenToWorldX(c.x), state.transform.screenToWorldY(c.y))
                    scaleStartElements = objectSelection.items.map { it }
                    objectSelection.liveScaleX = 1f
                    objectSelection.liveScaleY = 1f
                    return true
                }
            }

            if (screenX >= w[0] - 16 && screenX <= w[2] + 16 && screenY >= w[1] - 16 && screenY <= w[3] + 16) {
                movingSelection = true
                moveLastWorld.set(tapDownWorld.x, tapDownWorld.y)
                return true
            } else {
                objectSelection.clear()
                syncSelectedIds()
                listener.onObjectMenuVisibility(false)
                listener.invalidate()
                // fall through to normal tool handling below
            }
        }

        val tool = if (tempEraserActive && isStylus) ToolType.ERASER else activeTool
        val finger = !isStylus

        // §5 finger policy: fingers only draw when explicitly configured to.
        if (finger && tool.isInkTool && fingerAction == FingerAction.SCROLL) {
            return false // view: finger scroll/zoom
        }
        if (finger && fingerAction == FingerAction.IGNORE) {
            return true // consume, do nothing
        }

        when (tool) {
            ToolType.PEN, ToolType.PENCIL, ToolType.BRUSH, ToolType.HIGHLIGHTER, ToolType.FOUNTAIN -> {
                beginStroke(tapDownWorld.x, tapDownWorld.y, pressure, tilt, eventNanos, tool)
                return true
            }
            ToolType.ERASER -> {
                erasedStrokes.clear()
                eraserPathWorld.clear()
                eraseAt(tapDownWorld.x, tapDownWorld.y)
                eraserPathWorld.add(tapDownWorld.x); eraserPathWorld.add(tapDownWorld.y)
                listener.invalidate()
                return true
            }
            ToolType.SHAPE -> {
                shapeStart = floatArrayOf(tapDownWorld.x, tapDownWorld.y)
                shapeCur = floatArrayOf(tapDownWorld.x, tapDownWorld.y)
                listener.invalidate()
                return true
            }
            ToolType.LASSO -> {
                if (toolConfig.selectionMode == SelectionMode.RECTANGLE) {
                    rectSelectionLive = floatArrayOf(tapDownWorld.x, tapDownWorld.y, tapDownWorld.x, tapDownWorld.y)
                } else {
                    lassoPoints = ArrayList()
                    lassoPoints?.add(tapDownWorld.x); lassoPoints?.add(tapDownWorld.y)
                }
                listener.invalidate()
                return true
            }
            ToolType.LASER -> {
                currentLaser = LaserTrail(arrayListOf(screenX, screenY), System.currentTimeMillis())
                laserTrails.add(currentLaser!!)
                listener.invalidate()
                return true
            }
            ToolType.SELECT_TEXT -> {
                selectTextAt(tapDownWorld.x, tapDownWorld.y)
                return true
            }
            ToolType.SELECT_OBJECT -> {
                // decided on UP (tap)
                return true
            }
            ToolType.TEXT, ToolType.STICKY_NOTE -> {
                // decided on UP (tap)
                return true
            }
            else -> return false
        }
    }

    fun onActionMove(
        screenX: Float, screenY: Float, isStylus: Boolean, pressure: Float,
        tilt: Float = 0f, eventNanos: Long = 0L
    ): Boolean {
        val wx = state.transform.screenToWorldX(screenX)
        val wy = state.transform.screenToWorldY(screenY)
        if (hypot(wx - tapDownWorld.x, wy - tapDownWorld.y) > 6f / state.transform.scale) {
            movedBeyondSlop = true
        }

        if (draggingTextHandle != 0) {
            dragTextHandleTo(wx, wy)
            return true
        }
        if (movingSelection) {
            objectSelection.dragAccumX += wx - moveLastWorld.x
            objectSelection.dragAccumY += wy - moveLastWorld.y
            moveLastWorld.set(wx, wy)
            listener.invalidate()
            return true
        }
        if (scalingSelection && scaleStartBBox != null) {
            val sx = scaleAlong(wx, scaleAnchorWorld.x, scaleStartHandleWorld.x)
            val sy = scaleAlong(wy, scaleAnchorWorld.y, scaleStartHandleWorld.y)
            objectSelection.liveScaleX = sx
            objectSelection.liveScaleY = sy
            listener.invalidate()
            return true
        }
        if (rotatingSelection) {
            val a = angleAt(screenX, screenY)
            objectSelection.liveRotation += (a - rotateStartAngle)
            rotateStartAngle = a
            listener.invalidate()
            return true
        }

        when {
            livePoints != null -> {
                appendStrokePoint(wx, wy, pressure, tilt, eventNanos)
                listener.invalidate()
                return true
            }
            activeTool == ToolType.ERASER && (isStylus || !palmRejection || fingerAction != FingerAction.SCROLL) -> {
                eraseAt(wx, wy)
                eraserPathWorld.add(wx); eraserPathWorld.add(wy)
                listener.invalidate()
                return true
            }
            shapeStart != null -> {
                shapeCur = floatArrayOf(wx, wy)
                listener.invalidate()
                return true
            }
            rectSelectionLive != null -> {
                val r = rectSelectionLive!!
                r[2] = wx; r[3] = wy
                listener.invalidate()
                return true
            }
            lassoPoints != null -> {
                lassoPoints?.add(wx); lassoPoints?.add(wy)
                listener.invalidate()
                return true
            }
            currentLaser != null -> {
                currentLaser?.points?.add(screenX)
                currentLaser?.points?.add(screenY)
                listener.invalidate()
                return true
            }
            textSelection.isActive && !isStylus && activeTool == ToolType.SELECT_TEXT && movedBeyondSlop -> {
                // drag over text with select tool extends selection
                extendSelectionTo(wx, wy)
                return true
            }
        }
        return false
    }

    fun onActionUp(screenX: Float, screenY: Float, isStylus: Boolean): Boolean {
        val wx = state.transform.screenToWorldX(screenX)
        val wy = state.transform.screenToWorldY(screenY)

        if (draggingTextHandle != 0) {
            draggingTextHandle = 0
            listener.onTextMenuVisibility(true)
            return true
        }
        if (movingSelection) {
            commitSelectionMove()
            return true
        }
        if (scalingSelection) {
            commitSelectionScale()
            return true
        }
        if (rotatingSelection) {
            commitSelectionRotation()
            return true
        }

        when {
            livePoints != null -> finishStroke()
            activeTool == ToolType.ERASER && (isStylus || !palmRejection || fingerAction != FingerAction.SCROLL) -> finishEraser()
            shapeStart != null -> finishShape()
            rectSelectionLive != null -> finishRectSelection()
            lassoPoints != null -> finishLasso()
            currentLaser != null -> { currentLaser = null }
            activeTool == ToolType.SELECT_OBJECT && !movedBeyondSlop -> tapSelectObject(wx, wy)
            (activeTool == ToolType.TEXT || activeTool == ToolType.STICKY_NOTE) && !movedBeyondSlop -> {
                tapInsertText(wx, wy, activeTool == ToolType.STICKY_NOTE)
            }
            else -> return false
        }
        listener.invalidate()
        return true
    }

    fun onLongPress(screenX: Float, screenY: Float) {
        val wx = state.transform.screenToWorldX(screenX)
        val wy = state.transform.screenToWorldY(screenY)
        // Long-press anywhere selects PDF text (works with finger per spec).
        if (!textSelection.isActive) {
            selectTextAt(wx, wy, silentOnMiss = true)
        }
    }

    fun onDoubleTap(screenX: Float, screenY: Float): Boolean {
        // handled by view for zoom
        return false
    }

    private fun angleAt(screenX: Float, screenY: Float): Float =
        atan2(screenY - state.transform.worldToScreenY(rotateCenter.y), screenX - state.transform.worldToScreenX(rotateCenter.x))

    // =====================================================================
    // Stroke capture (§2 / §4)
    // =====================================================================

    private fun beginStroke(wx: Float, wy: Float, pressure: Float, tilt: Float, nanos: Long, tool: ToolType) {
        livePage = pageForWorldY(wy)
        liveTool = tool
        val style = toolConfig.inkStyleOf(tool)
        liveWidth = style.width
        val p = if (pressureEnabled) pressure.coerceIn(0.05f, 1f) else 1f
        lastEventNanos = nanos
        liveVelocity = -1f
        livePoints = arrayListOf(StrokePoint(wx, wy, p, tilt, nanos))
    }

    private fun appendStrokePoint(wx: Float, wy: Float, pressure: Float, tilt: Float, nanos: Long) {
        val pts = livePoints ?: return
        val last = pts.lastOrNull() ?: return
        if (hypot(wx - last.x, wy - last.y) < MIN_POINT_DIST && pts.size > 1) {
            // still refresh pressure on the existing point (stylus pressure changes while hovering still)
            if (pressureEnabled) {
                pts[pts.size - 1] = last.copy(p = pressure.coerceIn(0.05f, 1f), tilt = tilt, t = nanos)
            }
            return
        }
        if (pts.size >= 6000) return

        // live velocity (world units / second) for the fountain engine
        val dt = if (nanos > 0 && last.t > 0) (nanos - last.t) else 0L
        val d = hypot(wx - last.x, wy - last.y)
        liveVelocity = if (dt > 0) d / dt * 1e9f else -1f

        // light exponential smoothing while capturing (per-tool strength)
        val style = toolConfig.inkStyleOf(liveTool)
        var px = wx
        var py = wy
        if (style.smoothing > 0.05f && pts.isNotEmpty()) {
            val smoothed = StrokeOps.smoothPoint(last.x, last.y, wx, wy, style.smoothing * 0.35f)
            px = smoothed[0]; py = smoothed[1]
        }
        val p = if (pressureEnabled) pressure.coerceIn(0.05f, 1f) else 1f
        pts.add(StrokePoint(px, py, p, tilt, nanos))
    }

    private fun finishStroke() {
        val raw = livePoints ?: return
        livePoints = null
        if (raw.isEmpty()) return
        val cfg = toolConfig
        val style = cfg.inkStyleOf(liveTool)

        // §14 — deterministic shape recognition on ink strokes
        if (cfg.shapeRecognition && liveTool != ToolType.HIGHLIGHTER && raw.size >= 8) {
            val recognized = ShapeRecognition.recognize(raw)
            if (recognized != null && recognized.confidence >= ShapeRecognition.MIN_CONFIDENCE) {
                val shape = ShapeElement(
                    id = newId(),
                    page = livePage,
                    kind = recognized.kind,
                    color = style.color,
                    width = maxOf(2f, style.width * 0.9f),
                    opacity = style.opacity,
                    x1 = recognized.x1, y1 = recognized.y1, x2 = recognized.x2, y2 = recognized.y2,
                    fillColor = cfg.shapeFillColor,
                    filled = cfg.shapeFilled,
                    vertices = recognized.vertices,
                    timestamp = System.currentTimeMillis()
                )
                listener.commit(doc.addShapeCmd(shape))
                listener.invalidate()
                return
            }
        }

        // smoothing on commit (§4 natural interpolation)
        val smoothed = StrokeOps.smoothCommitted(raw, style.smoothing)
        val stroke = Stroke(
            id = newId(),
            page = livePage,
            tool = liveTool,
            color = style.color,
            width = style.width,
            opacity = style.opacity,
            points = smoothed,
            timestamp = System.currentTimeMillis(),
            pressureResponse = style.pressureResponse
        )
        listener.commit(doc.addStrokeCmd(stroke))
        listener.invalidate()
    }

    private fun pageForWorldY(wy: Float): Int {
        if (state.infiniteMode) return 0
        val p = state.layout.pageAtY(wy)
        if (p >= 0) return p
        return state.layout.nearestPage(wy)
    }

    // =====================================================================
    // Eraser (§6)
    // =====================================================================

    private fun eraserRadiusWorld(): Float =
        (toolConfig.eraserSize / 2f) / state.transform.scale

    private fun eraseAt(wx: Float, wy: Float) {
        val r = eraserRadiusWorld()
        val margin = r + 40f // widest stroke safety margin
        when (toolConfig.eraserMode) {
            EraserMode.STROKE, EraserMode.OBJECT -> {
                for (s in strokesNear(wx - margin, wy - margin, wx + margin, wy + margin)) {
                    if (s in erasedStrokes) continue
                    if (s.page != pageForWorldY(wy) && !state.infiniteMode) continue
                    val hit = distToPolyline(wx, wy, s.points) < r + s.width / 2f
                    if (hit) erasedStrokes.add(s)
                }
                if (toolConfig.eraserMode == EraserMode.OBJECT) {
                    hitTextElement(wx, wy)?.let { if (it !in erasedTexts) erasedTexts.add(it) }
                    hitImageElement(wx, wy)?.let { if (it !in erasedImages) erasedImages.add(it) }
                    hitShapeElement(wx, wy)?.let { if (it !in erasedShapes) erasedShapes.add(it) }
                    hitAnnotation(wx, wy)?.let {
                        when (it) {
                            is SelectedElement.SelHighlight -> if (it.ann !in erasedHighlights) erasedHighlights.add(it.ann)
                            is SelectedElement.SelUnderline -> if (it.ann !in erasedUnderlines) erasedUnderlines.add(it.ann)
                            else -> {}
                        }
                    }
                }
            }
            EraserMode.PIXEL -> { /* computed on up via eraserPathWorld */ }
        }
    }

    private val erasedTexts = ArrayList<TextElement>()
    private val erasedImages = ArrayList<ImageElement>()
    private val erasedShapes = ArrayList<ShapeElement>()
    private val erasedHighlights = ArrayList<HighlightAnnotation>()
    private val erasedUnderlines = ArrayList<UnderlineAnnotation>()

    private fun finishEraser() {
        when (toolConfig.eraserMode) {
            EraserMode.STROKE -> {
                if (erasedStrokes.isNotEmpty()) {
                    listener.commit(doc.removeStrokesCmd(ArrayList(erasedStrokes)))
                }
            }
            EraserMode.OBJECT -> {
                if (erasedStrokes.isNotEmpty()) listener.commit(doc.removeStrokesCmd(ArrayList(erasedStrokes)))
                if (erasedTexts.isNotEmpty()) listener.commit(doc.removeTextCmd(ArrayList(erasedTexts)))
                if (erasedImages.isNotEmpty()) listener.commit(doc.removeImagesCmd(ArrayList(erasedImages)))
                if (erasedShapes.isNotEmpty()) listener.commit(doc.removeShapesCmd(ArrayList(erasedShapes)))
                if (erasedHighlights.isNotEmpty() || erasedUnderlines.isNotEmpty()) {
                    listener.commit(doc.removeAnnotationsCmd(ArrayList(erasedHighlights), ArrayList(erasedUnderlines)))
                }
                erasedTexts.clear(); erasedImages.clear(); erasedShapes.clear()
                erasedHighlights.clear(); erasedUnderlines.clear()
            }
            EraserMode.PIXEL -> partialErase()
        }
        erasedStrokes.clear()
        eraserPathWorld.clear()
    }

    /**
     * Partial erase: vector split with boundary interpolation (§6).
     * Never rasterizes; remaining geometry (coords, pressure, tilt, timestamps)
     * is preserved bit-exactly outside the erased capsules.
     */
    private fun partialErase() {
        val r = eraserRadiusWorld()
        if (eraserPathWorld.size < 2) return
        val margin = r + 40f
        val path = eraserPathWorld.toFloatArray()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var k = 0
        while (k < path.size) {
            minX = min(minX, path[k]); maxX = max(maxX, path[k])
            minY = min(minY, path[k + 1]); maxY = max(maxY, path[k + 1])
            k += 2
        }
        val candidates = strokesNear(minX - margin, minY - margin, maxX + margin, maxY + margin)
        var changed = false
        for (s in candidates) {
            // sequentially split the stroke against every eraser capsule segment
            var pieces: MutableList<Stroke> = arrayListOf(s)
            var k2 = 0
            while (k2 + 3 < path.size && pieces.isNotEmpty()) {
                val ex0 = path[k2]; val ey0 = path[k2 + 1]
                val ex1 = path[k2 + 2]; val ey1 = path[k2 + 3]
                val next = ArrayList<Stroke>()
                for (piece in pieces) {
                    val kept = StrokeOps.splitStrokeAgainstCapsule(piece, ex0, ey0, ex1, ey1, r)
                    if (kept == null) {
                        next.add(piece)
                    } else {
                        for (seg in kept) {
                            if (seg.size >= 2) {
                                next.add(piece.copy(id = newId(), points = seg))
                            }
                        }
                    }
                }
                pieces = next
                k2 += 2
            }
            if (pieces.size != 1 || pieces[0].id != s.id) {
                listener.commit(doc.replaceStrokeCmd(s, pieces))
                changed = true
            }
        }
        if (changed) listener.invalidate()
    }

    // =====================================================================
    // Shapes / lasso / rect selection (§11)
    // =====================================================================

    private fun finishShape() {
        val st = shapeStart ?: return
        val cur = shapeCur ?: run { shapeStart = null; return }
        shapeStart = null
        val page = pageForWorldY((st[1] + cur[1]) / 2f)
        val cfg = toolConfig
        var ex = cur[0]
        var ey = cur[1]
        // §14 snap to geometry: 0/45/90° axes for lines/arrows
        if (cfg.shapeSnap && (cfg.shapeKind == ShapeKind.LINE || cfg.shapeKind == ShapeKind.ARROW || cfg.shapeKind == ShapeKind.DOUBLE_ARROW)) {
            val snapped = StrokeOps.snapDirectional(st[0], st[1], ex, ey)
            ex = snapped[0]; ey = snapped[1]
        }
        if (hypot(ex - st[0], ey - st[1]) > 4f / state.transform.scale) {
            val shape = ShapeElement(
                id = newId(), page = page, kind = cfg.shapeKind,
                color = cfg.shapeColor, width = cfg.shapeWidth, opacity = cfg.shapeOpacity,
                x1 = st[0], y1 = st[1], x2 = ex, y2 = ey,
                fillColor = cfg.shapeFillColor, filled = cfg.shapeFilled,
                timestamp = System.currentTimeMillis()
            )
            listener.commit(doc.addShapeCmd(shape))
        }
        shapeCur = null
    }

    private fun finishLasso() {
        val pts = lassoPoints ?: return
        lassoPoints = null
        if (pts.size < 6) return
        val poly = pts.toFloatArray()
        val sel = ArrayList<SelectedElement>()
        var strokeCount = 0; var textCount = 0
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var k = 0
        while (k < poly.size) {
            minX = min(minX, poly[k]); maxX = max(maxX, poly[k])
            minY = min(minY, poly[k + 1]); maxY = max(maxY, poly[k + 1])
            k += 2
        }
        for (s in strokesNear(minX, minY, maxX, maxY)) {
            val geometric = StrokeOps.strokeIntersectsPolygon(s, poly)
            if (geometric) {
                // include whole group when the stroke is grouped
                if (s.groupId != null) {
                    addGroupMembers(s.groupId!!, sel)
                } else {
                    sel.add(SelectedElement.SelStroke(s))
                }
                strokeCount++
            }
        }
        for (t in doc.texts) {
            val h = renderer.measureTextHeight(t).toFloat()
            if (rectIntersectsPolygon(floatArrayOf(t.x, t.y, t.x + t.width, t.y + h), poly)) {
                if (t.groupId != null) addGroupMembers(t.groupId!!, sel) else sel.add(SelectedElement.SelText(t))
                textCount++
            }
        }
        for (i in doc.images) {
            if (rectIntersectsPolygon(floatArrayOf(i.x, i.y, i.x + i.w, i.y + i.h), poly)) {
                if (i.groupId != null) addGroupMembers(i.groupId!!, sel) else sel.add(SelectedElement.SelImage(i))
            }
        }
        for (sh in doc.shapes) {
            if (rectIntersectsPolygon(sh.bbox(), poly)) {
                if (sh.groupId != null) addGroupMembers(sh.groupId!!, sel) else sel.add(SelectedElement.SelShape(sh))
            }
        }
        finishSelectionBuild(sel, strokeCount, textCount)
    }

    private fun finishRectSelection() {
        val r = rectSelectionLive ?: return
        rectSelectionLive = null
        val rect = floatArrayOf(min(r[0], r[2]), min(r[1], r[3]), max(r[0], r[2]), max(r[1], r[3]))
        val sel = ArrayList<SelectedElement>()
        var strokeCount = 0; var textCount = 0
        for (s in strokesNear(rect[0], rect[1], rect[2], rect[3])) {
            if (StrokeOps.strokeIntersectsRect(s, rect)) {
                if (s.groupId != null) addGroupMembers(s.groupId!!, sel) else sel.add(SelectedElement.SelStroke(s))
                strokeCount++
            }
        }
        for (t in doc.texts) {
            val h = renderer.measureTextHeight(t).toFloat()
            if (rectsOverlap(floatArrayOf(t.x, t.y, t.x + t.width, t.y + h), rect)) {
                if (t.groupId != null) addGroupMembers(t.groupId!!, sel) else sel.add(SelectedElement.SelText(t))
                textCount++
            }
        }
        for (i in doc.images) {
            if (rectsOverlap(floatArrayOf(i.x, i.y, i.x + i.w, i.y + i.h), rect)) {
                if (i.groupId != null) addGroupMembers(i.groupId!!, sel) else sel.add(SelectedElement.SelImage(i))
            }
        }
        for (sh in doc.shapes) {
            if (rectsOverlap(sh.bbox(), rect)) {
                if (sh.groupId != null) addGroupMembers(sh.groupId!!, sel) else sel.add(SelectedElement.SelShape(sh))
            }
        }
        finishSelectionBuild(sel, strokeCount, textCount)
    }

    private fun finishSelectionBuild(sel: List<SelectedElement>, strokeCount: Int, textCount: Int) {
        if (sel.isNotEmpty()) {
            objectSelection.set(sel)
            syncSelectedIds()
            listener.onObjectMenuVisibility(true)
            listener.onSelectionCountChanged(strokeCount, textCount)
        }
    }

    /** Pulls in every element sharing [groupId] (group selection semantics). */
    private fun addGroupMembers(groupId: String, out: ArrayList<SelectedElement>) {
        for (s in doc.strokes) if (s.groupId == groupId && out.none { it is SelectedElement.SelStroke && it.stroke.id == s.id }) out.add(SelectedElement.SelStroke(s))
        for (t in doc.texts) if (t.groupId == groupId && out.none { it is SelectedElement.SelText && it.text.id == t.id }) out.add(SelectedElement.SelText(t))
        for (i in doc.images) if (i.groupId == groupId && out.none { it is SelectedElement.SelImage && it.image.id == i.id }) out.add(SelectedElement.SelImage(i))
        for (sh in doc.shapes) if (sh.groupId == groupId && out.none { it is SelectedElement.SelShape && it.shape.id == sh.id }) out.add(SelectedElement.SelShape(sh))
    }

    private fun rectIntersectsPolygon(rect: FloatArray, poly: FloatArray): Boolean {
        // any rect corner inside, or any rect edge crossing a polygon edge
        if (pointInPolygon(rect[0], rect[1], poly)) return true
        if (pointInPolygon(rect[2], rect[1], poly)) return true
        if (pointInPolygon(rect[2], rect[3], poly)) return true
        if (pointInPolygon(rect[0], rect[3], poly)) return true
        val edges = arrayOf(
            floatArrayOf(rect[0], rect[1], rect[2], rect[1]),
            floatArrayOf(rect[2], rect[1], rect[2], rect[3]),
            floatArrayOf(rect[2], rect[3], rect[0], rect[3]),
            floatArrayOf(rect[0], rect[3], rect[0], rect[1])
        )
        val n = poly.size / 2
        for (e in edges) {
            for (i in 0 until n) {
                val j = (i + 1) % n
                if (StrokeOps.segmentsIntersect(e[0], e[1], e[2], e[3], poly[i * 2], poly[i * 2 + 1], poly[j * 2], poly[j * 2 + 1])) return true
            }
        }
        return false
    }

    private fun rectsOverlap(a: FloatArray, b: FloatArray): Boolean =
        a[0] <= b[2] && a[2] >= b[0] && a[1] <= b[3] && a[3] >= b[1]

    private fun tapSelectObject(wx: Float, wy: Float) {
        // annotation hit first (topmost interaction layer)
        hitAnnotation(wx, wy)?.let {
            selectItems(listOf(it)); return
        }
        hitImageElement(wx, wy)?.let { selectWithGroup(it.groupId) { selectItems(listOf(SelectedElement.SelImage(it))) }; return }
        hitTextElement(wx, wy)?.let { selectWithGroup(it.groupId) { selectItems(listOf(SelectedElement.SelText(it))) }; return }
        hitShapeElement(wx, wy)?.let { selectWithGroup(it.groupId) { selectItems(listOf(SelectedElement.SelShape(it))) }; return }
        // strokes last (topmost drawing)
        val hits = ArrayList<SelectedElement>()
        val tolerance = 8f / state.transform.scale
        for (s in strokesNear(wx - 60f, wy - 60f, wx + 60f, wy + 60f)) {
            if (distToPolyline(wx, wy, s.points) < s.width / 2f + tolerance) {
                hits.add(SelectedElement.SelStroke(s))
            }
        }
        if (hits.isNotEmpty()) {
            val topmost = hits.last().let { if (it is SelectedElement.SelStroke) it.stroke else return }
            selectWithGroup(topmost.groupId) { selectItems(listOf(SelectedElement.SelStroke(topmost))) }
            return
        }
        objectSelection.clear()
        syncSelectedIds()
        listener.onObjectMenuVisibility(false)
    }

    private inline fun selectWithGroup(groupId: String?, crossinline plain: () -> Unit) {
        if (groupId != null) {
            val sel = ArrayList<SelectedElement>()
            addGroupMembers(groupId, sel)
            if (sel.isNotEmpty()) {
                selectItems(sel)
                return
            }
        }
        plain()
    }

    private fun selectItems(items: List<SelectedElement>) {
        objectSelection.set(items)
        syncSelectedIds()
        listener.onObjectMenuVisibility(true)
        listener.onSelectionCountChanged(
            items.count { it is SelectedElement.SelStroke },
            items.count { it is SelectedElement.SelText }
        )
        listener.invalidate()
    }

    private fun syncSelectedIds() {
        selectedIds = HashSet()
        for (it in objectSelection.items) {
            when (it) {
                is SelectedElement.SelStroke -> selectedIds.add(it.stroke.id)
                is SelectedElement.SelText -> selectedIds.add(it.text.id)
                is SelectedElement.SelImage -> selectedIds.add(it.image.id)
                is SelectedElement.SelShape -> selectedIds.add(it.shape.id)
                is SelectedElement.SelHighlight -> selectedIds.add(it.ann.id)
                is SelectedElement.SelUnderline -> selectedIds.add(it.ann.id)
            }
        }
    }

    fun isSelected(id: String): Boolean = id in selectedIds

    /** Resolves an ImageElement relative path to a File (used by the view). */
    fun imageResolverFor(relPath: String): File? = imageResolver(relPath)

    // =====================================================================
    // Selection move / scale / rotate commits (§11)
    // =====================================================================

    private fun commitSelectionMove() {
        movingSelection = false
        val dx = objectSelection.dragAccumX
        val dy = objectSelection.dragAccumY
        objectSelection.dragAccumX = 0f; objectSelection.dragAccumY = 0f
        if (kotlin.math.abs(dx) < 0.1f && kotlin.math.abs(dy) < 0.1f) return

        val strokeMoves = ArrayList<Pair<Stroke, FloatArray>>()
        val textMoves = ArrayList<Pair<TextElement, FloatArray>>()
        val shapeMoves = ArrayList<Pair<ShapeElement, FloatArray>>()
        val imageMoves = ArrayList<Pair<ImageElement, FloatArray>>()
        for (it in objectSelection.items) {
            when (it) {
                is SelectedElement.SelStroke -> strokeMoves.add(it.stroke to floatArrayOf(dx, dy))
                is SelectedElement.SelText -> textMoves.add(it.text to floatArrayOf(dx, dy))
                is SelectedElement.SelShape -> shapeMoves.add(it.shape to floatArrayOf(dx, dy))
                is SelectedElement.SelImage -> imageMoves.add(it.image to floatArrayOf(dx, dy))
                else -> {}
            }
        }
        if (strokeMoves.isNotEmpty()) listener.commit(doc.moveStrokesCmd(strokeMoves))
        if (textMoves.isNotEmpty()) listener.commit(doc.moveTextsCmd(textMoves))
        if (shapeMoves.isNotEmpty()) listener.commit(doc.moveShapesCmd(shapeMoves))
        if (imageMoves.isNotEmpty()) listener.commit(doc.moveImagesCmd(imageMoves))
        listener.invalidate()
    }

    private fun commitSelectionScale() {
        scalingSelection = false
        val bbox = scaleStartBBox
        val sx = objectSelection.liveScaleX
        val sy = objectSelection.liveScaleY
        objectSelection.liveScaleX = 1f; objectSelection.liveScaleY = 1f
        scaleStartBBox = null
        scaleStartElements = emptyList()
        if (bbox == null) return
        if (kotlin.math.abs(sx - 1f) < 0.01f && kotlin.math.abs(sy - 1f) < 0.01f) return
        applySelectionScale(sx, sy, scaleAnchorWorld.x, scaleAnchorWorld.y)
        listener.invalidate()
    }

    /** Scales every selected element about (ax, ay) — strokes included (§8 resize). */
    fun applySelectionScale(sx: Float, sy: Float, ax: Float, ay: Float) {
        val strokePairs = ArrayList<Pair<Stroke, Stroke>>()
        val textPairs = ArrayList<Pair<TextElement, TextElement>>()
        val shapePairs = ArrayList<Pair<ShapeElement, ShapeElement>>()
        val imagePairs = ArrayList<Pair<ImageElement, ImageElement>>()
        for (it in objectSelection.items) {
            when (it) {
                is SelectedElement.SelStroke -> strokePairs.add(it.stroke to StrokeOps.scaleStroke(it.stroke, ax, ay, sx, sy))
                is SelectedElement.SelText -> {
                    val t = it.text
                    textPairs.add(t to t.copy(
                        x = ax + (t.x - ax) * sx,
                        y = ay + (t.y - ay) * sy,
                        size = (t.size * ((sx + sy) / 2f)).coerceIn(4f, 400f),
                        width = (t.width * sx).coerceAtLeast(24f)
                    ))
                }
                is SelectedElement.SelShape -> {
                    val s = it.shape
                    shapePairs.add(s to s.copy(
                        x1 = ax + (s.x1 - ax) * sx, y1 = ay + (s.y1 - ay) * sy,
                        x2 = ax + (s.x2 - ax) * sx, y2 = ay + (s.y2 - ay) * sy,
                        width = (s.width * ((sx + sy) / 2f)).coerceIn(0.2f, 200f)
                    ))
                }
                is SelectedElement.SelImage -> {
                    val i = it.image
                    imagePairs.add(i to i.copy(
                        x = ax + (i.x - ax) * sx, y = ay + (i.y - ay) * sy,
                        w = (i.w * sx).coerceAtLeast(8f), h = (i.h * sy).coerceAtLeast(8f)
                    ))
                }
                else -> {}
            }
        }
        if (strokePairs.isNotEmpty()) listener.commit(doc.transformStrokesCmd(strokePairs))
        if (textPairs.isNotEmpty()) listener.commit(doc.transformTextsCmd(textPairs))
        if (shapePairs.isNotEmpty()) listener.commit(doc.transformShapesCmd(shapePairs))
        if (imagePairs.isNotEmpty()) listener.commit(doc.transformImagesCmd(imagePairs))
    }

    private fun commitSelectionRotation() {
        rotatingSelection = false
        val angle = objectSelection.liveRotation
        objectSelection.liveRotation = 0f
        if (kotlin.math.abs(angle) < 0.5f) return
        applySelectionRotation(rotateCenter.x, rotateCenter.y, angle)
        listener.invalidate()
    }

    /** Rotates every selected element about (cx, cy) — strokes included (§8 rotate). */
    fun applySelectionRotation(cx: Float, cy: Float, angleDeg: Float) {
        val strokePairs = ArrayList<Pair<Stroke, Stroke>>()
        val textPairs = ArrayList<Pair<TextElement, TextElement>>()
        val shapePairs = ArrayList<Pair<ShapeElement, ShapeElement>>()
        val imagePairs = ArrayList<Pair<ImageElement, ImageElement>>()
        for (it in objectSelection.items) {
            when (it) {
                is SelectedElement.SelStroke -> strokePairs.add(it.stroke to StrokeOps.rotateStroke(it.stroke, cx, cy, angleDeg))
                is SelectedElement.SelText -> textPairs.add(it.text to it.text.copy(rotation = it.text.rotation + angleDeg))
                is SelectedElement.SelShape -> shapePairs.add(it.shape to it.shape.copy(rotation = it.shape.rotation + angleDeg))
                is SelectedElement.SelImage -> imagePairs.add(it.image to it.image.copy(rotation = it.image.rotation + angleDeg))
                else -> {}
            }
        }
        if (strokePairs.isNotEmpty()) listener.commit(doc.transformStrokesCmd(strokePairs))
        if (textPairs.isNotEmpty()) listener.commit(doc.transformTextsCmd(textPairs))
        if (shapePairs.isNotEmpty()) listener.commit(doc.transformShapesCmd(shapePairs))
        if (imagePairs.isNotEmpty()) listener.commit(doc.transformImagesCmd(imagePairs))
    }

    private fun bboxWorldToScreen(b: FloatArray): FloatArray = floatArrayOf(
        state.transform.worldToScreenX(b[0]), state.transform.worldToScreenY(b[1]),
        state.transform.worldToScreenX(b[2]), state.transform.worldToScreenY(b[3])
    )

    /** Live-transform pivots exposed for the view's ghost rendering (§11). */
    val ghostAnchor: PointF get() = scaleAnchorWorld
    val ghostRotateCenter: PointF get() = rotateCenter

    // =====================================================================
    // Clipboard + grouping (§8)
    // =====================================================================

    /** True when the current selection can be grouped (2+ ungrouped elements). */
    val canGroup: Boolean
        get() = objectSelection.items.size >= 2 && objectSelection.items.any { groupOf(it) == null }

    /** True when the current selection contains at least one grouped element. */
    val canUngroup: Boolean
        get() = objectSelection.items.any { groupOf(it) != null }

    private fun groupOf(item: SelectedElement): String? = when (item) {
        is SelectedElement.SelStroke -> item.stroke.groupId
        is SelectedElement.SelText -> item.text.groupId
        is SelectedElement.SelImage -> item.image.groupId
        is SelectedElement.SelShape -> item.shape.groupId
        else -> null
    }

    fun groupSelection(): Boolean {
        if (!canGroup) return false
        val sIds = HashSet<String>(); val tIds = HashSet<String>()
        val iIds = HashSet<String>(); val shIds = HashSet<String>()
        for (it in objectSelection.items) {
            when (it) {
                is SelectedElement.SelStroke -> sIds.add(it.stroke.id)
                is SelectedElement.SelText -> tIds.add(it.text.id)
                is SelectedElement.SelImage -> iIds.add(it.image.id)
                is SelectedElement.SelShape -> shIds.add(it.shape.id)
                else -> {}
            }
        }
        listener.commit(doc.groupCmd(sIds, tIds, iIds, shIds))
        listener.invalidate()
        return true
    }

    fun ungroupSelection(): Boolean {
        val gid = objectSelection.items.firstNotNullOfOrNull { groupOf(it) } ?: return false
        listener.commit(doc.ungroupCmd(gid))
        listener.invalidate()
        return true
    }

    /** Copies the object selection into the process-wide clipboard (§8). */
    fun copySelectionObjects(docId: String): Boolean {
        if (!objectSelection.isActive) return false
        val ss = ArrayList<Stroke>(); val ts = ArrayList<TextElement>()
        val imgs = ArrayList<ImageElement>(); val shs = ArrayList<ShapeElement>()
        for (it in objectSelection.items) {
            when (it) {
                is SelectedElement.SelStroke -> ss.add(it.stroke)
                is SelectedElement.SelText -> ts.add(it.text)
                is SelectedElement.SelImage -> imgs.add(it.image)
                is SelectedElement.SelShape -> shs.add(it.shape)
                else -> {}
            }
        }
        if (ss.isEmpty() && ts.isEmpty() && imgs.isEmpty() && shs.isEmpty()) return false
        EditorClipboard.copy(EditorClipboard.Elements(docId, ss, ts, imgs, shs))
        return true
    }

    /** Copy + delete in one step (§8 cut). */
    fun cutSelectionObjects(docId: String): Boolean {
        val copied = copySelectionObjects(docId)
        if (copied) deleteSelection()
        return copied
    }

    /**
     * Pastes the clipboard centered on the current viewport (§8).
     * Images from another document are dropped (files are doc-scoped).
     */
    fun pasteClipboard(docId: String): Boolean {
        val clip = EditorClipboard.content ?: return false
        if (clip.isEmpty) return false
        val focusWorldX = state.transform.screenToWorldX(state.viewportWidth / 2f)
        val focusWorldY = state.transform.screenToWorldY(state.viewportHeight / 2f)

        // bbox of clipboard content (for re-centering)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (s in clip.strokes) { val b = s.bbox(); minX = min(minX, b[0]); minY = min(minY, b[1]); maxX = max(maxX, b[2]); maxY = max(maxY, b[3]) }
        for (t in clip.texts) { val h = renderer.measureTextHeight(t).toFloat(); minX = min(minX, t.x); minY = min(minY, t.y); maxX = max(maxX, t.x + t.width); maxY = max(maxY, t.y + h) }
        for (i in clip.images) { minX = min(minX, i.x); minY = min(minY, i.y); maxX = max(maxX, i.x + i.w); maxY = max(maxY, i.y + i.h) }
        for (s in clip.shapes) { val b = s.bbox(); minX = min(minX, b[0]); minY = min(minY, b[1]); maxX = max(maxX, b[2]); maxY = max(maxY, b[3]) }
        if (minX > maxX) return false
        val dx = focusWorldX - (minX + maxX) / 2f
        val dy = focusWorldY - (minY + maxY) / 2f
        val page = pageForWorldY(focusWorldY)

        val newStrokes = clip.strokes.map { it.copy(id = newId(), page = page, timestamp = System.currentTimeMillis(), groupId = null, points = it.points.map { p -> p.copy(x = p.x + dx, y = p.y + dy) }) }
        val newTexts = clip.texts.map { it.copy(id = newId(), page = page, x = it.x + dx, y = it.y + dy, groupId = null, timestamp = System.currentTimeMillis()) }
        val newImages = if (clip.docId == docId) {
            clip.images.map { it.copy(id = newId(), page = page, x = it.x + dx, y = it.y + dy, groupId = null) }
        } else {
            emptyList() // cross-document image paste needs file copies — dropped for now
        }
        val newShapes = clip.shapes.map { it.copy(id = newId(), page = page, x1 = it.x1 + dx, y1 = it.y1 + dy, x2 = it.x2 + dx, y2 = it.y2 + dy, groupId = null, timestamp = System.currentTimeMillis()) }

        listener.commit(doc.addElementsCmd(newStrokes, newTexts, newImages, newShapes))
        // select the pasted content
        val sel = ArrayList<SelectedElement>()
        for (s in newStrokes) sel.add(SelectedElement.SelStroke(s))
        for (t in newTexts) sel.add(SelectedElement.SelText(t))
        for (i in newImages) sel.add(SelectedElement.SelImage(i))
        for (s in newShapes) sel.add(SelectedElement.SelShape(s))
        objectSelection.set(sel)
        syncSelectedIds()
        listener.onObjectMenuVisibility(true)
        listener.invalidate()
        return true
    }

    fun deleteSelection() {
        val strokes = ArrayList<Stroke>()
        val texts = ArrayList<TextElement>()
        val images = ArrayList<ImageElement>()
        val shapes = ArrayList<ShapeElement>()
        val highs = ArrayList<HighlightAnnotation>()
        val unders = ArrayList<UnderlineAnnotation>()
        for (it in objectSelection.items) {
            when (it) {
                is SelectedElement.SelStroke -> strokes.add(it.stroke)
                is SelectedElement.SelText -> texts.add(it.text)
                is SelectedElement.SelImage -> images.add(it.image)
                is SelectedElement.SelShape -> shapes.add(it.shape)
                is SelectedElement.SelHighlight -> highs.add(it.ann)
                is SelectedElement.SelUnderline -> unders.add(it.ann)
            }
        }
        if (strokes.isNotEmpty()) listener.commit(doc.removeStrokesCmd(strokes))
        if (texts.isNotEmpty()) listener.commit(doc.removeTextCmd(texts))
        if (images.isNotEmpty()) listener.commit(doc.removeImagesCmd(images))
        if (shapes.isNotEmpty()) listener.commit(doc.removeShapesCmd(shapes))
        if (highs.isNotEmpty() || unders.isNotEmpty()) listener.commit(doc.removeAnnotationsCmd(highs, unders))
        objectSelection.clear()
        syncSelectedIds()
        listener.onObjectMenuVisibility(false)
        listener.invalidate()
    }

    // =====================================================================
    // Text selection
    // =====================================================================

    private fun hitTextHandle(screenX: Float, screenY: Float): Int {
        val pos = textSelection.handlePositions() ?: return 0
        val page = textSelection.pageIndex
        if (page < 0) return 0
        val layout = state.layout
        val h1x = state.transform.worldToScreenX(layout.pageLeft(page) + pos.first[0])
        val h1y = state.transform.worldToScreenY(layout.pageTop(page) + pos.first[1])
        val h2x = state.transform.worldToScreenX(layout.pageLeft(page) + pos.second[0])
        val h2y = state.transform.worldToScreenY(layout.pageTop(page) + pos.second[1])
        val r = 30f
        val d1 = distScreen(screenX, screenY, h1x, h1y)
        val d2 = distScreen(screenX, screenY, h2x, h2y)
        return when {
            d1 < r && d1 <= d2 -> 1
            d2 < r -> 2
            else -> 0
        }
    }

    private fun dragTextHandleTo(wx: Float, wy: Float) {
        val page = textSelection.pageIndex
        if (page < 0) return
        val px = wx - state.layout.pageLeft(page)
        val py = wy - state.layout.pageTop(page)
        val word = textSelection.wordAt(px, py) ?: return
        textSelection.extendTo(word)
        listener.invalidate()
    }

    private fun extendSelectionTo(wx: Float, wy: Float) {
        val page = textSelection.pageIndex
        if (page < 0) return
        val px = wx - state.layout.pageLeft(page)
        val py = wy - state.layout.pageTop(page)
        val word = textSelection.wordAt(px, py) ?: return
        textSelection.extendTo(word)
        listener.invalidate()
    }

    /**
     * Long-press / select-tool entry: finds the word at world position.
     * silentOnMiss=true (long-press): empty area or non-text → silent cancel
     * (spec: NO menu, NO stray stroke).
     */
    private var textJob: Job? = null

    fun selectTextAt(wx: Float, wy: Float, silentOnMiss: Boolean = false) {
        val pageIdx = state.layout.pageAtY(wy)
        if (pageIdx < 0) {
            if (!silentOnMiss) clearTextSelection()
            return
        }
        val pdfPage = pdfPageOf(pageIdx)
        if (pdfPage < 0 || textExtractor == null) {
            if (!silentOnMiss) clearTextSelection()
            return
        }
        val px = wx - state.layout.pageLeft(pageIdx)
        val py = wy - state.layout.pageTop(pageIdx)
        textJob?.cancel()
        textJob = scope.launch {
            if (!textExtractor.isAttached) {
                listener.onTextPreparing()
            }
            val size = state.layout.let { PointF(it.pageWidth(pageIdx), it.pageHeight(pageIdx)) }
            val pageText = textExtractor.pageText(pdfPage, size)
            val word: PdfWord? = pageText?.wordNear(px, py)
            if (pageText != null && word != null) {
                textSelection.begin(pdfPage, pageText, word)
                listener.onTextMenuVisibility(true)
                listener.invalidate()
            } else {
                if (!silentOnMiss) clearTextSelection()
            }
        }
    }

    fun clearTextSelection() {
        textSelection.clear()
        listener.onTextMenuVisibility(false)
        listener.invalidate()
    }

    /** "Select all" action from the context menu. */
    fun selectAllText() {
        val page = textSelection.pageIndex
        if (page >= 0) {
            textSelection.selectAll()
            listener.invalidate()
        } else {
            // no selection yet — use current page
            val pageIdx = state.currentPage()
            val pdfPage = pdfPageOf(pageIdx)
            if (pdfPage >= 0 && textExtractor != null) {
                textJob?.cancel()
                textJob = scope.launch {
                    val size = PointF(state.layout.pageWidth(pageIdx), state.layout.pageHeight(pageIdx))
                    val pt = textExtractor.pageText(pdfPage, size)
                    if (pt != null && pt.hasText) {
                        textSelection.begin(pdfPage, pt, pt.allWords.first())
                        textSelection.selectAll()
                        listener.onTextMenuVisibility(true)
                        listener.invalidate()
                    }
                }
            }
        }
    }

    // =====================================================================
    // Text menu actions (called from the Compose context menu)
    // =====================================================================

    fun selectedTextString(): String = textSelection.selectedText()

    fun copySelection(): String {
        val t = textSelection.selectedText()
        clearTextSelection()
        return t
    }

    fun highlightSelection(): Boolean {
        val t = textSelection
        if (!t.isActive) return false
        val rects = t.selectionRects().map { r ->
            val pw = state.layout.pageWidth(t.pageIndex)
            val ph = state.layout.pageHeight(t.pageIndex)
            listOf(r[0] / pw, r[1] / ph, r[2] / pw, r[3] / ph)
        }
        if (rects.isEmpty()) return false
        val ann = HighlightAnnotation(newId(), t.pageIndex, toolConfig.highlighterColor, toolConfig.highlighterOpacity, rects)
        listener.commit(doc.addHighlightCmd(ann))
        clearTextSelection()
        return true
    }

    fun underlineSelection(): Boolean {
        val t = textSelection
        if (!t.isActive) return false
        val rects = t.selectionRects().map { r ->
            val pw = state.layout.pageWidth(t.pageIndex)
            val ph = state.layout.pageHeight(t.pageIndex)
            listOf(r[0] / pw, r[1] / ph, r[2] / pw, r[3] / ph)
        }
        if (rects.isEmpty()) return false
        val ann = UnderlineAnnotation(newId(), t.pageIndex, 0xFFEF4444, 100, rects)
        listener.commit(doc.addUnderlineCmd(ann))
        clearTextSelection()
        return true
    }

    // =====================================================================
    // Text insertion (§12)
    // =====================================================================

    private fun tapInsertText(wx: Float, wy: Float, sticky: Boolean) {
        val page = pageForWorldY(wy)
        hitTextElement(wx, wy)?.let { existing ->
            listener.onInsertTextRequest(page, wx, wy, existing, sticky)
            return
        }
        listener.onInsertTextRequest(page, wx, wy, null, sticky)
    }

    fun commitText(existing: TextElement?, page: Int, wx: Float, wy: Float, text: String, sticky: Boolean, size: Float, color: Long): Command? {
        return if (existing != null) {
            val cmd = doc.updateTextCmd(existing, existing.copy(text = text, size = size, color = color, sticky = sticky))
            listener.commit(cmd)
            cmd
        } else if (text.isNotBlank()) {
            val el = TextElement(newId(), page, text, wx, wy, size, color, sticky, if (sticky) 220f else 300f, timestamp = System.currentTimeMillis())
            val cmd = doc.addTextCmd(el)
            listener.commit(cmd)
            cmd
        } else null
    }

    /** §12 — full rich-text commit: every editable property at once. */
    fun commitTextEdit(existing: TextElement, updated: TextElement): Command? {
        val cmd = doc.updateTextCmd(existing, updated)
        listener.commit(cmd)
        listener.invalidate()
        return cmd
    }

    /** §12 — inserts a fully configured text element. */
    fun commitTextInsert(el: TextElement): Command? {
        if (el.text.isBlank()) return null
        val cmd = doc.addTextCmd(el)
        listener.commit(cmd)
        listener.invalidate()
        return cmd
    }

    // =====================================================================
    // Hit helpers
    // =====================================================================

    private fun hitTextElement(wx: Float, wy: Float): TextElement? {
        for (t in doc.texts) {
            val h = renderer.measureTextHeight(t).toFloat()
            if (wx >= t.x && wx <= t.x + t.width + 8 && wy >= t.y - 4 && wy <= t.y + h + 4) return t
        }
        return null
    }

    private fun hitShapeElement(wx: Float, wy: Float): ShapeElement? {
        for (s in doc.shapes) {
            val b = s.bbox()
            if (wx >= b[0] - 4 && wx <= b[2] + 4 && wy >= b[1] - 4 && wy <= b[3] + 4) return s
        }
        return null
    }

    /** Hit-tests highlight/underline annotations, converting page-local to world coords. */
    private fun hitAnnotation(wx: Float, wy: Float): SelectedElement? {
        val layout = state.layout
        for (h in doc.highlights) {
            val page = h.page
            val left = layout.pageLeft(page)
            val top = layout.pageTop(page)
            val pw = layout.pageWidth(page)
            val ph = layout.pageHeight(page)
            for (r in h.rects) {
                val x0 = left + r[0] * pw; val y0 = top + r[1] * ph
                val x1 = left + r[2] * pw; val y1 = top + r[3] * ph
                if (wx in x0..x1 && wy in y0..y1) return SelectedElement.SelHighlight(h)
            }
        }
        for (u in doc.underlines) {
            val page = u.page
            val left = layout.pageLeft(page)
            val top = layout.pageTop(page)
            val pw = layout.pageWidth(page)
            val ph = layout.pageHeight(page)
            for (r in u.rects) {
                val x0 = left + r[0] * pw; val y0 = top + r[1] * ph
                val x1 = left + r[2] * pw; val y1 = top + r[3] * ph
                if (wx in x0..x1 && wy in y0 - 8..y1 + 8) return SelectedElement.SelUnderline(u)
            }
        }
        return null
    }

    private fun hitImageElement(wx: Float, wy: Float): ImageElement? {
        for (i in doc.images) {
            if (wx >= i.x && wx <= i.x + i.w && wy >= i.y && wy <= i.y + i.h) return i
        }
        return null
    }

    private fun distScreen(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        hypot(x1 - x2, y1 - y2)

    // =====================================================================
    // Laser lifecycle
    // =====================================================================

    fun tickLaser(): Boolean {
        val now = System.currentTimeMillis()
        var changed = false
        val it = laserTrails.iterator()
        while (it.hasNext()) {
            val tr = it.next()
            if (now - tr.startTime > LASER_LIFE_MS) {
                it.remove()
                changed = true
            }
        }
        return changed || laserTrails.isNotEmpty()
    }

    fun laserAlpha(trail: LaserTrail): Float {
        val age = (System.currentTimeMillis() - trail.startTime).toFloat() / LASER_LIFE_MS
        return (1f - age).coerceIn(0f, 1f)
    }

    // =====================================================================
    // Live preview data for the view
    // =====================================================================

    val liveStrokePoints: List<StrokePoint>? get() = livePoints
    val liveStrokeTool: ToolType get() = liveTool
    val liveStrokeWidth: Float get() = liveWidth
    val liveStrokeVelocity: Float get() = liveVelocity
    val livePressureResponse: Float
        get() = toolConfig.inkStyleOf(liveTool).pressureResponse
    val currentShapeStart: FloatArray? get() = shapeStart
    val currentShapeEnd: FloatArray? get() = shapeCur
    val currentLasso: ArrayList<Float>? get() = lassoPoints
    val erasedLive: List<Stroke> get() = erasedStrokes
}
