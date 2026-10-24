package com.nanote.ink.render

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.AttributeSet
import android.util.LruCache
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.nanote.domain.model.ImageObj
import com.nanote.domain.model.LinkObj
import com.nanote.domain.model.PageObject
import com.nanote.domain.model.TextBox
import com.nanote.ink.engine.AddStrokes
import com.nanote.ink.engine.Bounds
import com.nanote.ink.engine.EditorTool
import com.nanote.ink.engine.History
import com.nanote.ink.engine.InputRouter
import com.nanote.ink.engine.Mat3
import com.nanote.ink.engine.PageDoc
import com.nanote.ink.engine.PenSpec
import com.nanote.ink.engine.PointerSpec
import com.nanote.ink.engine.RemoveObjects
import com.nanote.ink.engine.ReplaceObject
import com.nanote.ink.engine.ReplaceStrokes
import com.nanote.ink.engine.Route
import com.nanote.ink.engine.Stroke
import com.nanote.ink.engine.ToolSpec
import com.nanote.ink.engine.ToolTypes
import com.nanote.ink.engine.ViewTransform
import com.nanote.ink.engine.eraserHits
import com.nanote.ink.engine.pixelErasePath
import com.nanote.ink.engine.pointInPolygon
import com.nanote.ink.engine.recognize
import com.nanote.ink.engine.simplify
import com.nanote.ink.engine.Snap
import com.nanote.ink.engine.specOf
import com.nanote.ink.engine.STRIDE
import com.nanote.ink.input.GestureController
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.pow

/**
 * The drawing surface (spec 2/6/7). M3 tool set:
 *  - pens + hold-to-snap shape recognition (pure geometry)
 *  - shapes tool (line/arrow/rect/ellipse/triangle/diamond/star) with live preview
 *  - whole-stroke eraser AND pixel eraser (live fragment preview, one undo step)
 *  - lasso: select (>=50% points inside), move/resize/rotate (one composed
 *    matrix, committed on release), copy/paste/delete/recolor
 *  - text and image objects with drag-to-move; eraser removes them (undoable)
 *  - laser pointer with a fading trail (never saved)
 *
 * Touch pipeline per spec 6.1: unbuffered dispatch, historical samples first,
 * screen->page via inverse matrix, ACTION_CANCEL discards. Zero allocations
 * per event: scratch buffers are preallocated; per-gesture lists only grow on
 * gesture boundaries.
 */
@SuppressLint("WrongConstant", "ClickableViewAccessibility")
class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Host {
        val doc: PageDoc
        val history: History
        val transform: ViewTransform
        val tileCache: InkTileCache
        fun tool(): ToolSpec
        fun fingersDraw(): Boolean
        fun surfaceColor(): Int

        /** v1.1: full palm rejection — canvas accepts the stylus only. */
        fun stylusOnly(): Boolean

        /** v1.1: user-customizable stylus barrel-button action. */
        fun stylusBarrelAction(): com.nanote.ink.engine.StylusBarrelAction

        /** v1.1: fired once when the barrel button is pressed with an
         *  UNDO/REDO action mapped. */
        fun onBarrelCommand(action: com.nanote.ink.engine.StylusBarrelAction)

        /** Ids temporarily hidden from tile snapshots (lasso/pixel-erase previews). */
        val hiddenStrokeIds: MutableSet<Int>
        val hiddenObjectIds: MutableSet<String>

        fun onContentCommitted()
        fun onViewportOrContentMayHaveChanged()

        /** Text tool tap: open the text composer at page position. */
        fun onTapForText(pageX: Float, pageY: Float)

        /** Image tool tap: open the photo picker; result lands at (pageX, pageY). */
        fun onTapForImage(pageX: Float, pageY: Float)

