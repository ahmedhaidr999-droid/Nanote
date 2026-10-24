package com.nanote.ink.engine

/**
 * Active editor tool + its configuration. The ViewModel exposes an immutable
 * instance; the canvas reads it through a getter so tool changes cost nothing
 * on the touch path.
 *
 * v1.1: the HIGHLIGHTER is a first-class tool, fully separated from the
 * writing pens. Pen and highlighter each remember their own color and width
 * ([colorArgb] / [widthPt] are computed per active tool), so switching tools
 * never leaks the pen's color into the highlighter or vice versa.
 */
enum class EditorTool { PEN, HIGHLIGHTER, ERASER, LASSO, SHAPE, TEXT, IMAGE, LASER }

enum class ShapeKind { LINE, ARROW, RECT, ELLIPSE, TRIANGLE, DIAMOND, STAR }

data class ToolSpec(
    val tool: EditorTool = EditorTool.PEN,
    /** Pen engine for writing strokes; [withTool] keeps this in sync with [tool]. */
    val pen: PenType = PenType.BALLPOINT,
    /** Color/width memory of the WRITING pens (pen, shape, text…). */
    val penColorArgb: Int = 0xFF000000.toInt(),
    val penWidthPt: Float = 1.5f,
    /** Separate color/width memory of the highlighter tool. */
    val hlColorArgb: Int = 0xFFFFEB3B.toInt(),
    val hlWidthPt: Float = 12f,
    /** Last non-highlighter pen type used by the PEN tool. */
    val lastWritingPen: PenType = PenType.BALLPOINT,
    val eraserWholeStroke: Boolean = true,
    val eraserRadiusPt: Float = 8f,
    val shapeKind: ShapeKind = ShapeKind.LINE,
    val snapEnabled: Boolean = true
) {
    val isHighlighter: Boolean get() = tool == EditorTool.HIGHLIGHTER

    /** Effective stroke color: the highlighter keeps its own palette. */
    val colorArgb: Int get() = if (isHighlighter) hlColorArgb else penColorArgb

    /** Effective stroke width: the highlighter keeps its own presets. */
    val widthPt: Float get() = if (isHighlighter) hlWidthPt else penWidthPt

    companion object {
        /**
         * Pure tool-switch logic (unit-tested): swaps the active tool while
         * keeping each tool's color/width memory isolated. The pen engine is
         * kept in sync: the highlighter tool always draws with
         * [PenType.HIGHLIGHTER]; the PEN tool restores [lastWritingPen].
         */
        fun withTool(s: ToolSpec, t: EditorTool): ToolSpec = when (t) {
            EditorTool.HIGHLIGHTER -> s.copy(
                tool = EditorTool.HIGHLIGHTER,
                pen = PenType.HIGHLIGHTER,
                // the writing pens' memory stays untouched; remember the last
                // writing pen so switching back restores it
                lastWritingPen = if (s.pen != PenType.HIGHLIGHTER) s.pen else s.lastWritingPen
            )
            EditorTool.PEN -> s.copy(
                tool = EditorTool.PEN,
                pen = if (s.lastWritingPen == PenType.HIGHLIGHTER) PenType.BALLPOINT else s.lastWritingPen
            )
            else -> s.copy(tool = t)
        }

        /** Pure pen-type selection: never called with HIGHLIGHTER from the
         *  separated tool; writing-pen choice is remembered for the PEN tool. */
        fun withPen(s: ToolSpec, p: PenType): ToolSpec =
            if (p == PenType.HIGHLIGHTER) withTool(s, EditorTool.HIGHLIGHTER)
            else s.copy(pen = p, lastWritingPen = p, tool = EditorTool.PEN)

        /** Color change routed to the memory of the ACTIVE tool. */
        fun withColor(s: ToolSpec, argb: Int): ToolSpec =
            if (s.isHighlighter) s.copy(hlColorArgb = argb) else s.copy(penColorArgb = argb)

        /** Width change routed to the memory of the ACTIVE tool. */
        fun withWidth(s: ToolSpec, w: Float): ToolSpec =
            if (s.isHighlighter) s.copy(hlWidthPt = w) else s.copy(penWidthPt = w)
    }
}
