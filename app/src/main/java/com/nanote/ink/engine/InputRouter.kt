package com.nanote.ink.engine

/**
 * Input routing and palm rejection, extracted from MotionEvent into a pure
 * value type so it is unit-testable on the JVM. The values mirror the
 * MotionEvent constants of the same names.
 */
object ToolTypes {
    const val UNKNOWN = 0
    const val FINGER = 1
    const val STYLUS = 2
    const val MOUSE = 3
    const val ERASER = 4
    /** MotionEvent.TOOL_TYPE_PALM — API 31 constant, inlined here on purpose. */
    const val PALM = 5
}

object Buttons {
    const val PRIMARY = 0x1
    const val SECONDARY = 0x2
    const val TERTIARY = 0x4
    const val STYLUS_PRIMARY = 0x20
    const val STYLUS_SECONDARY = 0x40
}

/** Snapshot of one pointer at one sample, taken from MotionEvent. */
class PointerSpec(
    val toolType: Int,
    val buttonState: Int,
    val touchMajor: Float,
    val eventTime: Long,
    val pointerCount: Int
)

enum class Route { DRAW, ERASE, NAVIGATE, IGNORE }

/**
 * Routing rules (per spec section 6.1):
 *  - eraser tool                     -> ERASE
 *  - stylus (primary barrel button)  -> ERASE
 *  - stylus                          -> DRAW
 *  - palm                            -> IGNORE
 *  - contact patch larger than palmMajorPx -> IGNORE
 *  - finger within 350 ms of stylus  -> IGNORE (palm lands as the pen lifts)
 *  - finger + fingers-draw on        -> DRAW
 *  - everything else (1-2 fingers)   -> NAVIGATE (pan and pinch)
 *  - while a stylus is down or hovering, every finger pointer is IGNOREd
 *  - mouse: left-drag draws (Chromebooks), other buttons navigate
 */
class InputRouter(
    private val fingersDraw: () -> Boolean,
    private val palmMajorPx: Float = 30f
) {
    private var lastStylusMs = 0L
    private var stylusHovering = false
    private var stylusDown = false

    /** [eventTimeMs] uses the MotionEvent clock (uptimeMillis) in production. */
    fun onHoverEvent(stylusHover: Boolean, eventTimeMs: Long) {
        stylusHovering = stylusHover
        if (stylusHover) lastStylusMs = eventTimeMs
    }

    /** Call on ACTION_DOWN/POINTER_DOWN/UP of stylus pointers (both edges refresh the window). */
    fun onStylusDownChanged(down: Boolean, eventTimeMs: Long) {
        stylusDown = down
        lastStylusMs = eventTimeMs
    }

    fun route(e: PointerSpec): Route {
        val stylusLike = e.toolType == ToolTypes.STYLUS || e.toolType == ToolTypes.ERASER
        if (stylusLike) {
            lastStylusMs = e.eventTime
        }
        val stylusActive = stylusDown || stylusHovering || (e.eventTime - lastStylusMs < 350)
        return when (e.toolType) {
            ToolTypes.ERASER -> Route.ERASE
            ToolTypes.STYLUS ->
                if ((e.buttonState and Buttons.STYLUS_PRIMARY) != 0) Route.ERASE else Route.DRAW
            ToolTypes.PALM -> Route.IGNORE
            ToolTypes.FINGER ->
                when {
                    stylusActive -> Route.IGNORE
                    e.touchMajor > palmMajorPx -> Route.IGNORE
                    fingersDraw() && e.pointerCount == 1 -> Route.DRAW
                    else -> Route.NAVIGATE
                }
            ToolTypes.MOUSE ->
                when {
                    (e.buttonState and Buttons.TERTIARY) != 0 -> Route.NAVIGATE
                    (e.buttonState and Buttons.SECONDARY) != 0 -> Route.NAVIGATE
                    fingersDraw() && (e.buttonState and Buttons.PRIMARY) != 0 -> Route.DRAW
                    else -> Route.NAVIGATE
                }
            else -> Route.IGNORE
        }
    }
}