        /** A link object was tapped (page jump or URL). */
        fun onLinkTap(link: LinkObj)
    }

    var host: Host? = null
        private set

    // ----- scratch (no allocation in touch/draw) -----
    private val pagePoint = FloatArray(2)
    private val scratchRect = Rect()
    private val androidMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    private val painter = StrokePainter()
    private val frozenPath = Path()
    private val livePath = Path()
    private val previewPath = Path()
    private val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0xFF1976D2.toInt()
    }
    private val selBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = 0x991976D2.toInt()
    }
    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pageBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    // ----- object painting -----
    private val imageCache = object : LruCache<String, Bitmap>(12) {}
    private val ioExecutor = Executors.newSingleThreadExecutor()
    val objectPainter = ObjectPainter { assetId -> imageCache.get(assetId) }

    /** Asynchronously decodes an image asset into the cache and repaints. */
    fun prefetchImage(assetId: String, loader: () -> Bitmap?) {
        if (imageCache.get(assetId) != null) return
        ioExecutor.execute {
            val bmp = try {
                loader()
            } catch (e: Exception) {
                null
            }
            if (bmp != null) {
                imageCache.put(assetId, bmp)
                postInvalidateOnAnimation()
            }
        }
    }

    // ----- live stroke buffers -----
    private var livePts = FloatArray(INIT_LIVE_POINTS * STRIDE)
    private var liveWidths = FloatArray(INIT_LIVE_POINTS)
    private var liveCount = 0
    private var liveWidth = 0f
    private var liveDownTime = 0L
    private var livePen = com.nanote.ink.engine.PenType.BALLPOINT
    private var liveArgb = 0
    private var liveBaseWidth = 2f
    private var liveActive = false
    private var liveFrozenCount = 0
    private var liveDirty = Bounds()
    private var lastLiveMoveMs = 0L
    private val filterX = com.nanote.ink.engine.OneEuro()
    private val filterY = com.nanote.ink.engine.OneEuro()

    // ----- routing state -----
    private val routes = SparseArray<Route>()
    private val router = InputRouter(
        fingersDraw = { host?.fingersDraw() ?: true },
        stylusOnly = { host?.stylusOnly() ?: false },
        barrelAction = { host?.stylusBarrelAction() ?: com.nanote.ink.engine.StylusBarrelAction.ERASE }
    )
    private val gestures = GestureController()
    private var navPointerCount = 0
    private var drawingPointerId = -1
    private var erasingPointerId = -1

    // whole-stroke erase gesture
    private val erasedThisGesture = ArrayList<Stroke>()
    private var eraseX = Float.NaN
    private var eraseY = Float.NaN
    private var eraseR = 8f
    private var erasing = false

    // pixel erase gesture
    private val pixelCenters = ArrayList<Float>()
    private val pixelHiddenIds = HashSet<Int>()
    private val pixelFragments = ArrayList<Stroke>()
    private val pixelScratch = Bounds()
    private val pixelQuery = HashSet<Int>()

    // lasso state
    private class LassoState {
        val polygon = ArrayList<Float>()
        var finished = false
        var selectionBounds = Bounds()
        val selectedIds = ArrayList<Int>()
        val originals = ArrayList<Stroke>()
        val preview = Mat3()
        var mode = 0 // 0 idle, 1 move, 2 transform
    }

    private var lasso: LassoState? = null
    private val lassoQuery = HashSet<Int>()
    private val lassoBounds = Bounds()
    private var lassoMoveLastX = 0f
    private var lassoMoveLastY = 0f
    // transform gesture anchors
    private var tgLastSpan = 0f
    private var tgLastAngle = 0f
    private var tgLastCx = 0f
    private var tgLastCy = 0f

    // shape tool
    private var shapeActive = false
    private var shapeX0 = 0f
    private var shapeY0 = 0f
    private var shapeX1 = 0f
    private var shapeY1 = 0f

    // object drag
    private var draggedObject: PageObject? = null
    private var dragStartPageX = 0f
    private var dragStartPageY = 0f
    private var dragMoved = false

    // laser
    private val laserPts = ArrayList<Float>() // x, y, eventTime
    private var laserActive = false

    // double-tap
    private var lastTapUpMs = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var zoomAnimator: ValueAnimator? = null

    private var fittedPageW = 0f
    private var fittedPageH = 0f

    private val tileRect = Rect()
    private val lassoCornerScratch = FloatArray(8)

    fun attach(h: Host) {
        host = h
        h.tileCache.onTileReadyCallback = { info ->
            val t = h.transform
            val ts = 2.0.pow(info.bucket / 2.0).toFloat()
            val k = t.scale / ts
            val size = (InkTileCache.TILE_PX * k).toInt()
            val left = (info.tx * InkTileCache.TILE_PX * k).toInt()
            val top = (info.ty * InkTileCache.TILE_PX * k).toInt()
            tileRect.set(left, top, left + size + 2, top + size + 2)
            tileRect.offset(t.transX.toInt(), t.transY.toInt())
            invalidate(tileRect)
        }
        invalidate()
        // Objects are drawn by the view every frame; tiles carry paper + ink only.
    }

    fun detach() {
        host = null
        zoomAnimator?.cancel()
        ioExecutor.shutdownNow()
    }

    // ------------------------------------------------------------------ draw

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refitViewportIfNeeded()
    }

    /** Refits when the page (or session) changed since the last fit. */
    fun refitViewportIfNeeded() {
        val h0 = host ?: return
        val doc = h0.doc
        if (doc.widthPt != fittedPageW || doc.heightPt != fittedPageH) {
            fittedPageW = doc.widthPt
            fittedPageH = doc.heightPt
            h0.tileCache.invalidateAll()
            if (width > 0 && height > 0) {
                h0.transform.fitToWidth(doc.widthPt, doc.heightPt, width.toFloat(), height.toFloat())
            }
            invalidate()
        }
    }

    /** Force a re-fit (page settings changed). */
    fun refitViewport() {
        fittedPageW = 0f
        fittedPageH = 0f
        refitViewportIfNeeded()
    }

    override fun onDraw(canvas: Canvas) {
        val h = host ?: return
        val doc = h.doc
        val t = h.transform

        canvas.drawColor(h.surfaceColor())

        h.tileCache.drawInto(canvas, t.scale, t.transX, t.transY, width.toFloat(), height.toFloat(), doc)

        // page-space drawing below
        val v = t.m.v
        matrixValues[0] = v[0]; matrixValues[1] = v[1]; matrixValues[2] = v[2]
        matrixValues[3] = v[3]; matrixValues[4] = v[4]; matrixValues[5] = v[5]
        matrixValues[6] = 0f; matrixValues[7] = 0f; matrixValues[8] = 1f
        androidMatrix.setValues(matrixValues)
        val save = canvas.save()
        canvas.setMatrix(androidMatrix)

        pageBorderPaint.color = 0x33282828
        pageBorderPaint.strokeWidth = 1f / t.scale
        canvas.drawRect(0f, 0f, doc.widthPt, doc.heightPt, pageBorderPaint)

        // objects (except dragged/hidden)
        val objs = doc.objectsSnapshot().filter { it.id !in h.hiddenObjectIds }
        val dragged = draggedObject
        if (objs.isNotEmpty()) objectPainter.drawObjects(canvas, objs)

        // pixel-erase fragments live preview
        if (pixelFragments.isNotEmpty()) {
            for (s in pixelFragments) {
                painter.configure(s.pen, s.argb)
                previewPath.rewind()
                painter.appendStrokePath(s, previewPath)
                canvas.drawPath(previewPath, painter.paint)
            }
        }

        // shape tool preview
        if (shapeActive) {
            val tool = h.tool()
            val preview = buildShapeStroke(tool, 0, shapeX0, shapeY0, shapeX1, shapeY1, 0f)
            if (preview != null) {
                painter.configure(preview.pen, preview.argb)
                previewPath.rewind()
                painter.appendStrokePath(preview, previewPath)
                canvas.drawPath(previewPath, painter.paint)
            }
        }

        // lasso overlays
        val ls = lasso
        if (ls != null) {
            if (!ls.finished && ls.polygon.size >= 4) {
                previewPath.rewind()
                previewPath.moveTo(ls.polygon[0], ls.polygon[1])
                for (i in 2 until ls.polygon.size step 2) {
                    previewPath.lineTo(ls.polygon[i], ls.polygon[i + 1])
                }
                lassoPaint.strokeWidth = 1.2f / t.scale
                canvas.drawPath(previewPath, lassoPaint)
            }
            if (ls.selectionBounds.let { !it.isEmpty }) {
                selBoxPaint.strokeWidth = 1.2f / t.scale
                val b = ls.selectionBounds
                // transform selection bounds corners by preview matrix (reused scratch)
                val c = lassoCornerScratch
                c[0] = b.left; c[1] = b.top; c[2] = b.right; c[3] = b.top
                c[4] = b.right; c[5] = b.bottom; c[6] = b.left; c[7] = b.bottom
                ls.preview.mapPoints(c, 0, 4)
                previewPath.rewind()
                previewPath.moveTo(c[0], c[1])
                previewPath.lineTo(c[2], c[3])
                previewPath.lineTo(c[4], c[5])
                previewPath.lineTo(c[6], c[7])
                previewPath.close()
                canvas.drawPath(previewPath, selBoxPaint)

                // transformed copies of selected strokes
                for (s in ls.originals) {
                    val mapped = s.transformed(ls.preview, s.id)
                    painter.configure(mapped.pen, mapped.argb)
                    previewPath.rewind()
                    painter.appendStrokePath(mapped, previewPath)
                    canvas.drawPath(previewPath, painter.paint)
                }
            }
        }

        // dragged object preview at new position
        if (dragged != null) {
            drawDraggedPreview(canvas, dragged)
        }

        // live stroke
        if (liveActive && liveCount > 0) {
            painter.configure(livePen, liveArgb)
            canvas.drawPath(frozenPath, painter.paint)
            canvas.drawPath(livePath, painter.paint)
            if (liveCount == 1) {
                painter.drawDot(canvas, livePts[0], livePts[1], liveWidths[0] / 2f)
            } else if (specOf(livePen).capOrdinal == PenSpec.CAP_ROUND) {
                painter.drawDot(canvas, livePts[0], livePts[1], liveWidths[0] / 2f)
                val lastX = livePts[(liveCount - 1) * STRIDE]
                val lastY = livePts[(liveCount - 1) * STRIDE + 1]
                painter.drawDot(canvas, lastX, lastY, liveWidths[liveCount - 1] / 2f)
            }
        }

        // laser trail (fading, never saved)
        if (laserPts.isNotEmpty()) {
            val now = android.os.SystemClock.uptimeMillis()
            // compact out expired triplets in place (zero alloc)
            var w = 0
            var r = 0
            while (r + 2 < laserPts.size) {
                if (laserPts[r + 2] >= now - LASER_FADE_MS) {
                    laserPts[w] = laserPts[r]
                    laserPts[w + 1] = laserPts[r + 1]
                    laserPts[w + 2] = laserPts[r + 2]
                    w += 3
                }
                r += 3
            }
            while (laserPts.size > w) laserPts.removeAt(laserPts.size - 1)

            for (j in laserPts.indices step 3) {
                val age = (now - laserPts[j + 2]).coerceAtLeast(0f)
                val alpha = (1f - age / LASER_FADE_MS).coerceIn(0f, 1f)
                laserPaint.color = ((alpha * 255).toInt() shl 24) or (0xFFD32F2F.toInt() and 0xFFFFFF)
                val rr = 1f + 3f * alpha
                canvas.drawCircle(laserPts[j], laserPts[j + 1], rr, laserPaint)
            }
            postInvalidateOnAnimation()
        }

        canvas.restoreToCount(save)

        // eraser ring cursor (screen space)
        if (erasing && !eraseY.isNaN()) {
            erasePaint.color = 0x99202020.toInt()
            val cx = t.m.mapX(eraseX, eraseY)
            val cy = t.m.mapY(eraseX, eraseY)
            canvas.drawCircle(cx, cy, eraseR * t.scale, erasePaint)
        }
    }

    private val draggedPreviewMatrix = Matrix()
    private val draggedValues = FloatArray(9)

    private fun drawDraggedPreview(canvas: Canvas, obj: PageObject) {
        val dx = pagePoint[0] - dragStartPageX
        val dy = pagePoint[1] - dragStartPageY
        val m = when (obj) {
            is TextBox -> obj.m
            is ImageObj -> obj.m
            else -> return
        }
        for (i in 0 until 9) draggedValues[i] = m.getOrElse(i) { if (i == 8) 1f else 0f }
        draggedValues[2] += dx
        draggedValues[5] += dy
        draggedPreviewMatrix.setValues(draggedValues)
        val save = canvas.save()
        canvas.concat(draggedPreviewMatrix)
        objectPainter.drawObject(
            canvas,
            when (obj) {
                is TextBox -> obj.copy(m = (0 until 9).map { draggedValues[it] })
                is ImageObj -> obj.copy(m = (0 until 9).map { draggedValues[it] })
                else -> obj
            }
        )
        canvas.restoreToCount(save)
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val h = host ?: return false
        requestUnbufferedDispatch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return onDown(e, h)
            MotionEvent.ACTION_POINTER_DOWN -> return onPointerDown(e, h)
            MotionEvent.ACTION_MOVE -> {
                val r = onMove(e, h)
                maybeNav(e, h)
                return r
            }
            MotionEvent.ACTION_POINTER_UP -> return onPointerUp(e, h)
            MotionEvent.ACTION_UP -> return onUp(e, h)
            MotionEvent.ACTION_CANCEL -> return onCancel(e, h)
        }
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val stylus = event.getToolType(0) == ToolTypes.STYLUS ||
            event.getToolType(0) == ToolTypes.ERASER
        router.onHoverEvent(stylus, event.eventTime)
        return true
    }

    private fun spec(e: MotionEvent, i: Int): PointerSpec = PointerSpec(
        e.getToolType(i),
        e.buttonState,
        e.getTouchMajor(i),
        e.eventTime,
        e.pointerCount
    )

    private fun onDown(e: MotionEvent, h: Host): Boolean {
        routes.clear()
        navPointerCount = 0
        val tool = h.tool()
        val route = router.route(spec(e, 0))
        val isStylus = e.getToolType(0) == ToolTypes.STYLUS || e.getToolType(0) == ToolTypes.ERASER
        router.onStylusDownChanged(isStylus, e.eventTime)

        // double-tap for zoom only when navigating
        val now = e.eventTime
        val close = hypot(e.x - lastTapX, e.y - lastTapY) < 48f
        if (route == Route.NAVIGATE && now - lastTapUpMs < 300 && close) {
            lastTapUpMs = 0
            animateZoomToggle(e.x, e.y)
            return true
        }
        lastTapUpMs = 0

        routes.put(e.getPointerId(0), route)

        // resolve page point
        val ok = h.transform.toPage(e.x, e.y, pagePoint)
        val px = pagePoint[0]
        val py = pagePoint[1]

        // active selection? manipulate it instead of drawing
        val ls = lasso
        if (ls != null && ls.finished && route == Route.DRAW) {
            val inside = pointInSelection(px, py, ls)
            if (!inside) {
                commitLasso(h)
                // fall through: start a new gesture below
            } else {
                ls.mode = 1
                lassoMoveLastX = px
                lassoMoveLastY = py
                return true
            }
        }

        when (route) {
            Route.DRAW -> when (tool.tool) {
                EditorTool.LASSO -> beginLasso(h, px, py)
                EditorTool.SHAPE -> beginShape(h, px, py)
                EditorTool.LASER -> beginLaser(h, px, py)
                EditorTool.TEXT -> {
                    // tap handled on UP (no move)
                }
                EditorTool.IMAGE -> { /* tap handled on UP */ }
                EditorTool.ERASER -> beginErase(e, 0, h)
                EditorTool.HIGHLIGHTER -> beginStroke(e, 0, h) // separated highlighter tool
                EditorTool.PEN -> {
                    // object drag? (text/image under the pen)
                    if (ok) {
                        val hit = objectPainter.hitTest(h.doc.objectsSnapshot(), px, py)
                        if (hit != null) {
                            draggedObject = hit
                            dragStartPageX = px
                            dragStartPageY = py
                            dragMoved = false
                            h.hiddenObjectIds.add(hit.id)
                            h.tileCache.invalidateAll()
                            return true
                        }
                    }
                    beginStroke(e, 0, h)
                }
            }
            Route.ERASE -> beginErase(e, 0, h)
            Route.LASSO -> {
                // v1.1: barrel-button lasso — the current tool does not matter
                if (ok && lasso == null) beginLasso(h, px, py)
            }
            Route.COMMAND -> {
                // v1.1: barrel-button undo/redo fires once on press
                h.onBarrelCommand(h.stylusBarrelAction())
            }
            Route.NAVIGATE -> {
                gestures.begin(e.x, e.y, e.x, e.y)
                navPointerCount = 1
            }
            Route.IGNORE -> Unit
        }
        return true
    }

    private fun onPointerDown(e: MotionEvent, h: Host): Boolean {
        val i = e.actionIndex
        val route = router.route(spec(e, i))
        routes.put(e.getPointerId(i), route)

        val ls = lasso
        if (ls != null && ls.finished && ls.mode == 1 && route == Route.DRAW) {
            // upgrade to transform gesture with second finger
            ls.mode = 2
            val a = e.getX(0)
            val b = e.getY(0)
            val c = e.getX(1)
            val d = e.getY(1)
            tgLastSpan = hypot(c - a, d - b)
            tgLastAngle = atan2(d - b, c - a) * 180f / Math.PI.toFloat()
            tgLastCx = (a + c) / 2f
            tgLastCy = (b + d) / 2f
            return true
        }

        when (route) {
            Route.NAVIGATE -> {
                // v1.1: in pen-only mode the first finger landed as IGNORE
                // (anti-palm); a second small finger is a deliberate pinch —
                // promote the first so pan/zoom keeps working.
                if (h.stylusOnly()) {
                    promoteIgnoredFingers(e)
                    navPointerCount = 0
                    for (p in 0 until e.pointerCount) {
                        if (routes.get(e.getPointerId(p)) == Route.NAVIGATE) navPointerCount++
                    }
                } else {
                    navPointerCount++
                }
                if (navPointerCount == 2) {
                    val a = navigatePointer(e, 0)
                    val b = navigatePointer(e, 1)
                    if (a != null && b != null) gestures.begin(a[0], a[1], b[0], b[1])
                }
            }
            Route.DRAW -> if (!liveActive) beginStroke(e, i, h)
            Route.ERASE -> if (!erasing) beginErase(e, i, h)
            Route.LASSO -> {
                if (lasso == null && h.transform.toPage(e.getX(i), e.getY(i), pagePoint)) {
                    beginLasso(h, pagePoint[0], pagePoint[1])
                }
            }
            Route.COMMAND -> Unit // fired once on DOWN
            Route.IGNORE -> Unit
        }
        return true
    }

    private fun navigatePointer(e: MotionEvent, order: Int): FloatArray? {
        var seen = 0
        for (p in 0 until e.pointerCount) {
            if (routes.get(e.getPointerId(p)) == Route.NAVIGATE) {
                if (seen == order) {
                    pagePointScratch2[0] = e.getX(p)
                    pagePointScratch2[1] = e.getY(p)
                    return pagePointScratch2
                }
                seen++
            }
        }
        return null
    }

    private val pagePointScratch2 = FloatArray(2)

    private fun onMove(e: MotionEvent, h: Host): Boolean {
        val tool = h.tool()

        // selection gestures
        val ls = lasso
        if (ls != null && ls.finished) {
            if (ls.mode == 1) {
                if (h.transform.toPage(e.x, e.y, pagePoint)) {
                    val dx = pagePoint[0] - lassoMoveLastX
                    val dy = pagePoint[1] - lassoMoveLastY
                    ls.preview.postTranslate(dx, dy)
                    lassoMoveLastX = pagePoint[0]
                    lassoMoveLastY = pagePoint[1]
                    invalidate()
                }
                return true
            } else if (ls.mode == 2 && e.pointerCount >= 2) {
                val a = e.getX(0); val b = e.getY(0)
                val c = e.getX(1); val d = e.getY(1)
                val span = hypot(c - a, d - b)
                val angle = atan2(d - b, c - a) * 180f / Math.PI.toFloat()
                val cx = (a + c) / 2f
                val cy = (b + d) / 2f
                // map screen centroid to page space and rotate/scale there
                val pc = floatArrayOf(cx, cy)
                if (h.transform.toPage(cx, cy, pc)) {
                    val dAngle = angle - tgLastAngle
                    if (span > 1f && tgLastSpan > 1f) {
                        val k = span / tgLastSpan
                        ls.preview.postScale(k, k, pc[0], pc[1])
                    }
                    if (kotlin.math.abs(dAngle) > 0.01f) {
                        ls.preview.postRotate(dAngle, pc[0], pc[1])
                    }
                    val dx = cx - tgLastCx
                    val dy = cy - tgLastCy
                    if (dx != 0f || dy != 0f) {
                        val pd = floatArrayOf(dx, dy)
                        h.transform.m.inverseMapPoints(pd, 0, 1)
                        ls.preview.postTranslate(pd[0], pd[1])
                    }
                }
                tgLastSpan = span
                tgLastAngle = angle
                tgLastCx = cx
                tgLastCy = cy
                invalidate()
                return true
            }
        }

        // object drag
        val dragged = draggedObject
        if (dragged != null) {
            if (h.transform.toPage(e.x, e.y, pagePoint)) {
                dragMoved = true
                invalidate()
            }
            return true
        }

        // laser
        if (laserActive) {
            if (h.transform.toPage(e.x, e.y, pagePoint)) {
                laserPts.add(pagePoint[0]); laserPts.add(pagePoint[1]); laserPts.add(e.eventTime.toFloat())
                postInvalidateOnAnimation()
            }
            return true
        }

        // lasso capture
        val ls2 = lasso
        if (ls2 != null && !ls2.finished) {
            if (h.transform.toPage(e.x, e.y, pagePoint)) {
                ls2.polygon.add(pagePoint[0]); ls2.polygon.add(pagePoint[1])
                invalidate()
            }
            return true
        }

        // shape preview
        if (shapeActive) {
            if (h.transform.toPage(e.x, e.y, pagePoint)) {
                shapeX1 = pagePoint[0]
                shapeY1 = pagePoint[1]
                invalidate()
            }
            return true
        }

        // normal samples
        val hist = e.historySize
        for (p in 0 until e.pointerCount) {
            val route = routes.get(e.getPointerId(p)) ?: continue
            for (hs in 0 until hist) {
                dispatchSample(
                    e, p,
                    e.getHistoricalX(p, hs), e.getHistoricalY(p, hs),
                    e.getHistoricalPressure(p, hs),
                    e.getHistoricalEventTime(hs), route, h
                )
            }
            dispatchSample(e, p, e.getX(p), e.getY(p), e.getPressure(p), e.eventTime, route, h)
        }

        if (liveActive || erasing) invalidateLiveDirty(h)
        return true
    }

    private fun dispatchSample(
        e: MotionEvent,
        p: Int,
        sx: Float,
        sy: Float,
        pressure: Float,
        timeMs: Long,
        route: Route,
        h: Host
    ) {
        when (route) {
            Route.DRAW -> {
                if (e.getPointerId(p) == drawingPointerId) addLivePoint(sx, sy, pressure, timeMs, h)
            }
            Route.ERASE -> {
                if (e.getPointerId(p) == erasingPointerId) eraseSegment(sx, sy, h)
            }
            else -> Unit
        }
    }

    private fun onPointerUp(e: MotionEvent, h: Host): Boolean {
        val i = e.actionIndex
        val id = e.getPointerId(i)
        val route = routes.get(id) ?: return true
        routes.remove(id)

        val ls = lasso
        if (ls != null && ls.finished && ls.mode == 2) {
            ls.mode = 1
            if (h.transform.toPage(e.getX(i), e.getY(i), pagePoint)) {
                lassoMoveLastX = pagePoint[0]
                lassoMoveLastY = pagePoint[1]
            }
            return true
        }

        when (route) {
            Route.DRAW -> if (id == drawingPointerId) endStroke(e.eventTime, h)
            Route.ERASE -> if (id == erasingPointerId) endEraseGesture(h)
            Route.LASSO -> Unit // barrel-lasso: capture continues; ends on final UP
            Route.COMMAND -> Unit // one-shot command already fired on DOWN
            Route.NAVIGATE -> {
                navPointerCount--
                if (navPointerCount <= 1) {
                    val remaining = navigatePointer(e, 0)
                    if (remaining != null) gestures.begin(remaining[0], remaining[1], remaining[0], remaining[1])
                    else gestures.end()
                    invalidate()
                }
            }
            Route.IGNORE -> Unit
        }
        return true
    }

    private fun onUp(e: MotionEvent, h: Host): Boolean {
        val id = e.getPointerId(e.actionIndex)
        val route = routes.get(id)
        val canceled = e.flags and 0x20 != 0 // MotionEvent.FLAG_CANCELED (API 24 constant, inlined)
        val isStylus = e.getToolType(e.actionIndex) == ToolTypes.STYLUS ||
            e.getToolType(e.actionIndex) == ToolTypes.ERASER
        router.onStylusDownChanged(false, e.eventTime)

        val tool = h.tool()
        val ls = lasso
        if (ls != null && ls.finished) {
            if (ls.mode == 1 || ls.mode == 2) {
                ls.mode = 0
            }
            routes.clear()
            return true
        }

        if (ls != null && !ls.finished && (tool.tool == EditorTool.LASSO || route == Route.LASSO)) {
            finishLassoCapture(h)
            routes.clear()
            return true
        }

        if (shapeActive && tool.tool == EditorTool.SHAPE) {
            endShape(h)
            routes.clear()
            return true
        }

        if (laserActive) {
            laserActive = false
            postInvalidateOnAnimation()
            routes.clear()
            return true
        }

        val dragged = draggedObject
        if (dragged != null) {
            endObjectDrag(h)
            routes.clear()
            return true
        }

        // taps (no movement) for text/image/link
        if (route == Route.DRAW && !canceled) {
            when (tool.tool) {
                EditorTool.TEXT -> {
                    if (h.transform.toPage(e.x, e.y, pagePoint)) {
                        h.onTapForText(pagePoint[0], pagePoint[1])
                    }
                    routes.clear()
                    return true
                }
                EditorTool.IMAGE -> {
                    if (h.transform.toPage(e.x, e.y, pagePoint)) {
                        h.onTapForImage(pagePoint[0], pagePoint[1])
                    }
                    routes.clear()
                    return true
                }
                EditorTool.PEN -> {
                    if (h.transform.toPage(e.x, e.y, pagePoint)) {
                        val link = objectPainter.hitTest(h.doc.objectsSnapshot(), pagePoint[0], pagePoint[1])
                        if (link is LinkObj && !liveActive) {
                            h.onLinkTap(link)
                            routes.clear()
                            return true
                        }
                    }
                }
                else -> Unit
            }
        }

        when (route) {
            Route.DRAW -> if (canceled) cancelStroke(h) else if (id == drawingPointerId) endStroke(e.eventTime, h)
            Route.ERASE -> if (id == erasingPointerId) endEraseGesture(h)
            Route.LASSO -> Unit // handled by finishLassoCapture above
            Route.COMMAND -> Unit
            Route.NAVIGATE -> {
                if (navPointerCount == 1) {
                    lastTapUpMs = e.eventTime
                    lastTapX = e.x
                    lastTapY = e.y
                }
                navPointerCount = 0
                gestures.end()
                invalidate()
            }
            Route.IGNORE -> Unit
        }
        routes.clear()
        if (e.pointerCount == 1 && !isStylus) {
            h.onViewportOrContentMayHaveChanged()
        }
        return true
    }

    private fun onCancel(e: MotionEvent, h: Host): Boolean {
        cancelStroke(h)
        erasing = false
        endPixelEraseDiscard()
        eraseY = Float.NaN
        draggedObject?.let { h.hiddenObjectIds.remove(it.id) }
        draggedObject = null
        lasso = null
        routes.clear()
        navPointerCount = 0
        gestures.end()
        h.onViewportOrContentMayHaveChanged()
        invalidate()
        return true
    }

    // ------------------------------------------------------------- navigation

    private fun maybeNav(e: MotionEvent, h: Host) {
        if (navPointerCount <= 0) return
        if (navPointerCount >= 2) {
            val a = navigatePointer(e, 0)
            val b = navigatePointer(e, 1)
            if (a != null && b != null) {
                gestures.update(a[0], a[1], b[0], b[1])
                val t = h.transform
                t.zoom(gestures.zoomFactor, gestures.focusX, gestures.focusY)
                t.pan(gestures.focusDx, gestures.focusDy)
                t.clampPan(h.doc.widthPt, h.doc.heightPt, width.toFloat(), height.toFloat())
                invalidate()
            }
        } else {
            val a = navigatePointer(e, 0) ?: return
            gestures.update(a[0], a[1], a[0], a[1])
            h.transform.pan(gestures.focusDx, gestures.focusDy)
            h.transform.clampPan(h.doc.widthPt, h.doc.heightPt, width.toFloat(), height.toFloat())
            invalidate()
        }
    }

    // ------------------------------------------------------------ live stroke

    private fun beginStroke(e: MotionEvent, i: Int, h: Host) {
        val tool = h.tool()
        livePen = tool.pen
        liveArgb = tool.colorArgb
        liveBaseWidth = tool.widthPt
        liveCount = 0
        liveFrozenCount = 0
        liveActive = true
        liveDownTime = e.eventTime
        lastLiveMoveMs = e.eventTime
        drawingPointerId = e.getPointerId(i)
        frozenPath.rewind()
        livePath.rewind()
        filterX.reset()
        filterY.reset()
        liveDirty.reset()
        addLivePoint(e.getX(i), e.getY(i), e.getPressure(i), e.eventTime, h)
    }

    private fun addLivePoint(sx: Float, sy: Float, pressure: Float, timeMs: Long, h: Host) {
        ensureLiveCapacity(liveCount + 1)
        val t = h.transform
        if (!t.toPage(sx, sy, pagePoint)) return

        val spec = specOf(livePen)
        val dt = maxOf(timeMs - liveDownTime, 1L)
        val fx = filterX.apply(pagePoint[0], dt / 1000f)
        val fy = filterY.apply(pagePoint[1], dt / 1000f)

        val pr = com.nanote.ink.engine.PRESSURE_LUT[(pressure.coerceIn(0f, 1f) * 255f).toInt()]
        val speed = if (liveCount == 0) 0f else {
            val prevX = livePts[(liveCount - 1) * STRIDE]
            val prevY = livePts[(liveCount - 1) * STRIDE + 1]
            val pdt = maxOf(timeMs - lastLiveMoveMs, 1L)
            hypot(fx - prevX, fy - prevY) / (pdt / 1000f)
        }
        val thin = 1f - spec.speedThin * (speed / 1500f).coerceIn(0f, 1f)
        val target = liveBaseWidth * (spec.minScale + (spec.maxScale - spec.minScale) * pr) * thin
        liveWidth += 0.35f * (target - liveWidth)

        val idx = liveCount * STRIDE
        livePts[idx] = fx
        livePts[idx + 1] = fy
        livePts[idx + 2] = pressure
        livePts[idx + 3] = (timeMs - liveDownTime).toFloat()
        liveWidths[liveCount] = liveWidth
        liveCount++
        lastLiveMoveMs = timeMs

        liveDirty.union(fx, fy)
        rebuildLivePaths()
    }

    private fun rebuildLivePaths() {
        val cap = specOf(livePen).capOrdinal
        if (liveCount <= FROZEN_THRESHOLD) {
            livePath.rewind()
            painter.appendLivePath(livePts, liveCount, liveWidths, livePath, cap)
        } else {
            if (liveCount - liveFrozenCount >= FROZEN_CHUNK + LIVE_TAIL) {
                val from = (liveFrozenCount.coerceAtLeast(1)) - 1
                val until = liveCount - LIVE_TAIL
                if (until - from >= 2) {
                    painter.appendLivePathSlice(livePts, from, until, liveWidths, frozenPath, cap)
                    liveFrozenCount = until
                }
            }
            livePath.rewind()
            painter.appendLivePathSlice(livePts, liveFrozenCount, liveCount, liveWidths, livePath, cap)
        }
    }

    private fun ensureLiveCapacity(needed: Int) {
        if (needed <= livePts.size / STRIDE) return
        var cap = livePts.size / STRIDE
        while (cap < needed) cap *= 2
        livePts = livePts.copyOf(cap * STRIDE)
        liveWidths = liveWidths.copyOf(cap)
    }

    private fun endStroke(upTimeMs: Long, h: Host) {
        if (!liveActive) return
        liveActive = false
        drawingPointerId = -1
        if (liveCount == 0) {
            livePath.rewind(); frozenPath.rewind()
            return
        }

        val tool = h.tool()
        var pts = if (liveCount > 2) simplify(livePts.copyOf(liveCount * STRIDE), 0.15f)
        else livePts.copyOf(liveCount * STRIDE)

        // Hold-to-snap: the pen rested ~500 ms at the end (spec 6.7).
        if (tool.snapEnabled && upTimeMs - lastLiveMoveMs >= 400 && liveCount >= 8) {
            val xy = FloatArray(liveCount * 2)
            for (i in 0 until liveCount) {
                xy[2 * i] = livePts[i * STRIDE]
                xy[2 * i + 1] = livePts[i * STRIDE + 1]
            }
            val snap = recognize(xy)
            val id0 = h.doc.nextStrokeId()
            val ideal = snapToStroke(snap, tool, id0, pts)
            if (ideal != null) {
                val s = ideal
                h.history.push(AddStrokes(listOf(s)), h.doc)
                h.onContentCommitted()
                livePath.rewind(); frozenPath.rewind(); liveFrozenCount = 0
                invalidatePageRegion(s.bounds, h)
                liveDirty.reset()
                h.onViewportOrContentMayHaveChanged()
                return
            }
        }

        val id = h.doc.nextStrokeId()
        val s = Stroke(id, livePen, liveArgb, liveBaseWidth, pts)
        h.history.push(AddStrokes(listOf(s)), h.doc)
        h.onContentCommitted()

        livePath.rewind()
        frozenPath.rewind()
        liveFrozenCount = 0
        invalidatePageRegion(liveDirty, h)
        liveDirty.reset()
        h.onViewportOrContentMayHaveChanged()
    }

    private fun cancelStroke(h: Host) {
        liveActive = false
        drawingPointerId = -1
        livePath.rewind()
        frozenPath.rewind()
        invalidate()
    }

    // ------------------------------------------------------------ erasing

    private fun beginErase(e: MotionEvent, i: Int, h: Host) {
        erasing = true
        erasingPointerId = e.getPointerId(i)
        erasedThisGesture.clear()
        val tool = h.tool()
        eraseR = tool.eraserRadiusPt
        eraseX = Float.NaN
        eraseY = Float.NaN
        liveDirty.reset()
        if (!tool.eraserWholeStroke) {
            pixelCenters.clear()
            pixelHiddenIds.clear()
            pixelFragments.clear()
        }
        eraseSegment(e.getX(i), e.getY(i), h)
    }

    private fun eraseSegment(sx: Float, sy: Float, h: Host) {
        val t = h.transform
        if (!t.toPage(sx, sy, pagePoint)) return
        val px = pagePoint[0]
        val py = pagePoint[1]
        val x0 = if (eraseX.isNaN()) px else eraseX
        val y0 = if (eraseY.isNaN()) py else eraseY

        if (h.tool().eraserWholeStroke) {
            wholeStrokeErase(x0, y0, px, py, h)
        } else {
            pixelErase(x0, y0, px, py, h)
        }
        eraseX = px
        eraseY = py
        liveDirty.union(x0, y0)
        liveDirty.union(px, py)
    }

    private val eraseQuery = HashSet<Int>()
    private val eraseBounds = Bounds()

    private fun wholeStrokeErase(x0: Float, y0: Float, x1: Float, y1: Float, h: Host) {
        val r = eraseR
        eraseBounds.reset()
        eraseBounds.union(x0 - r, y0 - r)
        eraseBounds.union(x0 + r, y0 + r)
        eraseBounds.union(x1 - r, y1 - r)
        eraseBounds.union(x1 + r, y1 + r)

        // objects: erasing over an object removes it (undoable)
        val objs = h.doc.objectsSnapshot()
        val hitObj = objectPainter.hitTest(objs, x1, y1, r * 0.5f)
        if (hitObj != null) {
            h.history.push(RemoveObjects(listOf(hitObj)), h.doc)
            h.onContentCommitted()
            invalidate()
        }

        h.doc.grid.query(eraseBounds, eraseQuery)
        val hits = ArrayList<Int>(4)
        for (id in eraseQuery) {
            val s = h.doc.strokeById(id) ?: continue
            if (eraserHits(x0, y0, x1, y1, r, s)) hits.add(id)
        }
        if (hits.isNotEmpty()) {
            val removed = ArrayList<Stroke>(hits.size)
            for (id in hits) {
                val s = h.doc.strokeById(id)
                if (s != null) removed.add(s)
            }
            h.doc.removeStrokes(hits)
            erasedThisGesture.addAll(removed)
            invalidatePageRegion(eraseBounds, h)
        }
    }

    private fun pixelErase(x0: Float, y0: Float, x1: Float, y1: Float, h: Host) {
        val r = h.tool().eraserRadiusPt
        // sample the segment every r/2 and accumulate circle centers
        val steps = maxOf(1, (hypot(x1 - x0, y1 - y0) / (r / 2f)).toInt())
        for (k in 0..steps) {
            pixelCenters.add(x0 + (x1 - x0) * k / steps)
            pixelCenters.add(y0 + (y1 - y0) * k / steps)
        }

        pixelScratch.reset()
        pixelScratch.union(x0 - r, y0 - r)
        pixelScratch.union(x1 + r, y1 + r)
        h.doc.grid.query(pixelScratch, pixelQuery)

        val centers = pixelCenters.toFloatArray()
        for (id in pixelQuery) {
            if (id in pixelHiddenIds) continue
            val s = h.doc.strokeById(id) ?: continue
            val replaced = pixelErasePath(s, centers, r) { h.doc.nextStrokeId() }
            if (replaced != null) {
                pixelHiddenIds.add(id)
                pixelFragments.addAll(replaced)
                pixelScratch.union(s.bounds)
            }
        }
        if (pixelHiddenIds.isNotEmpty()) {
            val hset = h.hiddenStrokeIds
            pixelHiddenIds.forEach { hset.add(it) }
            h.tileCache.invalidateAll()
            invalidate()
        }
    }

    private fun endPixelEraseDiscard() {
        pixelCenters.clear()
        pixelHiddenIds.clear()
        pixelFragments.clear()
        host?.hiddenStrokeIds?.clear()
    }

    private fun endEraseGesture(h: Host) {
        if (!erasing) return
        erasing = false
        erasingPointerId = -1

        val tool = h.tool()
        if (!tool.eraserWholeStroke) {
            // commit one ReplaceStrokes for the whole pixel-erase gesture
            val originals = ArrayList<Stroke>(pixelHiddenIds.size)
            for (id in pixelHiddenIds) {
                val s = h.doc.strokeById(id)
                if (s != null) originals.add(s)
            }
            if (originals.isNotEmpty()) {
                val cmd = ReplaceStrokes(originals, ArrayList(pixelFragments))
                h.history.push(cmd, h.doc)
                h.onContentCommitted()
            }
            pixelCenters.clear()
            pixelHiddenIds.clear()
            pixelFragments.clear()
            h.hiddenStrokeIds.clear()
        } else if (erasedThisGesture.isNotEmpty()) {
            h.history.pushApplied(ReplaceStrokes(erasedThisGesture.toList(), emptyList()))
            h.onContentCommitted()
            erasedThisGesture.clear()
        }

        eraseY = Float.NaN
        h.onViewportOrContentMayHaveChanged()
        invalidate()
    }

    // ------------------------------------------------------------ lasso

    private fun beginLasso(h: Host, px: Float, py: Float) {
        val ls = LassoState()
        ls.polygon.add(px); ls.polygon.add(py)
        lasso = ls
        invalidate()
    }

    private fun finishLassoCapture(h: Host) {
        val ls = lasso ?: return
        val poly = ls.polygon.toFloatArray()

        lassoBounds.reset()
        for (i in ls.polygon.indices step 2) lassoBounds.union(ls.polygon[i], ls.polygon[i + 1])

        h.doc.grid.query(lassoBounds, lassoQuery)
        for (id in lassoQuery) {
            val s = h.doc.strokeById(id) ?: continue
            if (s.count == 0) continue
            var inside = 0
            for (i in 0 until s.count) {
                if (pointInPolygon(s.x(i), s.y(i), poly)) inside++
            }
            if (inside * 2 >= s.count) {
                ls.selectedIds.add(id)
                ls.originals.add(s)
            }
        }

        if (ls.selectedIds.isEmpty()) {
            lasso = null
        } else {
            ls.finished = true
            val b = Bounds()
            for (s in ls.originals) b.union(s.bounds)
            ls.selectionBounds = b
            ls.selectedIds.forEach { h.hiddenStrokeIds.add(it) }
            h.tileCache.invalidateAll()
        }
        invalidate()
    }

    private fun pointInSelection(px: Float, py: Float, ls: LassoState): Boolean {
        val b = ls.selectionBounds
        val c = floatArrayOf(b.left, b.top, b.right, b.top, b.right, b.bottom, b.left, b.bottom)
        ls.preview.mapPoints(c, 0, 4)
        val poly = floatArrayOf(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7])
        return pointInPolygon(px, py, poly)
    }

    /** Commits the current transform as one ReplaceStrokes, clears the lasso. */
    fun commitLasso(h: Host? = host) {
        val hh = h ?: return
        val ls = lasso ?: return
        if (ls.originals.isEmpty()) {
            lasso = null
            invalidate()
            return
        }
        val moved = ArrayList<Stroke>(ls.originals.size)
        for (s in ls.originals) {
            moved.add(s.transformed(ls.preview, hh.doc.nextStrokeId()))
        }
        hh.history.push(ReplaceStrokes(ArrayList(ls.originals), moved), hh.doc)
        hh.onContentCommitted()
        ls.selectedIds.forEach { hh.hiddenStrokeIds.remove(it) }
        hh.tileCache.invalidateAll()
        lasso = null
        invalidate()
    }

    fun lassoSelection(): List<Stroke> {
        val ls = lasso ?: return emptyList()
        return ls.originals.map { it.transformed(ls.preview, it.id) }
    }

    fun hasSelection(): Boolean = lasso?.finished == true

    fun clearSelectionOnly(h: Host? = host) {
        val hh = h ?: return
        val ls = lasso ?: return
        ls.selectedIds.forEach { hh.hiddenStrokeIds.remove(it) }
        lasso = null
        hh.tileCache.invalidateAll()
        invalidate()
    }

    fun deleteSelection(h: Host? = host) {
        val hh = h ?: return
        val ls = lasso ?: return
        if (ls.originals.isEmpty()) return
        hh.history.push(ReplaceStrokes(ArrayList(ls.originals), emptyList()), hh.doc)
        hh.onContentCommitted()
        ls.selectedIds.forEach { hh.hiddenStrokeIds.remove(it) }
        hh.tileCache.invalidateAll()
        lasso = null
        invalidate()
    }

    fun recolorSelection(argb: Int, h: Host? = host) {
        val hh = h ?: return
        val ls = lasso ?: return
        if (ls.originals.isEmpty()) return
        val recolored = ls.originals.map {
            Stroke(it.id, it.pen, argb, it.baseWidth, it.pts)
        }
        hh.history.push(ReplaceStrokes(ArrayList(ls.originals), recolored), hh.doc)
        hh.onContentCommitted()
        // keep selection (now the recolored strokes are the originals)
        for (i in ls.originals.indices) {
            ls.originals[i] = recolored[i]
        }
        invalidate()
    }

    // ------------------------------------------------------------ shapes

    private fun beginShape(h: Host, px: Float, py: Float) {
        shapeActive = true
        shapeX0 = px; shapeY0 = py
        shapeX1 = px; shapeY1 = py
        invalidate()
    }

    private fun endShape(h: Host) {
        shapeActive = false
        val tool = h.tool()
        val s = buildShapeStroke(tool, h.doc.nextStrokeId(), shapeX0, shapeY0, shapeX1, shapeY1, 0f)
        if (s != null) {
            h.history.push(AddStrokes(listOf(s)), h.doc)
            h.onContentCommitted()
            invalidatePageRegion(s.bounds, h)
        }
        h.onViewportOrContentMayHaveChanged()
    }

    private fun buildShapeStroke(
        tool: ToolSpec,
        id: Int,
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        t0: Float
    ): Stroke? {
        val pen = tool.pen
        val argb = tool.colorArgb
        val w = tool.widthPt
        return when (tool.shapeKind) {
            com.nanote.ink.engine.ShapeKind.LINE ->
                com.nanote.ink.engine.buildLineStroke(x0, y0, x1, y1, id, pen, argb, w, t0)
            com.nanote.ink.engine.ShapeKind.ARROW ->
                com.nanote.ink.engine.buildArrowStroke(x0, y0, x1, y1, id, pen, argb, w, t0)
            com.nanote.ink.engine.ShapeKind.RECT ->
                com.nanote.ink.engine.buildRectStroke(x0, y0, x1, y1, id, pen, argb, w, t0)
            com.nanote.ink.engine.ShapeKind.ELLIPSE ->
                com.nanote.ink.engine.buildEllipseStroke(
                    (x0 + x1) / 2f, (y0 + y1) / 2f,
                    kotlin.math.abs(x1 - x0) / 2f, kotlin.math.abs(y1 - y0) / 2f,
                    id, pen, argb, w, t0
                )
            com.nanote.ink.engine.ShapeKind.TRIANGLE ->
                com.nanote.ink.engine.buildTriangleStroke(x0, y0, x1, y1, id, pen, argb, w, t0)
            com.nanote.ink.engine.ShapeKind.DIAMOND ->
                com.nanote.ink.engine.buildDiamondStroke(x0, y0, x1, y1, id, pen, argb, w, t0)
            com.nanote.ink.engine.ShapeKind.STAR ->
                com.nanote.ink.engine.buildStarStroke(x0, y0, x1, y1, id, pen, argb, w, t0)
        }
    }

    private fun snapToStroke(
        snap: Snap?,
        tool: ToolSpec,
        id: Int,
        originalPts: FloatArray
    ): Stroke? {
        val pen = tool.pen
        val argb = tool.colorArgb
        val w = tool.widthPt
        val t0 = 0f
        return when (snap) {
            is Snap.Line -> com.nanote.ink.engine.buildLineStroke(snap.x1, snap.y1, snap.x2, snap.y2, id, pen, argb, w, t0)
            is Snap.Ellipse -> com.nanote.ink.engine.buildEllipseStroke(
                snap.cx, snap.cy, snap.rx, snap.ry, id, pen, argb, w, t0
            )
            is Snap.Poly -> com.nanote.ink.engine.buildPolyStroke(snap.xy, id, pen, argb, w, t0)
            null -> null
        }
    }

    // ------------------------------------------------------------ objects

    private fun endObjectDrag(h: Host) {
        val obj = draggedObject
        draggedObject = null
        if (obj == null) return
        h.hiddenObjectIds.remove(obj.id)
        if (!dragMoved) {
            h.tileCache.invalidateAll()
            invalidate()
            return
        }
        val dx = pagePoint[0] - dragStartPageX
        val dy = pagePoint[1] - dragStartPageY
        when (obj) {
            is TextBox -> {
                val nm = obj.m.toMutableList()
                nm[2] = nm[2] + dx
                nm[5] = nm[5] + dy
                h.history.push(ReplaceObject(obj.id, obj, obj.copy(m = nm)), h.doc)
            }
            is ImageObj -> {
                val nm = obj.m.toMutableList()
                nm[2] = nm[2] + dx
                nm[5] = nm[5] + dy
                h.history.push(ReplaceObject(obj.id, obj, obj.copy(m = nm)), h.doc)
            }
            else -> Unit
        }
        h.onContentCommitted()
        h.tileCache.invalidateAll()
        invalidate()
    }

    // ------------------------------------------------------------ laser

    private fun beginLaser(h: Host, px: Float, py: Float) {
        laserActive = true
        laserPts.add(px); laserPts.add(py); laserPts.add(android.os.SystemClock.uptimeMillis().toFloat())
        postInvalidateOnAnimation()
    }

    // ---------------------------------------------------------- invalidation

    private fun invalidateLiveDirty(h: Host) {
        if (!liveDirty.isEmpty) {
            val b = Bounds()
            b.set(liveDirty)
            invalidatePageRegion(b, h)
            liveDirty.reset()
        }
        if (erasing && !eraseY.isNaN()) {
            invalidate()
        }
    }

    private fun invalidatePageRegion(b: Bounds, h: Host) {
        if (b.isEmpty) return
        val s = h.transform.scale
        val tx = h.transform.transX
        val ty = h.transform.transY
        val left = (b.left * s + tx).toInt() - 4
        val top = (b.top * s + ty).toInt() - 4
        val right = (b.right * s + tx).toInt() + 4
        val bottom = (b.bottom * s + ty).toInt() + 4
        scratchRect.set(left, top, right, bottom)
        invalidate(scratchRect)
    }

    // ---------------------------------------------------------- double tap

    private fun animateZoomToggle(sx: Float, sy: Float) {
        val h = host ?: return
        val t = h.transform
        zoomAnimator?.cancel()

        val s0 = t.scale
        val s1 = if (s0 > 2f) s0 * 0.5f else s0 * 2f

        val p = FloatArray(2)
        if (!t.toPage(sx, sy, p)) return

        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 220
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener { a ->
            val f = a.animatedFraction
            val s = s0 + (s1 - s0) * f
            val tx = sx - s * p[0]
            val ty = sy - s * p[1]
            t.setScaleTranslate(t.clampScale(s), tx, ty)
            t.clampPan(h.doc.widthPt, h.doc.heightPt, width.toFloat(), height.toFloat())
            invalidate()
        }
        zoomAnimator = anim
        anim.start()
    }

    companion object {
        private const val INIT_LIVE_POINTS = 512
        private const val FROZEN_THRESHOLD = 400
        private const val FROZEN_CHUNK = 128
        private const val LIVE_TAIL = 24
        private const val LASER_FADE_MS = 550f

        /** Mirrors InputRouter's default palm contact-patch threshold. */
        private const val PALM_MAJOR_PX = 30f
    }

    /** v1.1 pen-only mode: promote IGNOREd small fingers to NAVIGATE when a
     *  deliberate second finger joins — pinch-zoom must keep working. */
    private fun promoteIgnoredFingers(e: MotionEvent) {
        for (p in 0 until e.pointerCount) {
            val pid = e.getPointerId(p)
            if (routes.get(pid) == Route.IGNORE &&
                e.getToolType(p) == ToolTypes.FINGER &&
                e.getTouchMajor(p) <= PALM_MAJOR_PX) {
                routes.put(pid, Route.NAVIGATE)
            }
        }
    }
}
