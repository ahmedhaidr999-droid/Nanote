package com.nanote.app.core.canvas

import android.graphics.PointF
import com.nanote.app.core.model.Command
import com.nanote.app.core.model.DocumentModel
import com.nanote.app.core.model.EraserMode
import com.nanote.app.core.model.isInkTool
import com.nanote.app.core.model.HighlightAnnotation
import com.nanote.app.core.model.ImageElement
import com.nanote.app.core.model.ShapeElement
import com.nanote.app.core.model.Stroke
import com.nanote.app.core.model.StrokePoint
import com.nanote.app.core.model.TextElement
import com.nanote.app.core.model.ToolConfig
import com.nanote.app.core.model.ToolType
import com.nanote.app.core.model.UnderlineAnnotation
import com.nanote.app.core.model.distToPolyline
import com.nanote.app.core.model.newId
import com.nanote.app.core.model.pointInPolygon
import com.nanote.app.core.pdf.PdfTextExtractor
import com.nanote.app.core.pdf.PdfWord
import com.nanote.app.util.AppLog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Touch/pen input brain — one per canvas pane.
 * The view forwards raw gestures; this class turns them into strokes, erases,
 * shapes, selections and text actions, applying every mutation through the
 * undoable command pipeline.
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
    }

    // ------------- runtime config -------------

    @Volatile
    var activeTool: ToolType = ToolType.PEN

    @Volatile
    var toolConfig: ToolConfig = ToolConfig()

    @Volatile
    var pressureEnabled: Boolean = true

    @Volatile
    var palmRejection: Boolean = true

    /** doc page index → pdf page index, or -1 when the page is a plain note page. */
    var pdfPageOf: (Int) -> Int = { -1 }

    // ------------- selection state -------------

    val textSelection = TextSelectionEngine()
    val objectSelection = ObjectSelection()
    private var selectedIds: HashSet<String> = HashSet()

    // ------------- live input state -------------

    private var livePoints: ArrayList<StrokePoint>? = null
    private var livePage = -1
    private var liveTool: ToolType = ToolType.PEN

    private val erasedStrokes = ArrayList<Stroke>()
    private val eraserPathWorld = ArrayList<Float>()

    private var shapeStart: FloatArray? = null
    private var shapeCur: FloatArray? = null

    private var lassoPoints: ArrayList<Float>? = null

    private var draggingTextHandle = 0 // 1 start, 2 end

    private var movingSelection = false
    private var scalingSelection = false
    private var moveLastWorld = PointF()
    private var scaleStartDist = 0f
    private var scaleStartBBox: FloatArray? = null
    private var scaleStartElements: List<Any> = emptyList()

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

    fun onActionDown(screenX: Float, screenY: Float, isStylus: Boolean, pressure: Float): Boolean {
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
            val corner = PointF(w[2], w[3])
            if (distScreen(screenX, screenY, corner.x, corner.y) < 34f) {
                scalingSelection = true
                scaleStartBBox = bbox
                scaleStartElements = objectSelection.items.map { it }
                scaleStartDist = maxOf(kotlin.math.hypot(screenX - w[0], screenY - w[1]), 8f)
                return true
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

        val tool = activeTool
        val finger = !isStylus

        // Fingers navigate (palm rejection): only stylus draws unless the user
        // disabled palm rejection. Finger with ink tool → treat as scroll (view handles).
        if (finger && palmRejection && (tool.isInkTool || tool == ToolType.ERASER ||
                    tool == ToolType.SHAPE || tool == ToolType.LASSO || tool == ToolType.LASER)
        ) {
            return false // view: finger scroll/zoom
        }

        when (tool) {
            ToolType.PEN, ToolType.PENCIL, ToolType.BRUSH, ToolType.HIGHLIGHTER -> {
                beginStroke(tapDownWorld.x, tapDownWorld.y, pressure, tool)
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
                lassoPoints = ArrayList()
                lassoPoints?.add(tapDownWorld.x); lassoPoints?.add(tapDownWorld.y)
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

    fun onActionMove(screenX: Float, screenY: Float, isStylus: Boolean, pressure: Float): Boolean {
        val wx = state.transform.screenToWorldX(screenX)
        val wy = state.transform.screenToWorldY(screenY)
        if (kotlin.math.hypot(wx - tapDownWorld.x, wy - tapDownWorld.y) > 6f / state.transform.scale) {
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
        if (scalingSelection) {
            // live ghost handled in draw; store current scale factor
            return true
        }

        when {
            livePoints != null -> {
                appendStrokePoint(wx, wy, pressure)
                listener.invalidate()
                return true
            }
            activeTool == ToolType.ERASER && (isStylus || !palmRejection) -> {
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
            commitSelectionScale(screenX, screenY)
            return true
        }

        when {
            livePoints != null -> finishStroke()
            activeTool == ToolType.ERASER && (isStylus || !palmRejection) -> finishEraser()
            shapeStart != null -> finishShape()
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

    // =====================================================================
    // Stroke capture
    // =====================================================================

    private fun beginStroke(wx: Float, wy: Float, pressure: Float, tool: ToolType) {
        livePage = state.layout.pageAtY(wy)
        if (livePage < 0) {
            livePage = state.layout.nearestPage(wy)
        }
        liveTool = tool
        val p = if (pressureEnabled) pressure.coerceIn(0.05f, 1f) else 1f
        livePoints = arrayListOf(StrokePoint(wx, wy, p))
    }

    private fun appendStrokePoint(wx: Float, wy: Float, pressure: Float) {
        val pts = livePoints ?: return
        val last = pts.lastOrNull() ?: return
        if (kotlin.math.hypot(wx - last.x, wy - last.y) < MIN_POINT_DIST && pts.size > 1) return
        val p = if (pressureEnabled) pressure.coerceIn(0.05f, 1f) else 1f
        if (pts.size >= 6000) return
        pts.add(StrokePoint(wx, wy, p))
    }

    private fun finishStroke() {
        val pts = livePoints ?: return
        livePoints = null
        if (pts.size < 1) return
        val cfg = toolConfig
        val stroke = when (liveTool) {
            ToolType.PEN -> Stroke(newId(), livePage, ToolType.PEN, cfg.penColor, cfg.penWidth, cfg.penOpacity, pts)
            ToolType.PENCIL -> Stroke(newId(), livePage, ToolType.PENCIL, cfg.pencilColor, cfg.pencilWidth, cfg.pencilOpacity, pts)
            ToolType.BRUSH -> Stroke(newId(), livePage, ToolType.BRUSH, cfg.brushColor, cfg.brushWidth, cfg.brushOpacity, pts)
            ToolType.HIGHLIGHTER -> Stroke(newId(), livePage, ToolType.HIGHLIGHTER, cfg.highlighterColor, cfg.highlighterWidth, cfg.highlighterOpacity, pts)
            else -> return
        }
        listener.commit(doc.addStrokeCmd(stroke))
        listener.invalidate()
    }

    // =====================================================================
    // Eraser
    // =====================================================================

    private fun eraserRadiusWorld(): Float =
        (toolConfig.eraserSize / 2f) / state.transform.scale

    private fun eraseAt(wx: Float, wy: Float) {
        val r = eraserRadiusWorld()
        when (toolConfig.eraserMode) {
            EraserMode.STROKE, EraserMode.OBJECT -> {
                for (s in doc.strokes) {
                    if (s in erasedStrokes) continue
                    val hit = distToPolyline(wx, wy, s.points) < r + s.width / 2f
                    if (hit) erasedStrokes.add(s)
                }
                if (toolConfig.eraserMode == EraserMode.OBJECT) {
                    hitTextElement(wx, wy)?.let { if (it !in erasedTexts) erasedTexts.add(it) }
                    hitImageElement(wx, wy)?.let { if (it !in erasedImages) erasedImages.add(it) }
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
                if (erasedHighlights.isNotEmpty() || erasedUnderlines.isNotEmpty()) {
                    listener.commit(doc.removeAnnotationsCmd(ArrayList(erasedHighlights), ArrayList(erasedUnderlines)))
                }
                erasedTexts.clear(); erasedImages.clear()
                erasedHighlights.clear(); erasedUnderlines.clear()
            }
            EraserMode.PIXEL -> {
                val r = eraserRadiusWorld()
                val touched = ArrayList<Stroke>()
                for (s in doc.strokes) {
                    // quick reject via path sample
                    for (k in 0 until eraserPathWorld.size step 2) {
                        if (distToPolyline(eraserPathWorld[k], eraserPathWorld[k + 1], s.points) < r + s.width / 2f) {
                            touched.add(s); break
                        }
                    }
                }
                var changed = false
                for (s in touched) {
                    val replacements = splitStroke(s, r)
                    if (replacements != null) {
                        listener.commit(doc.replaceStrokeCmd(s, replacements))
                        changed = true
                    }
                }
                if (changed) listener.invalidate()
            }
        }
        erasedStrokes.clear()
        eraserPathWorld.clear()
    }

    /** Splits a stroke removing points inside the eraser radius; null when unchanged. */
    private fun splitStroke(s: Stroke, r: Float): List<Stroke>? {
        val segments = ArrayList<List<StrokePoint>>()
        var current = ArrayList<StrokePoint>()
        for (p in s.points) {
            // distance to eraser polyline
            var inside = false
            for (k in 0 until eraserPathWorld.size step 2) {
                if (kotlin.math.hypot(p.x - eraserPathWorld[k], p.y - eraserPathWorld[k + 1]) < r) {
                    inside = true; break
                }
            }
            if (!inside) current.add(p)
            else if (current.isNotEmpty()) {
                if (current.size >= 1) segments.add(current)
                current = ArrayList()
            }
        }
        if (current.isNotEmpty()) segments.add(current)
        val kept = segments.filter { it.size >= 2 || (it.size == 1 && segments.size == 1) }
        if (kept.size == 1 && kept[0].size == s.points.size) return null
        return kept.map { seg -> s.copy(id = newId(), points = seg) }
    }

    // =====================================================================
    // Shapes / lasso
    // =====================================================================

    private fun finishShape() {
        val st = shapeStart ?: return
        val cur = shapeCur ?: run { shapeStart = null; return }
        shapeStart = null
        val page = state.layout.pageAtY((st[1] + cur[1]) / 2f).let { if (it < 0) state.layout.nearestPage((st[1] + cur[1]) / 2f) else it }
        val cfg = toolConfig
        if (kotlin.math.hypot(cur[0] - st[0], cur[1] - st[1]) > 4f / state.transform.scale) {
            val shape = ShapeElement(newId(), page, cfg.shapeKind, cfg.shapeColor, cfg.shapeWidth, 100, st[0], st[1], cur[0], cur[1])
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
        for (s in doc.strokes) {
            var inside = 0
            for (p in s.points) if (pointInPolygon(p.x, p.y, poly)) inside++
            if (inside >= s.points.size * 0.6f) { sel.add(SelectedElement.SelStroke(s)); strokeCount++ }
        }
        for (t in doc.texts) {
            if (pointInPolygon(t.x + t.width / 2, t.y + 10, poly)) { sel.add(SelectedElement.SelText(t)); textCount++ }
        }
        for (i in doc.images) {
            if (pointInPolygon(i.x + i.w / 2, i.y + i.h / 2, poly)) sel.add(SelectedElement.SelImage(i))
        }
        for (sh in doc.shapes) {
            if (pointInPolygon((sh.x1 + sh.x2) / 2, (sh.y1 + sh.y2) / 2, poly)) sel.add(SelectedElement.SelShape(sh))
        }
        if (sel.isNotEmpty()) {
            objectSelection.set(sel)
            syncSelectedIds()
            listener.onObjectMenuVisibility(true)
            listener.onSelectionCountChanged(strokeCount, textCount)
        }
    }

    private fun tapSelectObject(wx: Float, wy: Float) {
        // annotation hit first (topmost interaction layer)
        hitAnnotation(wx, wy)?.let {
            selectItems(listOf(it)); return
        }
        hitImageElement(wx, wy)?.let { selectItems(listOf(SelectedElement.SelImage(it))); return }
        hitTextElement(wx, wy)?.let { selectItems(listOf(SelectedElement.SelText(it))); return }
        // strokes last (topmost drawing)
        val hits = ArrayList<SelectedElement>()
        for (s in doc.strokes) {
            if (distToPolyline(wx, wy, s.points) < s.width / 2f + 6f / state.transform.scale) {
                hits.add(SelectedElement.SelStroke(s))
            }
        }
        if (hits.isNotEmpty()) {
            selectItems(hits.takeLast(1)) // topmost stroke
            return
        }
        objectSelection.clear()
        syncSelectedIds()
        listener.onObjectMenuVisibility(false)
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
    // Object move / scale commits
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

    private fun commitSelectionScale(screenX: Float, screenY: Float) {
        scalingSelection = false
        val bbox = scaleStartBBox
        if (bbox != null) {
            val w = bboxWorldToScreen(bbox)
            val dist = maxOf(kotlin.math.hypot(screenX - w[0], screenY - w[1]), 8f)
            val factor = (dist / scaleStartDist).coerceIn(0.1f, 20f)
            applyScale(factor, bbox)
        }
        scaleStartBBox = null
        scaleStartElements = emptyList()
        listener.invalidate()
    }

    private fun applyScale(factor: Float, bbox: FloatArray) {
        val cx = (bbox[0] + bbox[2]) / 2f
        val cy = (bbox[1] + bbox[3]) / 2f
        for (it in objectSelection.items) {
            when (it) {
                is SelectedElement.SelImage -> {
                    val img = it.image
                    val nw = img.w * factor
                    val nh = img.h * factor
                    val nx = cx - (cx - img.x) * factor
                    val ny = cy - (cy - img.y) * factor
                    listener.commit(doc.transformImageCmd(img, img.copy(x = nx, y = ny, w = nw, h = nh)))
                }
                is SelectedElement.SelText -> {
                    val t = it.text
                    listener.commit(doc.updateTextCmd(t, t.copy(
                        x = cx - (cx - t.x) * factor,
                        y = cy - (cy - t.y) * factor,
                        size = t.size * factor,
                        width = t.width * factor
                    )))
                }
                is SelectedElement.SelShape -> {
                    val s = it.shape
                    listener.commit(doc.addShapeCmdScaled(s, cx, cy, factor))
                }
                else -> {}
            }
        }
    }

    private fun bboxWorldToScreen(b: FloatArray): FloatArray = floatArrayOf(
        state.transform.worldToScreenX(b[0]), state.transform.worldToScreenY(b[1]),
        state.transform.worldToScreenX(b[2]), state.transform.worldToScreenY(b[3])
    )

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
    // Text insertion
    // =====================================================================

    private fun tapInsertText(wx: Float, wy: Float, sticky: Boolean) {
        val page = state.layout.pageAtY(wy).let { if (it < 0) state.layout.nearestPage(wy) else it }
        hitTextElement(wx, wy)?.let { existing ->
            listener.onInsertTextRequest(page, wx, wy, existing, sticky)
            return
        }
        listener.onInsertTextRequest(page, wx, wy, null, sticky)
    }

    fun commitText(existing: TextElement?, page: Int, wx: Float, wy: Float, text: String, sticky: Boolean, size: Float, color: Long) {
        if (existing != null) {
            listener.commit(doc.updateTextCmd(existing, existing.copy(text = text, size = size, color = color, sticky = sticky)))
        } else if (text.isNotBlank()) {
            val el = TextElement(newId(), page, text, wx, wy, size, color, sticky, if (sticky) 220f else 300f)
            listener.commit(doc.addTextCmd(el))
        }
        listener.invalidate()
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
        kotlin.math.hypot(x1 - x2, y1 - y2)

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
    val currentShapeStart: FloatArray? get() = shapeStart
    val currentShapeEnd: FloatArray? get() = shapeCur
    val currentLasso: ArrayList<Float>? get() = lassoPoints
    val erasedLive: List<Stroke> get() = erasedStrokes
}
