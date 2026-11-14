package com.nanote.core.graphics

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.nanote.core.model.FingerAction
import com.nanote.core.model.ToolType
import com.nanote.core.pdf.PageRender
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The high-performance drawing surface.
 *
 * Performance contract:
 *  - Zoom/pan gestures ONLY change the transform matrix + invalidate; no PDF
 *    re-render is triggered during a gesture (no lag). Re-render happens after
 *    the gesture settles, only when the discrete resolution bucket changed.
 *  - onDraw allocates nothing on the hot path; paints are preallocated, stroke
 *    paths come from the renderer cache, and elements are culled by bbox
 *    (spatial-index backed in infinite mode).
 *  - Stylus draws; fingers navigate according to the configured finger action
 *    (§5); TOOL_TYPE_PALM never produces marks; barrel button = temp eraser.
 *  - §4 high-frequency input: historical motion samples are replayed on every
 *    ACTION_MOVE so stylus capture runs at full hardware rate.
 */
@SuppressLint("ClickableViewAccessibility")
class CanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val TAG = "CanvasView"
        private const val DOUBLE_TAP_ZOOM = 2f
        private const val FLING_FRICTION = 0.94f
    }

    lateinit var state: CanvasState
    lateinit var controller: ToolController

    /** PDF page bitmaps provider (null for note documents). */
    var pageRenderProvider: ((Int) -> PageRender?)? = null

    /** Called (throttled) while the visible page set / zoom changes. */
    var onVisiblePagesChanged: ((List<Pair<Int, Float>>) -> Unit)? = null

    /** Called after a zoom gesture settles (re-render at new bucket if needed). */
    var onZoomSettled: (() -> Unit)? = null

    var onTransformChanged: (() -> Unit)? = null

    private var lastVisibleKey = ""
    private var lastStylusTime = 0L

    private val densityScale = resources.displayMetrics.density

    // ---------------- paints ----------------

    private val bgPaint = Paint().apply { color = Color.parseColor("#EEF2F7") }
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pageShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22000000")
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#332563EB")
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2563EB")
        style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val objectBBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2563EB")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val objectBBoxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#142563EB")
    }
    private val eraserCursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#882563EB")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3B30")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2563EB")
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
    }
    private val lassoFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A2563EB")
    }
    private val rectSelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2563EB")
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val rectSelFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#142563EB")
    }
    private val retryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = 14f * densityScale
        textAlign = Paint.Align.CENTER
    }
    private val pageGapLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0BCCF")
        textSize = 12f * densityScale
        textAlign = Paint.Align.CENTER
    }

    private val scratchRect = RectF()
    private val scratchRect2 = RectF()
    private val scratchPath = Path()

    // ---------------- input state ----------------

    private enum class InputMode { NONE, STROKE, CONTROLLER, SCROLL, PINCH }
    private var inputMode = InputMode.NONE
    private var stylusDrawing = false
    private var lastEraserPos = FloatArray(2)

    private var scaleDetector: ScaleGestureDetector? = null
    private var gestureDetector: GestureDetector? = null
    private var velocityTracker: android.view.VelocityTracker? = null
    private var scroller: android.widget.OverScroller? = null

    fun initialize() {
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (inputMode == InputMode.PINCH || inputMode == InputMode.SCROLL) {
                    inputMode = InputMode.PINCH
                    state.zoomAt(detector.focusX, detector.focusY, state.transform.scale * detector.scaleFactor)
                    onTransformChanged?.invoke()
                    notifyVisibleChanged()
                    invalidate()
                }
                return true
            }
        })
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (inputMode == InputMode.STROKE || inputMode == InputMode.CONTROLLER || inputMode == InputMode.PINCH) return false
                inputMode = InputMode.SCROLL
                state.panBy(-dx, -dy)
                onTransformChanged?.invoke()
                notifyVisibleChanged()
                invalidate()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (inputMode != InputMode.NONE) return
                controller.onLongPress(e.x, e.y)
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val tool = controller.activeTool
                if (tool == ToolType.TEXT || tool == ToolType.SELECT_TEXT ||
                    tool == ToolType.SELECT_OBJECT || tool == ToolType.STICKY_NOTE
                ) return false
                val target = if (state.transform.scale < 1.4f) DOUBLE_TAP_ZOOM else 1f
                animateZoom(target, e.x, e.y)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (inputMode != InputMode.SCROLL) return false
                startFling(vx, vy)
                return true
            }
        })
        scroller = android.widget.OverScroller(context)
        state.onTransformChanged = { onTransformChanged?.invoke() }
    }

    fun animateZoom(target: Float, fx: Float, fy: Float) {
        val start = state.transform.scale
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 200
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener { a ->
            val t = a.animatedValue as Float
            state.zoomAt(fx, fy, start + (target - start) * t)
            onTransformChanged?.invoke()
            notifyVisibleChanged()
            invalidate()
        }
        anim.start()
    }

    // =====================================================================
    // Touch routing (§4 / §5)
    // =====================================================================

    @SuppressLint("Recycle")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!::state.isInitialized || !::controller.isInitialized) return false

        val now = System.currentTimeMillis()
        var hasStylus = false
        var hasEraserTool = false
        for (i in 0 until event.pointerCount) {
            when (event.getToolType(i)) {
                MotionEvent.TOOL_TYPE_STYLUS -> hasStylus = true
                // §2 — hardware eraser end of the stylus erases too
                MotionEvent.TOOL_TYPE_ERASER -> hasEraserTool = true
                else -> {}
            }
        }
        if (hasStylus) lastStylusTime = now
        val stylusRecently = now - lastStylusTime < 400

        // barrel button or dedicated eraser tip = temporary eraser (§2)
        controller.tempEraserActive = hasEraserTool ||
            (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocityTracker?.recycle()
                velocityTracker = android.view.VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
                scroller?.forceFinished(true)
                inputMode = InputMode.NONE
                stylusDrawing = false

                val idx = event.actionIndex
                val isStylus = event.getToolType(idx) == MotionEvent.TOOL_TYPE_STYLUS
                val pressure = event.getPressure(idx)
                val tilt = tiltOf(event, idx)
                val nanos = event.eventTime * 1_000_000L // µs → ns

                // Hard palm guard: finger while stylus just drew → ignore entirely.
                if (!isStylus && stylusRecently && controller.palmRejection) {
                    inputMode = InputMode.NONE
                    return true
                }

                val consumed = controller.onActionDown(event.x, event.y, isStylus, pressure, tilt, nanos)
                if (consumed) {
                    inputMode = InputMode.CONTROLLER
                    if (controller.liveStrokePoints != null) {
                        inputMode = InputMode.STROKE
                        stylusDrawing = true
                    }
                } else {
                    // navigation gestures for this pointer
                    gestureDetector?.onTouchEvent(event)
                    inputMode = InputMode.SCROLL
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                velocityTracker?.addMovement(event)
                if (inputMode == InputMode.STROKE) return true // palm guard mid-stroke
                if (event.pointerCount >= 2) {
                    // entering pinch; cancel any pending long press by fake movement
                    inputMode = InputMode.PINCH
                }
                scaleDetector?.onTouchEvent(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (inputMode == InputMode.PINCH && event.pointerCount >= 2) {
                    scaleDetector?.onTouchEvent(event)
                    return true
                }
                scaleDetector?.onTouchEvent(event)
                if (inputMode == InputMode.PINCH) return true

                when (inputMode) {
                    InputMode.STROKE, InputMode.CONTROLLER -> {
                        val idx = activePointerIndex(event)
                        val isStylus = event.getToolType(idx) == MotionEvent.TOOL_TYPE_STYLUS || stylusDrawing
                        // §4 — replay ALL historical samples: full-rate stylus capture
                        for (h in 0 until event.historySize) {
                            val hx = event.getHistoricalX(idx, h)
                            val hy = event.getHistoricalY(idx, h)
                            val hp = event.getHistoricalPressure(idx, h)
                            val ht = event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, idx, h)
                            val hn = event.getHistoricalEventTime(h) * 1_000_000L
                            controller.onActionMove(hx, hy, isStylus, hp, ht, hn)
                        }
                        val pressure = event.getPressure(idx)
                        val tilt = tiltOf(event, idx)
                        val nanos = event.eventTime * 1_000_000L
                        controller.onActionMove(event.x, event.y, isStylus, pressure, tilt, nanos)
                        if (controller.activeTool == ToolType.ERASER || controller.tempEraserActive) {
                            lastEraserPos[0] = event.x; lastEraserPos[1] = event.y
                        }
                    }
                    InputMode.SCROLL -> gestureDetector?.onTouchEvent(event)
                    else -> gestureDetector?.onTouchEvent(event)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (inputMode == InputMode.PINCH && event.pointerCount <= 2) {
                    // pinch ends
                    onZoomSettled?.invoke()
                    inputMode = InputMode.SCROLL
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000, 8000f)
                val vx = velocityTracker?.xVelocity ?: 0f
                val vy = velocityTracker?.yVelocity ?: 0f

                if (inputMode == InputMode.PINCH) {
                    onZoomSettled?.invoke()
                }
                if (inputMode == InputMode.STROKE || inputMode == InputMode.CONTROLLER) {
                    val isStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || stylusDrawing
                    controller.onActionUp(event.x, event.y, isStylus)
                } else if (inputMode == InputMode.SCROLL) {
                    gestureDetector?.onTouchEvent(event)
                    if (abs(vx) > 800 || abs(vy) > 800) {
                        startFling(vx, vy)
                    }
                }
                velocityTracker?.recycle()
                velocityTracker = null
                inputMode = InputMode.NONE
                stylusDrawing = false
                controller.tempEraserActive = false
                notifyVisibleChanged(force = true)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
                inputMode = InputMode.NONE
                stylusDrawing = false
                controller.tempEraserActive = false
                return true
            }
        }
        return true
    }

    /** Tilt in radians when reported, else 0 (§4 — graceful on hardware without tilt). */
    private fun tiltOf(event: MotionEvent, idx: Int): Float = try {
        event.getAxisValue(MotionEvent.AXIS_TILT, idx)
    } catch (t: Throwable) {
        0f
    }

    /** The pointer that owns the current gesture (stylus preferred). */
    private fun activePointerIndex(event: MotionEvent): Int {
        for (i in 0 until event.pointerCount) {
            if (event.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS) return i
        }
        return event.actionIndex.coerceIn(0, (event.pointerCount - 1).coerceAtLeast(0))
    }

    override fun computeScroll() {
        if (flingVelocityX == 0f && flingVelocityY == 0f) return
        val now = System.currentTimeMillis()
        val dt = (now - flingLastTick).coerceAtLeast(8)
        flingLastTick = now
        val stepX = flingVelocityX * dt / 1000f
        val stepY = flingVelocityY * dt / 1000f
        state.panBy(-stepX, -stepY)
        flingVelocityX *= FLING_FRICTION
        flingVelocityY *= FLING_FRICTION
        if (abs(flingVelocityX) < 40f && abs(flingVelocityY) < 40f) {
            flingVelocityX = 0f; flingVelocityY = 0f
            notifyVisibleChanged(force = true)
            onZoomSettled?.invoke()
        } else {
            onTransformChanged?.invoke()
            notifyVisibleChanged()
        }
        invalidate()
    }

    private var flingVelocityX = 0f
    private var flingVelocityY = 0f
    private var flingLastTick = 0L

    private fun startFling(vx: Float, vy: Float) {
        flingVelocityX = vx
        flingVelocityY = vy
        flingLastTick = System.currentTimeMillis()
        postInvalidateOnAnimation()
    }

    // =====================================================================
    // Draw
    // =====================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        state.viewportWidth = w
        state.viewportHeight = h
        notifyVisibleChanged(force = true)
    }

    override fun onDraw(canvas: Canvas) {
        if (!::state.isInitialized) return
        val t = state.transform
        canvas.drawPaint(bgPaint)

        canvas.save()
        canvas.translate(t.panX, t.panY)
        canvas.scale(t.scale, t.scale)

        val viewL = t.screenToWorldX(0f)
        val viewT = t.screenToWorldY(0f)
        val viewR = t.screenToWorldX(width.toFloat())
        val viewB = t.screenToWorldY(height.toFloat())

        if (state.infiniteMode) {
            drawInfiniteWorld(canvas, viewL, viewT, viewR, viewB)
        } else {
            drawPagedWorld(canvas, viewL, viewT, viewR, viewB)
        }

        drawLiveInput(canvas)

        canvas.restore()

        drawScreenOverlays(canvas)
    }

    /** §9 — the infinite canvas: dot-grid background, no page boundaries. */
    private fun drawInfiniteWorld(canvas: Canvas, viewL: Float, viewT: Float, viewR: Float, viewB: Float) {
        PageBackgrounds.paintInfiniteDots(canvas, viewL, viewT, viewR, viewB, dark = false)
        drawAllContent(canvas, viewL, viewT, viewR, viewB)
    }

    private fun drawPagedWorld(canvas: Canvas, viewL: Float, viewT: Float, viewR: Float, viewB: Float) {
        val layout = state.layout
        for (p in 0 until layout.count) {
            val top = layout.pageTop(p)
            val bottom = layout.pageBottom(p)
            if (bottom < viewT || top > viewB) continue
            val left = layout.pageLeft(p)
            val right = layout.pageRight(p)
            val pageW = layout.pageWidth(p)
            val pageH = layout.pageHeight(p)

            // page card + shadow
            scratchRect.set(left - 6, top - 6, right + 6, bottom + 6)
            canvas.drawRoundRect(scratchRect, 8f, 8f, pageShadowPaint)
            scratchRect.set(left, top, right, bottom)
            canvas.drawRect(scratchRect, pagePaint)

            canvas.save()
            canvas.clipRect(left, top, right, bottom)

            // §10 — per-page template background (never touches handwriting)
            val def = controller.doc.pages.getOrNull(p)
            if (def != null && def.pdfPage < 0) {
                PageBackgrounds.paint(
                    canvas, def.template, left, top, right, bottom,
                    PageBackgrounds.templateColorOf(def.templateColor, dark = false)
                )
            }

            // PDF bitmap
            val render = pageRenderProvider?.invoke(p)
            val renderBitmap = render?.bitmap
            if (renderBitmap != null && !renderBitmap.isRecycled) {
                scratchRect2.set(left, top, right, bottom)
                canvas.drawBitmap(renderBitmap, null, scratchRect2, bmpPaint)
            } else if (render != null && render.failed) {
                // gray page + retry hint drawn in screen space later
                canvas.drawRect(scratchRect, retryGrayPaint)
            }

            drawPageContent(canvas, p, left, top, pageW, pageH, viewL, viewT, viewR, viewB)

            // text-selection tint (over content, under handles)
            if (controller.textSelection.isActive && controller.textSelection.pageIndex == p) {
                for (r in controller.textSelection.selectionRects()) {
                    scratchRect2.set(
                        left + r[0], top + r[1], left + r[2], top + r[3]
                    )
                    canvas.drawRect(scratchRect2, selPaint)
                }
            }

            canvas.restore()
        }
    }

    private val retryGrayPaint = Paint().apply { color = Color.parseColor("#F1F5F9") }

    /** Infinite-canvas content pass — everything lives on the unbounded plane. */
    private fun drawAllContent(canvas: Canvas, viewL: Float, viewT: Float, viewR: Float, viewB: Float) {
        val doc = controller.doc
        val renderer = controller.renderer
        val pressure = controller.pressureEnabled

        for (img in doc.images) {
            if (img.x + img.w < viewL || img.x > viewR || img.y + img.h < viewT || img.y > viewB) continue
            val file = controller.imageResolverFor(img.path) ?: continue
            canvas.save()
            if (controller.isSelected(img.id)) applySelectionGhost(canvas)
            renderer.drawImage(canvas, img, file)
            canvas.restore()
        }
        for (sh in doc.shapes) {
            val b = sh.bbox()
            if (b[2] < viewL || b[0] > viewR || b[3] < viewT || b[1] > viewB) continue
            canvas.save()
            if (controller.isSelected(sh.id)) applySelectionGhost(canvas)
            renderer.drawShape(canvas, sh)
            canvas.restore()
        }
        for (tx in doc.texts) {
            val h = renderer.measureTextHeight(tx)
            if (tx.x + tx.width < viewL || tx.x > viewR || tx.y + h < viewT || tx.y > viewB) continue
            canvas.save()
            if (controller.isSelected(tx.id)) applySelectionGhost(canvas)
            renderer.drawText(canvas, tx)
            canvas.restore()
        }
        val erased = controller.erasedLive
        for (s in controller.strokesNear(viewL, viewT, viewR, viewB)) {
            if (erased.isNotEmpty() && s in erased) continue
            canvas.save()
            if (controller.isSelected(s.id)) applySelectionGhost(canvas)
            renderer.drawStroke(canvas, s, pressure)
            canvas.restore()
        }
    }

    /**
     * Applies the live selection transform (move / resize / rotate ghost, §11)
     * to the canvas so subsequent element draws appear transformed.
     */
    private fun applySelectionGhost(canvas: Canvas) {
        val sel = controller.objectSelection
        if (sel.dragAccumX != 0f || sel.dragAccumY != 0f) {
            canvas.translate(sel.dragAccumX, sel.dragAccumY)
        }
        if (sel.liveScaleX != 1f || sel.liveScaleY != 1f) {
            val a = controller.ghostAnchor
            canvas.translate(a.x, a.y)
            canvas.scale(sel.liveScaleX, sel.liveScaleY)
            canvas.translate(-a.x, -a.y)
        }
        if (sel.liveRotation != 0f) {
            val c = controller.ghostRotateCenter
            canvas.rotate(sel.liveRotation, c.x, c.y)
        }
    }

    /** All stored elements of a page (culled by viewport). */
    private fun drawPageContent(
        canvas: Canvas, page: Int,
        left: Float, top: Float, pageW: Float, pageH: Float,
        viewL: Float, viewT: Float, viewR: Float, viewB: Float
    ) {
        val doc = controller.doc
        val renderer = controller.renderer

        // highlights (below ink, above page)
        for (h in doc.highlights) {
            if (h.page != page) continue
            for (r in h.rects) {
                val x0 = left + r[0] * pageW
                val y0 = top + r[1] * pageH
                val x1 = left + r[2] * pageW
                val y1 = top + r[3] * pageH
                if (x1 < viewL || x0 > viewR || y1 < viewT || y0 > viewB) continue
                scratchRect2.set(x0, y0, x1, y1)
                renderer.drawHighlightRect(canvas, scratchRect2, h.color, h.opacity)
            }
        }
        for (u in doc.underlines) {
            if (u.page != page) continue
            for (r in u.rects) {
                val x0 = left + r[0] * pageW
                val y = top + r[3] * pageH
                val x1 = left + r[2] * pageW
                if (x1 < viewL || x0 > viewR || y < viewT - 10 || y > viewB + 10) continue
                renderer.drawUnderline(canvas, x0, y, x1, u.color, u.opacity)
            }
        }

        // images
        for (img in doc.images) {
            if (img.page != page) continue
            if (img.x + img.w < viewL || img.x > viewR || img.y + img.h < viewT || img.y > viewB) continue
            val file = controller.imageResolverFor(img.path) ?: continue
            canvas.save()
            if (controller.isSelected(img.id)) applySelectionGhost(canvas)
            renderer.drawImage(canvas, img, file)
            canvas.restore()
        }

        // shapes
        for (sh in doc.shapes) {
            if (sh.page != page) continue
            val b = sh.bbox()
            if (b[2] < viewL || b[0] > viewR || b[3] < viewT || b[1] > viewB) continue
            canvas.save()
            if (controller.isSelected(sh.id)) applySelectionGhost(canvas)
            renderer.drawShape(canvas, sh)
            canvas.restore()
        }

        // texts
        for (tx in doc.texts) {
            if (tx.page != page) continue
            val h = renderer.measureTextHeight(tx)
            if (tx.x + tx.width < viewL || tx.x > viewR || tx.y + h < viewT || tx.y > viewB) continue
            canvas.save()
            if (controller.isSelected(tx.id)) applySelectionGhost(canvas)
            renderer.drawText(canvas, tx)
            canvas.restore()
        }

        // strokes
        val pressure = controller.pressureEnabled
        val erased = controller.erasedLive
        for (s in doc.strokes) {
            if (s.page != page) continue
            if (erased.isNotEmpty() && s in erased) continue
            val b = s.bbox()
            if (b[2] < viewL || b[0] > viewR || b[3] < viewT || b[1] > viewB) continue
            canvas.save()
            if (controller.isSelected(s.id)) applySelectionGhost(canvas)
            renderer.drawStroke(canvas, s, pressure)
            canvas.restore()
        }
    }

    /** In-progress stroke / shape / lasso / rect selection. */
    private fun drawLiveInput(canvas: Canvas) {
        val c = controller
        val renderer = c.renderer
        c.liveStrokePoints?.let { pts ->
            val tool = c.liveStrokeTool
            val cfg = c.toolConfig
            val style = cfg.inkStyleOf(tool)
            val path = renderer.buildLiveInkPath(
                pts, style.width, c.pressureEnabled, tool,
                style.pressureResponse, c.liveStrokeVelocity
            )
            renderer.drawLiveInk(canvas, path, style.color, style.width, style.opacity, tool)
            return
        }
        val s = c.currentShapeStart
        val e = c.currentShapeEnd
        if (s != null && e != null) {
            val cfg = c.toolConfig
            renderer.drawLiveShape(canvas, cfg.shapeKind, s[0], s[1], e[0], e[1], cfg.shapeColor, cfg.shapeWidth, cfg.shapeFillColor, cfg.shapeFilled)
            return
        }
        c.rectSelectionLive?.let { r ->
            scratchRect.set(
                kotlin.math.min(r[0], r[2]), kotlin.math.min(r[1], r[3]),
                kotlin.math.max(r[0], r[2]), kotlin.math.max(r[1], r[3])
            )
            canvas.drawRect(scratchRect, rectSelFillPaint)
            canvas.drawRect(scratchRect, rectSelPaint)
            return
        }
        c.currentLasso?.let { pts ->
            if (pts.size >= 4) {
                scratchPath.rewind()
                scratchPath.moveTo(pts[0], pts[1])
                for (i in 2 until pts.size step 2) scratchPath.lineTo(pts[i], pts[i + 1])
                canvas.drawPath(scratchPath, lassoFillPaint)
                canvas.drawPath(scratchPath, lassoPaint)
            }
        }
    }

    /** Constant-size overlays in screen coordinates. */
    private fun drawScreenOverlays(canvas: Canvas) {
        val c = controller
        val t = state.transform
        val layout = state.layout

        // text selection handles
        if (c.textSelection.isActive) {
            val page = c.textSelection.pageIndex
            if (page >= 0 && page < layout.count) {
                c.textSelection.handlePositions()?.let { (h1, h2) ->
                    val x1 = t.worldToScreenX(layout.pageLeft(page) + h1[0])
                    val y1 = t.worldToScreenY(layout.pageTop(page) + h1[1]) + 14f * densityScale
                    val x2 = t.worldToScreenX(layout.pageLeft(page) + h2[0])
                    val y2 = t.worldToScreenY(layout.pageTop(page) + h2[1]) + 14f * densityScale
                    drawHandle(canvas, x1, y1)
                    drawHandle(canvas, x2, y2)
                }
            }
        }

        // object selection bbox + handles (§11: corners, rotate, move)
        if (c.objectSelection.isActive) {
            c.objectSelection.bbox(c.doc, c.renderer)?.let { b ->
                val dx = t.worldToScreenX(c.objectSelection.dragAccumX) - t.panX
                val dy = t.worldToScreenY(c.objectSelection.dragAccumY) - t.panY
                scratchRect.set(
                    t.worldToScreenX(b[0]) + dx,
                    t.worldToScreenY(b[1]) + dy,
                    t.worldToScreenX(b[2]) + dx,
                    t.worldToScreenY(b[3]) + dy
                )
                canvas.drawRoundRect(scratchRect, 8f, 8f, objectBBoxFillPaint)
                canvas.drawRoundRect(scratchRect, 8f, 8f, objectBBoxPaint)
                // 4 corner resize handles
                val corners = arrayOf(
                    scratchRect.left to scratchRect.top,
                    scratchRect.right to scratchRect.top,
                    scratchRect.right to scratchRect.bottom,
                    scratchRect.left to scratchRect.bottom
                )
                for ((hx, hy) in corners) {
                    drawHandle(canvas, hx, hy)
                }
                // rotate handle above top-center with a stem
                val rx = scratchRect.centerX()
                val ry = scratchRect.top - 46f
                canvas.drawLine(rx, scratchRect.top, rx, ry + 10f, objectBBoxPaint)
                canvas.save()
                canvas.rotate(c.objectSelection.liveRotation, rx, ry)
                drawRotateHandle(canvas, rx, ry)
                canvas.restore()
            }
        }

        // eraser cursor
        if ((c.activeTool == ToolType.ERASER || c.tempEraserActive) && lastEraserPos[0] != 0f) {
            val r = c.toolConfig.eraserSize / 2f
            canvas.drawCircle(lastEraserPos[0], lastEraserPos[1], r, eraserCursorPaint)
        }

        // laser trails (fading)
        val now = System.currentTimeMillis()
        val it = c.laserTrails.iterator()
        var anyLaser = false
        while (it.hasNext()) {
            val tr = it.next()
            if (now - tr.startTime > 700) { it.remove(); continue }
            val alpha = c.laserAlpha(tr)
            laserPaint.alpha = (alpha * 255).toInt()
            laserPaint.strokeWidth = 5f * densityScale
            if (tr.points.size >= 4) {
                scratchPath.rewind()
                scratchPath.moveTo(tr.points[0], tr.points[1])
                for (i in 2 until tr.points.size step 2) scratchPath.lineTo(tr.points[i], tr.points[i + 1])
                canvas.drawPath(scratchPath, laserPaint)
                anyLaser = true
            }
        }
        if (anyLaser || c.laserTrails.isNotEmpty()) postInvalidateOnAnimation()

        // failed page hints (screen space)
        for (p in state.visiblePages()) {
            val render = pageRenderProvider?.invoke(p) ?: continue
            if (render.failed) {
                val cx = (t.worldToScreenX(layout.pageLeft(p)) + t.worldToScreenX(layout.pageRight(p))) / 2f
                val cy = (t.worldToScreenY(layout.pageTop(p)) + t.worldToScreenY(layout.pageBottom(p))) / 2f
                canvas.drawText("⟳", cx, cy, retryTextPaint)
            }
        }
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        handlePaint.color = Color.parseColor("#2563EB")
        canvas.drawCircle(x, y, 11f * densityScale / 1.6f, handlePaint)
        canvas.drawCircle(x, y, 11f * densityScale / 1.6f, handleStrokePaint)
    }

    /** Circular rotate handle with a subtle arrow glyph. */
    private fun drawRotateHandle(canvas: Canvas, x: Float, y: Float) {
        handlePaint.color = Color.parseColor("#0F766E")
        canvas.drawCircle(x, y, 12f * densityScale / 1.5f, handlePaint)
        canvas.drawCircle(x, y, 12f * densityScale / 1.5f, handleStrokePaint)
        retryTextPaint.textSize = 12f * densityScale
        val prev = retryTextPaint.color
        retryTextPaint.color = Color.WHITE
        canvas.drawText("↻", x, y + 4f * densityScale, retryTextPaint)
        retryTextPaint.color = prev
    }

    // =====================================================================
    // Visible page notifications (throttled)
    // =====================================================================

    fun notifyVisibleChanged(force: Boolean = false) {
        val pages = state.visiblePages()
        val scale = state.transform.scale
        val key = pages.joinToString(",") + "@" + (scale * 10).roundToInt()
        if (!force && key == lastVisibleKey) return
        lastVisibleKey = key
        val payload = pages.map { it to scale }
        onVisiblePagesChanged?.invoke(payload)
    }

    fun requestRenderRetryAt(page: Int) {
        // Compose overlay forwards retry taps here
    }

    fun densityFactor(): Float = densityScale

    fun triggerFlingNow() { /* no-op */ }
}
