package com.nanote.engine

import com.google.common.truth.Truth.assertThat
import com.nanote.ink.engine.EditorTool
import com.nanote.ink.engine.PenType
import com.nanote.ink.engine.ToolSpec
import org.junit.Test

/**
 * v1.1: the highlighter is a separate tool with its own color/width memory.
 * Switching between the pen and the highlighter must never leak one tool's
 * settings into the other.
 */
class ToolSpecTest {

    @Test
    fun `highlighter keeps its own color and width memory`() {
        var s = ToolSpec()
        // configure the writing pen: blue, 2.5 pt
        s = ToolSpec.withColor(s, 0xFF1976D2.toInt())
        s = ToolSpec.withWidth(s, 2.5f)
        // switch to the highlighter: defaults, not the pen's values
        s = ToolSpec.withTool(s, EditorTool.HIGHLIGHTER)
        assertThat(s.colorArgb).isEqualTo(0xFFFFEB3B.toInt())
        assertThat(s.widthPt).isEqualTo(12f)
        assertThat(s.pen).isEqualTo(PenType.HIGHLIGHTER)
        // configure the highlighter: green, 18 pt
        s = ToolSpec.withColor(s, 0xFF69F0AE.toInt())
        s = ToolSpec.withWidth(s, 18f)
        // back to the pen: the pen's memory survived untouched
        s = ToolSpec.withTool(s, EditorTool.PEN)
        assertThat(s.colorArgb).isEqualTo(0xFF1976D2.toInt())
        assertThat(s.widthPt).isEqualTo(2.5f)
        assertThat(s.pen).isNotEqualTo(PenType.HIGHLIGHTER)
        // and the highlighter's memory survived too
        s = ToolSpec.withTool(s, EditorTool.HIGHLIGHTER)
        assertThat(s.colorArgb).isEqualTo(0xFF69F0AE.toInt())
        assertThat(s.widthPt).isEqualTo(18f)
    }

    @Test
    fun `selecting a writing pen never activates the highlighter engine`() {
        var s = ToolSpec.withTool(ToolSpec(), EditorTool.HIGHLIGHTER)
        s = ToolSpec.withPen(s, PenType.FOUNTAIN)
        assertThat(s.tool).isEqualTo(EditorTool.PEN)
        assertThat(s.pen).isEqualTo(PenType.FOUNTAIN)
        // detouring through other tools remembers the last writing pen
        s = ToolSpec.withTool(s, EditorTool.ERASER)
        s = ToolSpec.withTool(s, EditorTool.PEN)
        assertThat(s.pen).isEqualTo(PenType.FOUNTAIN)
    }

    @Test
    fun `withPen highlighter defensively switches to the highlighter tool`() {
        val s = ToolSpec.withPen(ToolSpec(), PenType.HIGHLIGHTER)
        assertThat(s.tool).isEqualTo(EditorTool.HIGHLIGHTER)
        assertThat(s.pen).isEqualTo(PenType.HIGHLIGHTER)
    }

    @Test
    fun `highlighter strokes draw with the multiply highlighter pen engine`() {
        val s = ToolSpec.withTool(ToolSpec(), EditorTool.HIGHLIGHTER)
        assertThat(com.nanote.ink.engine.specOf(s.pen).multiply).isTrue()
        // writing tools stay non-multiply
        val p = ToolSpec.withTool(s, EditorTool.PEN)
        assertThat(com.nanote.ink.engine.specOf(p.pen).multiply).isFalse()
    }

    @Test
    fun `non-draw tools share the pen color memory but never the highlighter`() {
        var s = ToolSpec()
        s = ToolSpec.withColor(s, 0xFFD32F2F.toInt())
        s = ToolSpec.withTool(s, EditorTool.SHAPE)
        assertThat(s.colorArgb).isEqualTo(0xFFD32F2F.toInt())
        assertThat(s.pen).isNotEqualTo(PenType.HIGHLIGHTER)
    }
}
