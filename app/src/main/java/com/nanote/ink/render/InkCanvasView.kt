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
import android.widget.OverScroller
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
import com.nanote.ink.engine.PageStripLayout
import com.nanote.ink.engine.PenSpec
import com.nanote.ink.engine.PointerSpec
import com.nanote.ink.engine.RemoveObjects
import com.nanote.ink.engine.ReplaceObject
import com.nanote.ink.engine.ReplaceStrokes
import com.nanote.ink.engine.Route
import com.nanote.ink.engine.StripGeom
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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.pow

/**
 * One page of the continuous vertical strip as seen by the canvas. [host] is
 * null for pages the ViewModel has not loaded into the window yet.
 */
class StripSlot(
    override val index: Int,
    override val topPt: Float,
    val leftPt: Float,
    val widthPt: Float,
    override val heightPt: Float,
    val host: InkCanvasView.Host?
) : StripGeom

/** Strip state published by the editor ViewModel. */
class StripState(
    val slots: List<StripSlot>,
    val activeIndex: Int,
    val totalH: Float,
    val fitToken: Long,
    /** >= 0 -> animate the viewport so this page lands centered. */
    val centerOn: Int
)

/**
 * The drawing surface (spec 2/6/7 + v1.2 continuous strip). M3 tool set:
 *  - pens + hold-to-snap shape recognition (pure geometry)
 *  - shapes tool (line/arrow/rect/ellipse/triangle/diamond/star) with live preview
 *  - whole-stroke eraser AND pixel eraser (live fragment preview, one undo step)
 *  - lasso: select (>=50% points inside), move/resize/rotate (one composed
 *    matrix, committed on release), copy/paste/delete/recolor
 *  - text and image objects with drag-to-move; eraser removes them (undoable)
 *  - laser pointer with a fading trail (never saved)
 *
 * v1.2 (Notewise-style browsing): all pages live in one continuous vertical
 * strip. One finger scrolls, two fingers pinch-zoom, a flick flings, the
 * pen writes on whichever page it lands. The strip transform is owned here
 * (stripScale/stripOffX/stripOffY); each loaded page's ViewTransform is a
 * derived local matrix pushed from it.
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

        /** v1.2: PDF-backed pages open fit-to-PAGE (whole page visible). */
        val isPdfPage: Boolean get() = false

        /** v1.1: palm rejection — only the stylus WRITES (fingers still scroll). */
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

    // per-gesture OWNER hosts (v1.2: a gesture may target any loaded page)
    private var liveHost: Host? = null
    private var eraseHost: Host? = null
    private var shapeHost: Host? = null
    private var dragHost: Host? = null
    private var laserHost: Host? = null
    private var lassoHost: Host? = null

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
    private var centerAnimator: ValueAnimator? = null

    private val tileRect = Rect()
    private val lassoCornerScratch = FloatArray(8)

    // ------------------------------------------------- v1.2 strip state
    private var stripSlots: List<StripSlot> = emptyList()
    private var stripTotalH = 0f
    private var stripFitToken = -1L
    private var lastStripState: StripState? = null
    private var stripScale = 1f
    private var stripOffX = 0f
    private var stripOffY = 0f
    private var stripMinScale = 0.25f
    private var activeIndex = 0
    private var fittedVw = 0f
    private var fittedVh = 0f

    /** Viewport-center page changed during scrolling (index). */
    var onActivePageChanged: ((Int) -> Unit)? = null

    /** The pen landed on a page that is not loaded yet — ask for a load. */
    var onPageNeedsLoad: ((Int) -> Unit)? = null

    private val scroller = OverScroller(context)
    private val navT = LongArray(NAV_TRACK_N)
    private val navXs = FloatArray(NAV_TRACK_N)
    private val navYs = FloatArray(NAV_TRACK_N)
    private var navN = 0
    private var navPos = 0
    private var flingLastY = 0

    fun attach(h: Host) {
        host = h
        installTileCallback(h.transform, h.tileCache)
        invalidate()
        // Objects are drawn by the view every frame; tiles carry paper + ink only.
    }

    fun detach() {
        host = null
        zoomAnimator?.cancel()
        centerAnimator?.cancel()
        scroller.forceFinished(true)
        ioExecutor.shutdownNow()
    }

    /**
     * Publishes the full page strip. [state] comes from the ViewModel;
     * identical instances are ignored so Compose recomposition is free.
     * A NEW fitToken refits the viewport (open / page-settings change);
     * window slides keep the token and therefore keep the user's pan/zoom.
     */
    fun setStrip(state: StripState) {
        if (state === lastStripState) return
        lastStripState = state
        val refit = state.fitToken != stripFitToken ||
            width.toFloat() != fittedVw || height.toFloat() != fittedVh
        stripSlots = state.slots
        stripTotalH = state.totalH
        stripFitToken = state.fitToken
        scroller.forceFinished(true)
        centerAnimator?.cancel()
        for (s in state.slots) {
            s.host?.let { installTileCallback(it.transform, it.tileCache) }
        }
        setActiveIndex(state.activeIndex, notify = false)
        recomputeFloor()
        if (refit) refitStrip() else pushTransforms()
        if (state.centerOn >= 0) animateCenterPage(state.centerOn)
        invalidate()
    }

    private fun installTileCallback(t: ViewTransform, cache: InkTileCache) {
        cache.onTileReadyCallback = { info ->
            val ts = 2.0.pow(info.bucket / 2.0).toFloat()
            val k = t.scale / ts
            val size = (InkTileCache.TILE_PX * k).toInt()
            val left = (info.tx * InkTileCache.TILE_PX * k).toInt()
            val top = (info.ty * InkTileCache.TILE_PX * k).toInt()
            tileRect.set(left, top, left + size + 2, top + size + 2)
            tileRect.offset(t.transX.toInt(), t.transY.toInt())
            invalidate(tileRect)
        }
    }

    /** Derives every loaded page's local matrix from the strip transform. */
    private fun pushTransforms() {
        val s = stripScale
        for (slot in stripSlots) {
            val h = slot.host ?: continue
            h.transform.setScaleTranslate(
                s,
                stripOffX + slot.leftPt * s,
                stripOffY + slot.topPt * s
            )
        }
    }

    /** Zoom-out floor: the largest page in the notebook can still fit on screen. */
    private fun recomputeFloor() {
        var floor = 0.25f
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw > 0f && vh > 0f) {
            for (slot in stripSlots) {
                val fit = minOf(
                    vw / maxOf(slot.widthPt, 1f),
                    vh / maxOf(slot.heightPt, 1f)
                )
                if (fit < floor) floor = fit
            }
        }
        stripMinScale = floor
    }

    /**
     * Fits the ACTIVE page (PDF pages fit-to-page so the whole page is
     * visible; plain paper fits to width as before) and syncs the strip.
     */
    private fun refitStrip() {
        if (stripSlots.isEmpty() || width <= 0 || height <= 0) return
        fittedVw = width.toFloat()
        fittedVh = height.toFloat()
        recomputeFloor()
        var slot = stripSlots.getOrNull(activeIndex)
        if (slot == null || slot.host == null) slot = stripSlots.firstOrNull { it.host != null }
        if (slot == null) return
        val h = slot.host ?: return
        val t = h.transform
        val vw = fittedVw
        val vh = fittedVh
        if (h.isPdfPage) {
            t.fitToPage(slot.widthPt, slot.heightPt, vw, vh)
        } else {
            t.fitToWidth(slot.widthPt, slot.heightPt, vw, vh)
        }
        stripScale = t.scale
        stripOffX = t.transX - slot.leftPt * stripScale
        stripOffY = t.transY - slot.topPt * stripScale
        clampStrip()
        pushTransforms()
        updateActiveFromViewport(notify = false)
    }

    private fun clampStrip() {
        val a = stripSlots.getOrNull(activeIndex)
        stripOffY = PageStripLayout.clampOffsetY(
            stripOffY, stripScale, stripTotalH, height.toFloat()
        )
        if (a != null) {
            stripOffX = PageStripLayout.clampOffsetX(
                stripOffX, stripScale, a.leftPt, a.widthPt, width.toFloat()
            )
        }
    }

    /** Active page = the page under the viewport CENTER; notifies the VM. */
    private fun updateActiveFromViewport(notify: Boolean) {
        if (stripSlots.isEmpty()) return
        val stripY = (height / 2f - stripOffY) / stripScale
        val idx = PageStripLayout.indexAt(stripSlots, stripY)
        if (idx < 0) return
        if (idx != activeIndex || host == null) {
            setActiveIndex(idx, notify)
        }
    }

    private fun setActiveIndex(idx: Int, notify: Boolean) {
        activeIndex = idx
        val h = stripSlots.getOrNull(idx)?.host
        host = h
        if (notify) {
            onActivePageChanged?.invoke(idx)
            if (h == null) onPageNeedsLoad?.invoke(idx)
        }
    }

    /** Animates the strip so page [index] lands centered (sidebar taps). */
    private fun animateCenterPage(index: Int) {
        val slot = stripSlots.getOrNull(index) ?: return
        scroller.forceFinished(true)
        val vh = height.toFloat()
        val target = vh / 2f - (slot.topPt + slot.heightPt / 2f) * stripScale
        val y0 = stripOffY
        val y1 = PageStripLayout.clampOffsetY(target, stripScale, stripTotalH, vh)
        if (abs(y1 - y0) < 1f) {
            stripOffY = y1
            clampStrip()
            pushTransforms()
            updateActiveFromViewport(notify = true)
            invalidate()
            return
        }
        centerAnimator?.cancel()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                stripOffY = y0 + (y1 - y0) * a.animatedFraction
                clampStrip()
                pushTransforms()
                updateActiveFromViewport(notify = true)
                invalidate()
            }
        }
        centerAnimator = anim
        anim.start()
    }

    /** The loaded page slot under a screen point (nearest page if in a gap). */
    private fun slotAt(sx: Float, sy: Float): StripSlot? {
        val slots = stripSlots
        if (slots.isEmpty()) return null
        val stripY = (sy - stripOffY) / stripScale
        val idx = PageStripLayout.indexAt(slots, stripY)
        return slots.getOrNull(idx)
    }

    private fun slotIntersectsViewport(s: StripSlot, vw: Float, vh: Float): Boolean {
        val top = stripOffY + s.topPt * stripScale
        if (top > vh + 64f) return false
        if (top + s.heightPt * stripScale < -64f) return false
        val left = stripOffX + s.leftPt * stripScale
        if (left > vw + 64f) return false
        return left + s.widthPt * stripScale >= -64f
    }

    private fun setSlotMatrix(t: ViewTransform) {
        val v = t.m.v
        matrixValues[0] = v[0]; matrixValues[1] = v[1]; matrixValues[2] = v[2]
        matrixValues[3] = v[3]; matrixValues[4] = v[4]; matrixValues[5] = v[5]
        matrixValues[6] = 0f; matrixValues[7] = 0f; matrixValues[8] = 1f
        androidMatrix.setValues(matrixValues)
    }

    // ------------------------------------------------------------------ draw

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && stripSlots.isNotEmpty()) refitStrip()
    }

    /** Kept for the Compose update loop: refits only when the view resized. */
    fun refitViewportIfNeeded() {
        if (stripSlots.isEmpty()) return
        if (width.toFloat() != fittedVw || height.toFloat() != fittedVh) refitStrip()
    }

    override fun onDraw(canvas: Canvas) {
        val slots = stripSlots
        if (slots.isEmpty()) return
        val anyHost = slots.firstOrNull { it.host != null }?.host ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()

        canvas.drawColor(anyHost.surfaceColor())

        // committed tiles per page (screen space)
        for (s in slots) {
            val h = s.host ?: continue
            if (!slotIntersectsViewport(s, vw, vh)) continue
            h.tileCache.drawInto(canvas, stripScale, h.transform.transX, h.transform.transY, vw, vh, h.doc)
        }

        // page-space pass per page (objects + per-gesture overlays)
        for (s in slots) {
            val h = s.host ?: continue
            if (!slotIntersectsViewport(s, vw, vh)) continue

            setSlotMatrix(h.transform)
            val save = canvas.save()
            canvas.setMatrix(androidMatrix)

            pageBorderPaint.color = 0x33282828
            pageBorderPaint.strokeWidth = 1f / stripScale
            canvas.drawRect(0f, 0f, s.widthPt, s.heightPt, pageBorderPaint)

            // objects (except dragged/hidden)
            val objs = h.doc.objectsSnapshot().filter { it.id !in h.hiddenObjectIds }
            if (objs.isNotEmpty()) objectPainter.drawObjects(canvas, objs)

            // pixel-erase fragments live preview
            if (h === eraseHost && pixelFragments.isNotEmpty()) {
                for (st in pixelFragments) {
                    painter.configure(st.pen, st.argb)
                    previewPath.rewind()
                    painter.appendStrokePath(st, previewPath)
                    canvas.drawPath(previewPath, painter.paint)
                }
            }

            // shape tool preview
            if (h === shapeHost && shapeActive) {
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
            if (h === lassoHost && ls != null) {
                if (!ls.finished && ls.polygon.size >= 4) {
                    previewPath.rewind()
                    previewPath.moveTo(ls.polygon[0], ls.polygon[1])
                    for (i in 2 until ls.polygon.size step 2) {
                        previewPath.lineTo(ls.polygon[i], ls.polygon[i + 1])
                    }
                    lassoPaint.strokeWidth = 1.2f / stripScale
                    canvas.drawPath(previewPath, lassoPaint)
                }
                if (ls.selectionBounds.let { !it.isEmpty }) {
                    selBoxPaint.strokeWidth = 1.2f / stripScale
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
                    for (st in ls.originals) {
                        val mapped = st.transformed(ls.preview, st.id)
                        painter.configure(mapped.pen, mapped.argb)
                        previewPath.rewind()
                        painter.appendStrokePath(mapped, previewPath)
                        canvas.drawPath(previewPath, painter.paint)
                    }
                }
            }

            // dragged object preview at new position
            if (h === dragHost && draggedObject != null) {
                drawDraggedPreview(canvas, draggedObject!!)
            }

            // live stroke
            if (h === liveHost && liveActive && liveCount > 0) {
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
            if (h === laserHost && laserPts.isNotEmpty()) {
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
        }

        // eraser ring cursor (screen space)
        if (erasing && !eraseY.isNaN()) {
            val t = eraseHost?.transform ?: anyHost.transform
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
        val h = host ?: stripSlots.firstOrNull { it.host != null }?.host ?: return false
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
        navClear()
        scroller.forceFinished(true)
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

        // v1.2: content gestures target the page actually under the pointer
        var tgt: Host = h
        if (route == Route.DRAW || route == Route.ERASE || route == Route.LASSO) {
            val slot = slotAt(e.x, e.y)
            if (slot != null && slot.host == null) {
                // page not loaded yet (fast scroll): ask for it, ignore gesture
                onPageNeedsLoad?.invoke(slot.index)
                routes.put(e.getPointerId(0), Route.IGNORE)
                return true
            }
            if (slot != null) tgt = slot.host!!
        }

        // resolve page point on the target page
        val ok = tgt.transform.toPage(e.x, e.y, pagePoint)
        val px = pagePoint[0]
        val py = pagePoint[1]

        // active selection? manipulate it instead of drawing
        val ls = lasso
        if (ls != null && ls.finished && route == Route.DRAW) {
            val inside = pointInSelection(px, py, ls)
            if (!inside) {
                commitLasso()
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
                EditorTool.LASSO -> beginLasso(tgt, px, py)
                EditorTool.SHAPE -> beginShape(tgt, px, py)
                EditorTool.LASER -> beginLaser(tgt, px, py)
                EditorTool.TEXT -> {
                    // tap handled on UP (no move)
                }
                EditorTool.IMAGE -> { /* tap handled on UP */ }
                EditorTool.ERASER -> beginErase(e, 0, tgt)
                EditorTool.HIGHLIGHTER -> beginStroke(e, 0, tgt) // separated highlighter tool
                EditorTool.PEN -> {
                    // object drag? (text/image under the pen)
                    if (ok) {
                        val hit = objectPainter.hitTest(tgt.doc.objectsSnapshot(), px, py)
                        if (hit != null) {
                            draggedObject = hit
                            dragHost = tgt
                            dragStartPageX = px
                            dragStartPageY = py
                            dragMoved = false
                            tgt.hiddenObjectIds.add(hit.id)
                            tgt.tileCache.invalidateAll()
                            return true
                        }
                    }
                    beginStroke(e, 0, tgt)
                }
            }
            Route.ERASE -> beginErase(e, 0, tgt)
            Route.LASSO -> {
                // v1.1: barrel-button lasso — the current tool does not matter
                if (ok && lasso == null) beginLasso(tgt, px, py)
            }
            Route.COMMAND -> {
                // v1.1: barrel-button undo/redo fires once on press
                h.onBarrelCommand(h.stylusBarrelAction())
            }
            Route.NAVIGATE -> {
                gestures.begin(e.x, e.y, e.x, e.y)
                navPointerCount = 1
                navTrack(e.x, e.y, e.eventTime)
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

        // v1.2: a second finger ALWAYS means navigation — if the first finger
        // was mid-stroke (finger drawing enabled), discard that stroke and
        // promote both pointers so pinch-zoom works in EVERY mode.
        if (e.pointerCount == 2 && route == Route.NAVIGATE &&
            e.getToolType(i) == ToolTypes.FINGER
        ) {
            val otherId = e.getPointerId(if (i == 0) 1 else 0)
            if (routes.get(otherId) == Route.DRAW &&
                liveActive && drawingPointerId == otherId
            ) {
                cancelStroke(liveHost ?: h)
                routes.put(otherId, Route.NAVIGATE)
            }
        }

        when (route) {
            Route.NAVIGATE -> {
                // v1.1: in pen-only mode an ignored small finger joins a
                // deliberate pinch — promote so pan/zoom keeps working.
                if (h.stylusOnly()) promoteIgnoredFingers(e)
                recountNav(e)
                if (navPointerCount == 2) {
                    val a = navigatePointer(e, 0)
                    val b = navigatePointer(e, 1)
                    if (a != null && b != null) gestures.begin(a[0], a[1], b[0], b[1])
                }
            }
            Route.DRAW -> if (!liveActive) beginStrokeOnSlot(e, i, h)
            Route.ERASE -> if (!erasing) beginEraseOnSlot(e, i, h)
            Route.LASSO -> {
                val slot = slotAt(e.getX(i), e.getY(i))
                val tgt = slot?.host
                if (tgt != null) {
                    if (lasso == null && tgt.transform.toPage(e.getX(i), e.getY(i), pagePoint)) {
                        beginLasso(tgt, pagePoint[0], pagePoint[1])
                    }
                } else if (slot != null) {
                    onPageNeedsLoad?.invoke(slot.index)
                }
            }
            Route.COMMAND -> Unit // fired once on DOWN
            Route.IGNORE -> Unit
        }
        return true
    }

    /** Resolves the page under the new pointer and starts a stroke there. */
    private fun beginStrokeOnSlot(e: MotionEvent, i: Int, fallback: Host) {
        val slot = slotAt(e.getX(i), e.getY(i))
        if (slot != null && slot.host == null) {
            onPageNeedsLoad?.invoke(slot.index)
            routes.put(e.getPointerId(i), Route.IGNORE)
            return
        }
        beginStroke(e, i, slot?.host ?: fallback)
    }

    private fun beginEraseOnSlot(e: MotionEvent, i: Int, fallback: Host) {
        val slot = slotAt(e.getX(i), e.getY(i))
        if (slot != null && slot.host == null) {
            onPageNeedsLoad?.invoke(slot.index)
            routes.put(e.getPointerId(i), Route.IGNORE)
            return
        }
        beginErase(e, i, slot?.host ?: fallback)
    }

    private fun recountNav(e: MotionEvent) {
        var n = 0
        for (p in 0 until e.pointerCount) {
            if (routes.get(e.getPointerId(p)) == Route.NAVIGATE) n++
        }
        navPointerCount = n
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
            val lh = lassoHost ?: h
            if (ls.mode == 1) {
                if (lh.transform.toPage(e.x, e.y, pagePoint)) {
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
                if (lh.transform.toPage(cx, cy, pc)) {
                    val dAngle = angle - tgLastAngle
                    if (span > 1f && tgLastSpan > 1f) {
                        val k = span / tgLastSpan
                        ls.preview.postScale(k, k, pc[0], pc[1])
                    }
                    if (abs(dAngle) > 0.01f) {
                        ls.preview.postRotate(dAngle, pc[0], pc[1])
                    }
                    val dx = cx - tgLastCx
                    val dy = cy - tgLastCy
                    if (dx != 0f || dy != 0f) {
                        val pd = floatArrayOf(dx, dy)
                        lh.transform.m.inverseMapPoints(pd, 0, 1)
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
            val dh = dragHost ?: h
            if (dh.transform.toPage(e.x, e.y, pagePoint)) {
                dragMoved = true
                invalidate()
            }
            return true
        }

        // laser
        if (laserActive) {
            val lh = laserHost ?: h
            if (lh.transform.toPage(e.x, e.y, pagePoint)) {
                laserPts.add(pagePoint[0]); laserPts.add(pagePoint[1]); laserPts.add(e.eventTime.toFloat())
                postInvalidateOnAnimation()
            }
            return true
        }

        // lasso capture
        val ls2 = lasso
        if (ls2 != null && !ls2.finished) {
            val lh = lassoHost ?: h
            if (lh.transform.toPage(e.x, e.y, pagePoint)) {
                ls2.polygon.add(pagePoint[0]); ls2.polygon.add(pagePoint[1])
                invalidate()
            }
            return true
        }

        // shape preview
        if (shapeActive) {
            val sh = shapeHost ?: h
            if (sh.transform.toPage(e.x, e.y, pagePoint)) {
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

        if (liveActive || erasing) invalidateLiveDirty()
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
        fallback: Host
    ) {
        when (route) {
            Route.DRAW -> {
                val lh = liveHost ?: fallback
                if (e.getPointerId(p) == drawingPointerId) addLivePoint(sx, sy, pressure, timeMs, lh)
            }
            Route.ERASE -> {
                val eh = eraseHost ?: fallback
                if (e.getPointerId(p) == erasingPointerId) eraseSegment(sx, sy, eh)
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
            val lh = lassoHost ?: h
            if (lh.transform.toPage(e.getX(i), e.getY(i), pagePoint)) {
                lassoMoveLastX = pagePoint[0]
                lassoMoveLastY = pagePoint[1]
            }
            return true
        }

        when (route) {
            Route.DRAW -> if (id == drawingPointerId) endStroke(e.eventTime, liveHost ?: h)
            Route.ERASE -> if (id == erasingPointerId) endEraseGesture(eraseHost ?: h)
            Route.LASSO -> Unit // barrel-lasso: capture continues; ends on final UP
            Route.COMMAND -> Unit // one-shot command already fired on DOWN
            Route.NAVIGATE -> {
                recountNav(e)
                if (navPointerCount <= 1) {
                    val remaining = navigatePointer(e, 0)
                    if (remaining != null) {
                        gestures.begin(remaining[0], remaining[1], remaining[0], remaining[1])
                        navTrack(remaining[0], remaining[1], e.eventTime)
                    } else {
                        gestures.end()
                    }
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
            finishLassoCapture(lassoHost ?: h)
            routes.clear()
            return true
        }

        if (shapeActive && tool.tool == EditorTool.SHAPE) {
            endShape(shapeHost ?: h)
            routes.clear()
            return true
        }

        if (laserActive) {
            laserActive = false
            laserHost = null
            postInvalidateOnAnimation()
            routes.clear()
            return true
        }

        val dragged = draggedObject
        if (dragged != null) {
            endObjectDrag(dragHost ?: h)
            routes.clear()
            return true
        }

        // taps (no movement) for text/image/link
        if (route == Route.DRAW && !canceled) {
            val slot = slotAt(e.x, e.y)
            val tgt: Host = slot?.host ?: h
            when (tool.tool) {
                EditorTool.TEXT -> {
                    if (tgt.transform.toPage(e.x, e.y, pagePoint)) {
                        tgt.onTapForText(pagePoint[0], pagePoint[1])
                    }
                    routes.clear()
                    return true
                }
                EditorTool.IMAGE -> {
                    if (tgt.transform.toPage(e.x, e.y, pagePoint)) {
                        tgt.onTapForImage(pagePoint[0], pagePoint[1])
                    }
                    routes.clear()
                    return true
                }
                EditorTool.PEN -> {
                    if (tgt.transform.toPage(e.x, e.y, pagePoint)) {
                        val link = objectPainter.hitTest(tgt.doc.objectsSnapshot(), pagePoint[0], pagePoint[1])
                        if (link is LinkObj && !liveActive) {
                            tgt.onLinkTap(link)
                            routes.clear()
                            return true
                        }
                    }
                }
                else -> Unit
            }
        }

        when (route) {
            Route.DRAW -> if (canceled) cancelStroke(liveHost ?: h) else if (id == drawingPointerId) endStroke(e.eventTime, liveHost ?: h)
            Route.ERASE -> if (id == erasingPointerId) endEraseGesture(eraseHost ?: h)
            Route.LASSO -> Unit // handled by finishLassoCapture above
            Route.COMMAND -> Unit
            Route.NAVIGATE -> {
                if (navPointerCount == 1) {
                    lastTapUpMs = e.eventTime
                    lastTapX = e.x
                    lastTapY = e.y
                    startFlingIfNeeded()
                }
                navPointerCount = 0
                gestures.end()
                updateActiveFromViewport(notify = true)
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
        cancelStroke(liveHost ?: h)
        erasing = false
        eraseHost = null
        shapeHost = null
        laserHost = null
        laserActive = false
        endPixelEraseDiscard()
        eraseY = Float.NaN
        draggedObject?.let { obj ->
            (dragHost ?: h).hiddenObjectIds.remove(obj.id)
        }
        draggedObject = null
        dragHost = null
        lasso = null
        lassoHost = null
        routes.clear()
        navPointerCount = 0
        navClear()
        scroller.forceFinished(true)
        gestures.end()
        h.onViewportOrContentMayHaveChanged()
        invalidate()
        return true
    }

    // ------------------------------------------------------------- navigation

    /** Strip pan/zoom from the gesture controller (all pages move together). */
    private fun maybeNav(e: MotionEvent, h: Host) {
        if (navPointerCount <= 0 || stripSlots.isEmpty()) return
        if (navPointerCount >= 2) {
            val a = navigatePointer(e, 0)
            val b = navigatePointer(e, 1)
            if (a != null && b != null) {
                gestures.update(a[0], a[1], b[0], b[1])
                zoomBy(gestures.zoomFactor, gestures.focusX, gestures.focusY)
                stripPan(gestures.focusDx, gestures.focusDy)
            }
        } else {
            val a = navigatePointer(e, 0) ?: return
            gestures.update(a[0], a[1], a[0], a[1])
            stripPan(gestures.focusDx, gestures.focusDy)
            navTrack(a[0], a[1], e.eventTime)
        }
        updateActiveFromViewport(notify = true)
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val target = (stripScale * factor).coerceIn(stripMinScale, STRIP_MAX_SCALE)
        val k = target / stripScale
        if (k == 1f) return
        stripOffX = fx - (fx - stripOffX) * k
        stripOffY = fy - (fy - stripOffY) * k
        stripScale = target
    }

    private fun stripPan(dx: Float, dy: Float) {
        stripOffX += dx
        stripOffY += dy
        clampStrip()
        pushTransforms()
        invalidate()
    }

    // ------------------------------- fling (vertical strip browsing, v1.2)

    private fun navTrack(x: Float, y: Float, t: Long) {
        navT[navPos] = t
        navXs[navPos] = x
        navYs[navPos] = y
        navPos = (navPos + 1) % NAV_TRACK_N
        if (navN < NAV_TRACK_N) navN++
    }

    private fun navClear() {
        navN = 0
        navPos = 0
    }

    /** Starts a vertical fling when the last NAVIGATE finger left quickly. */
    private fun startFlingIfNeeded() {
        if (navN < 2) {
            navClear()
            return
        }
        val newest = (navPos - 1 + NAV_TRACK_N) % NAV_TRACK_N
        var oldest = newest
        var i = 1
        while (i < navN) {
            val cand = (newest - i + NAV_TRACK_N) % NAV_TRACK_N
            if (navT[newest] - navT[cand] <= 120) oldest = cand else break
            i++
        }
        val dt = navT[newest] - navT[oldest]
        val vy = if (dt > 0) (navYs[newest] - navYs[oldest]) * 1000f / dt else 0f
        navClear()
        if (abs(vy) < 350f) return
        scroller.fling(0, 0, 0, vy.toInt().coerceIn(-12000, 12000), 0, 0, -100000000, 100000000)
        flingLastY = 0
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val y = scroller.currY
            val dy = y - flingLastY
            flingLastY = y
            if (dy != 0) {
                stripOffY += dy
                clampStrip()
                pushTransforms()
                updateActiveFromViewport(notify = true)
                invalidate()
            }
            postInvalidateOnAnimation()
        }
    }

    // ------------------------------------------------------------ live stroke

    private fun beginStroke(e: MotionEvent, i: Int, h: Host) {
        val tool = h.tool()
        liveHost = h
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
        if (!liveActive) {
            liveHost = null
            return
        }
        liveActive = false
        drawingPointerId = -1
        liveHost = null
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
        liveHost = null
        livePath.rewind()
        frozenPath.rewind()
        invalidate()
    }

    // ------------------------------------------------------------ erasing

    private fun beginErase(e: MotionEvent, i: Int, h: Host) {
        erasing = true
        eraseHost = h
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
        if (!erasing) {
            eraseHost = null
            return
        }
        erasing = false
        erasingPointerId = -1
        eraseHost = null

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
        lassoHost = h
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
            lassoHost = null
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
    fun commitLasso(h: Host? = null) {
        val hh = h ?: lassoHost ?: host ?: return
        val ls = lasso ?: return
        if (ls.originals.isEmpty()) {
            lasso = null
            lassoHost = null
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
        lassoHost = null
        invalidate()
    }

    fun lassoSelection(): List<Stroke> {
        val ls = lasso ?: return emptyList()
        return ls.originals.map { it.transformed(ls.preview, it.id) }
    }

    /** The page owning the active lasso (for paste/targeting in the VM). */
    fun selectionHost(): Host? = lassoHost

    fun hasSelection(): Boolean = lasso?.finished == true

    fun clearSelectionOnly(h: Host? = null) {
        val hh = h ?: lassoHost ?: host ?: return
        val ls = lasso ?: return
        ls.selectedIds.forEach { hh.hiddenStrokeIds.remove(it) }
        lasso = null
        lassoHost = null
        hh.tileCache.invalidateAll()
        invalidate()
    }

    fun deleteSelection(h: Host? = null) {
        val hh = h ?: lassoHost ?: host ?: return
        val ls = lasso ?: return
        if (ls.originals.isEmpty()) return
        hh.history.push(ReplaceStrokes(ArrayList(ls.originals), emptyList()), hh.doc)
        hh.onContentCommitted()
        ls.selectedIds.forEach { hh.hiddenStrokeIds.remove(it) }
        hh.tileCache.invalidateAll()
        lasso = null
        lassoHost = null
        invalidate()
    }

    fun recolorSelection(argb: Int, h: Host? = null) {
        val hh = h ?: lassoHost ?: host ?: return
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
        shapeHost = h
        shapeX0 = px; shapeY0 = py
        shapeX1 = px; shapeY1 = py
        invalidate()
    }

    private fun endShape(h: Host) {
        shapeActive = false
        shapeHost = null
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
                    abs(x1 - x0) / 2f, abs(y1 - y0) / 2f,
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
        dragHost = null
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
        laserHost = h
        laserPts.add(px); laserPts.add(py); laserPts.add(android.os.SystemClock.uptimeMillis().toFloat())
        postInvalidateOnAnimation()
    }

    // ---------------------------------------------------------- invalidation

    private fun invalidateLiveDirty() {
        val lh = liveHost
        if (lh != null && !liveDirty.isEmpty) {
            val b = Bounds()
            b.set(liveDirty)
            invalidatePageRegion(b, lh)
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

    /** Base fit scale of a page (PDF -> fit page, paper -> fit width). */
    private fun baseFitScaleOf(slot: StripSlot): Float {
        val vw = width.toFloat()
        val vh = height.toFloat()
        val pdf = slot.host?.isPdfPage == true
        return if (pdf) minOf(vw / maxOf(slot.widthPt, 1f), vh / maxOf(slot.heightPt, 1f))
        else vw / maxOf(slot.widthPt, 1f)
    }

    /** Double-tap toggles between the page's fit scale and 2x, about the tap. */
    private fun animateZoomToggle(sx: Float, sy: Float) {
        val slot = stripSlots.getOrNull(activeIndex) ?: return
        scroller.forceFinished(true)
        centerAnimator?.cancel()
        zoomAnimator?.cancel()

        val fitScale = baseFitScaleOf(slot).coerceIn(stripMinScale, STRIP_MAX_SCALE)
        val s0 = stripScale
        val s1 = if (s0 > fitScale * 1.6f) fitScale else (fitScale * 2f).coerceIn(stripMinScale, STRIP_MAX_SCALE)

        val stripX = (sx - stripOffX) / s0
        val stripY = (sy - stripOffY) / s0

        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 220
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener { a ->
            val f = a.animatedFraction
            val s = s0 + (s1 - s0) * f
            stripScale = s.coerceIn(stripMinScale, STRIP_MAX_SCALE)
            stripOffX = sx - stripX * stripScale
            stripOffY = sy - stripY * stripScale
            clampStrip()
            pushTransforms()
            updateActiveFromViewport(notify = true)
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
        private const val NAV_TRACK_N = 16
        private const val STRIP_MAX_SCALE = 8f

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
