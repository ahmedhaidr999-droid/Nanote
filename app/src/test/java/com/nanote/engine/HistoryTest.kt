package com.nanote.engine

import com.google.common.truth.Truth.assertThat
import com.nanote.ink.engine.AddStrokes
import com.nanote.ink.engine.History
import com.nanote.ink.engine.PageDoc
import com.nanote.ink.engine.ReplaceStrokes
import com.nanote.ink.engine.Stroke
import com.nanote.ink.engine.strokeOf
import org.junit.Test

class HistoryTest {

    private val s1 = strokeOf(1, com.nanote.ink.engine.PenType.BALLPOINT, 0xFF000000.toInt(), 2f, floatArrayOf(0f, 0f, 10f, 0f, 20f, 0f))
    private val s2 = strokeOf(2, com.nanote.ink.engine.PenType.BALLPOINT, 0xFF000000.toInt(), 2f, floatArrayOf(0f, 10f, 10f, 10f, 20f, 10f))
    private val s3 = strokeOf(3, com.nanote.ink.engine.PenType.BALLPOINT, 0xFF000000.toInt(), 2f, floatArrayOf(0f, 20f, 10f, 20f, 20f, 20f))

    @Test
    fun `add undo redo restores exactly`() {
        val doc = PageDoc(595f, 842f)
        val h = History()
        h.push(AddStrokes(listOf(s1, s2)), doc)
        assertThat(doc.strokeCount).isEqualTo(2)

        h.push(AddStrokes(listOf(s3)), doc)
        assertThat(doc.strokeCount).isEqualTo(3)

        h.undo(doc)
        assertThat(doc.strokeCount).isEqualTo(2)
        assertThat(doc.strokeById(3)).isNull()

        h.redo(doc)
        assertThat(doc.strokeCount).isEqualTo(3)
        assertThat(doc.strokeById(3)).isEqualTo(s3)
    }

    @Test
    fun `replace strokes undoes move exactly`() {
        val doc = PageDoc(595f, 842f)
        val h = History()
        h.push(AddStrokes(listOf(s1)), doc)

        val moved = Stroke(1, s1.pen, s1.argb, s1.baseWidth, s1.pts.copyOf().also {
            for (i in 0 until 3) {
                it[i * 4] += 100f
                it[i * 4 + 1] += 50f
            }
        })
        h.push(ReplaceStrokes(listOf(s1), listOf(moved)), doc)
        assertThat(doc.strokeById(1)).isEqualTo(moved)

        h.undo(doc)
        assertThat(doc.strokeById(1)).isEqualTo(s1)
        h.redo(doc)
        assertThat(doc.strokeById(1)).isEqualTo(moved)
    }

    @Test
    fun `undo after erase restores stroke and grid entry`() {
        val doc = PageDoc(595f, 842f)
        val h = History()
        h.push(AddStrokes(listOf(s1)), doc)
        h.push(ReplaceStrokes(listOf(s1), emptyList()), doc)
        assertThat(doc.strokeCount).isEqualTo(0)

        val b = com.nanote.ink.engine.Bounds()
        b.set(-10f, -10f, 30f, 10f)
        val hits = HashSet<Int>()
        doc.grid.query(b, hits)
        assertThat(hits).isEmpty()

        h.undo(doc)
        assertThat(doc.strokeCount).isEqualTo(1)
        doc.grid.query(b, hits)
        assertThat(hits).containsExactly(1)
    }

    @Test
    fun `canUndo canRedo flags`() {
        val doc = PageDoc(595f, 842f)
        val h = History()
        assertThat(h.canUndo).isFalse()
        assertThat(h.canRedo).isFalse()
        h.push(AddStrokes(listOf(s1)), doc)
        assertThat(h.canUndo).isTrue()
        h.undo(doc)
        assertThat(h.canUndo).isFalse()
        assertThat(h.canRedo).isTrue()
        h.redo(doc)
        assertThat(h.canUndo).isTrue()
        assertThat(h.canRedo).isFalse()
    }

    @Test
    fun `history limit drops the oldest`() {
        val doc = PageDoc(595f, 842f)
        val h = History(limit = 5)
        repeat(10) { i ->
            h.push(AddStrokes(listOf(strokeOf(i, com.nanote.ink.engine.PenType.BALLPOINT, 0xFF000000.toInt(), 2f, floatArrayOf(i.toFloat(), 0f, i + 1f, 0f)))), doc)
        }
        assertThat(h.depth).isEqualTo(5)
        // undoing 5 times returns to the state after the 5th-from-last command
        repeat(5) { h.undo(doc) }
        assertThat(doc.strokeCount).isEqualTo(5)
        assertThat(h.canUndo).isFalse()
    }
}
