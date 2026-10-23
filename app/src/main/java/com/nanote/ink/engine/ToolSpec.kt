package com.nanote.ink.engine

/**
 * Active editor tool + its configuration. The ViewModel exposes an immutable
 * instance; the canvas reads it through a getter so tool changes cost nothing
 * on the touch path.
 */
enum class EditorTool { PEN, ERASER, LASSO, SHAPE, TEXT, IMAGE, LASER }

enum class ShapeKind { LINE, ARROW, RECT, ELLIPSE, TRIANGLE, DIAMOND, STAR }

data class ToolSpec(
    val tool: EditorTool = EditorTool.PEN,
    val pen: PenType = PenType.BALLPOINT,
    val colorArgb: Int = 0xFF000000.toInt(),
    val widthPt: Float = 1.5f,
    val eraserWholeStroke: Boolean = true,
    val eraserRadiusPt: Float = 8f,
    val shapeKind: ShapeKind = ShapeKind.LINE,
    val snapEnabled: Boolean = true
)
