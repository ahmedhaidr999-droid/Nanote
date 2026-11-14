package com.nanote.core.model

import com.nanote.core.common.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Undoable mutation over the document. Command pattern (no snapshots). */
interface Command {
    fun execute()
    fun revert()
}

/**
 * In-memory mutable document with undo/redo (max 200 steps), grouped per page
 * element indices and change notifications for autosave + redraw.
 */
class DocumentModel(initial: DocumentContent = DocumentContent(version = CURRENT_FORMAT_VERSION)) {

    companion object {
        const val MAX_UNDO = 200
        private const val TAG = "DocumentModel"
    }

    val pages = initial.pages.toMutableList()
    val strokes = initial.strokes.toMutableList()
    val shapes = initial.shapes.toMutableList()
    val texts = initial.texts.toMutableList()
    val images = initial.images.toMutableList()
    val highlights = initial.highlights.toMutableList()
    val underlines = initial.underlines.toMutableList()

    private val undoStack = ArrayDeque<Command>()
    private val redoStack = ArrayDeque<Command>()

    private val _revision = MutableStateFlow(0L)
    /** Increments on every committed change (content or undo/redo). */
    val revision: StateFlow<Long> = _revision

    private val _structureRevision = MutableStateFlow(0L)
    /** Increments when pages are added/removed (layout rebuild needed). */
    val structureRevision: StateFlow<Long> = _structureRevision

    /** Set to true when a partial-recovery happened on load. */
    var partiallyRecovered = false

    var dirty = false
        private set

    // ---------------- accessors ----------------

    fun strokesOn(page: Int): List<Stroke> = strokes.filter { it.page == page }
    fun shapesOn(page: Int): List<ShapeElement> = shapes.filter { it.page == page }
    fun textsOn(page: Int): List<TextElement> = texts.filter { it.page == page }
    fun imagesOn(page: Int): List<ImageElement> = images.filter { it.page == page }
    fun highlightsOn(page: Int): List<HighlightAnnotation> = highlights.filter { it.page == page }
    fun underlinesOn(page: Int): List<UnderlineAnnotation> = underlines.filter { it.page == page }

    fun pageCount(): Int = pages.size

    fun snapshot(): DocumentContent = DocumentContent(
        version = CURRENT_FORMAT_VERSION,
        pages = pages.toList(),
        strokes = strokes.toList(),
        shapes = shapes.toList(),
        texts = texts.toList(),
        images = images.toList(),
        highlights = highlights.toList(),
        underlines = underlines.toList()
    )

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    // ---------------- command application ----------------

    fun apply(cmd: Command) {
        cmd.execute()
        undoStack.addLast(cmd)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        dirty = true
        _revision.value = _revision.value + 1
    }

    fun undo() {
        val cmd = undoStack.removeLastOrNull() ?: return
        cmd.revert()
        redoStack.addLast(cmd)
        dirty = true
        AppLog.d(TAG, "undo (${undoStack.size} left)")
        _revision.value = _revision.value + 1
    }

    fun redo() {
        val cmd = redoStack.removeLastOrNull() ?: return
        cmd.execute()
        undoStack.addLast(cmd)
        dirty = true
        AppLog.d(TAG, "redo")
        _revision.value = _revision.value + 1
    }

    /** Mark persisted (called by autosave after successful write). */
    fun markClean() { dirty = false }

    // ---------------- element mutations (internal, used by commands) ----------------

    fun addPagesInternal(count: Int, width: Float = 595f, height: Float = 842f) {
        val start = pages.size
        for (i in 0 until count) {
            pages.add(PageDef(index = start + i, width = width, height = height, pdfPage = -1))
        }
        _structureRevision.value = _structureRevision.value + 1
    }

    fun setPageDefsInternal(defs: List<PageDef>) {
        pages.clear()
        pages.addAll(defs)
        _structureRevision.value = _structureRevision.value + 1
    }

    // ---------------- ready-made commands ----------------

    fun addStrokeCmd(stroke: Stroke): Command {
        return object : Command {
            override fun execute() { strokes.add(stroke) }
            override fun revert() { strokes.remove(stroke) }
        }
    }

    fun removeStrokesCmd(removed: List<Stroke>): Command {
        return object : Command {
            override fun execute() { strokes.removeAll(removed.toSet()) }
            override fun revert() { strokes.addAll(removed) }
        }
    }

    /** Replace a stroke with one or more segments (pixel eraser split). */
    fun replaceStrokeCmd(original: Stroke, replacements: List<Stroke>): Command {
        return object : Command {
            override fun execute() {
                strokes.remove(original); strokes.addAll(replacements)
            }
            override fun revert() {
                strokes.removeAll(replacements.toSet()); strokes.add(original)
            }
        }
    }

    fun addShapeCmd(s: ShapeElement): Command = object : Command {
        override fun execute() { shapes.add(s) }
        override fun revert() { shapes.remove(s) }
    }

    fun removeShapesCmd(removed: List<ShapeElement>): Command = object : Command {
        override fun execute() { shapes.removeAll(removed.toSet()) }
        override fun revert() { shapes.addAll(removed) }
    }

    fun addShapeCmdScaled(s: ShapeElement, cx: Float, cy: Float, factor: Float): Command {
        val ns = s.copy(
            x1 = cx - (cx - s.x1) * factor,
            y1 = cy - (cy - s.y1) * factor,
            x2 = cx - (cx - s.x2) * factor,
            y2 = cy - (cy - s.y2) * factor,
            width = s.width * factor
        )
        return object : Command {
            override fun execute() {
                val i = shapes.indexOfFirst { it.id == s.id }
                if (i >= 0) shapes[i] = ns
            }
            override fun revert() {
                val i = shapes.indexOfFirst { it.id == s.id }
                if (i >= 0) shapes[i] = s
            }
        }
    }

    fun addTextCmd(t: TextElement): Command = object : Command {
        override fun execute() { texts.add(t) }
        override fun revert() { texts.remove(t) }
    }

    fun updateTextCmd(old: TextElement, new: TextElement): Command = object : Command {
        override fun execute() { texts[texts.indexOf(old)] = new }
        override fun revert() { if (texts.contains(new)) texts[texts.indexOf(new)] = old }
    }

    fun removeTextCmd(removed: List<TextElement>): Command = object : Command {
        override fun execute() { texts.removeAll(removed.toSet()) }
        override fun revert() { texts.addAll(removed) }
    }

    fun addImageCmd(img: ImageElement): Command = object : Command {
        override fun execute() { images.add(img) }
        override fun revert() { images.remove(img) }
    }

    fun removeImagesCmd(removed: List<ImageElement>): Command = object : Command {
        override fun execute() { images.removeAll(removed.toSet()) }
        override fun revert() { images.addAll(removed) }
    }

    fun moveImagesCmd(moves: List<Pair<ImageElement, FloatArray>>): Command {
        // each pair: element -> [dx, dy]
        val orig = moves.map { it.first.copy() }
        return object : Command {
            override fun execute() {
                for ((el, d) in moves) {
                    val i = images.indexOf(el)
                    if (i >= 0) images[i] = el.copy(x = el.x + d[0], y = el.y + d[1])
                }
            }
            override fun revert() {
                for (o in orig) {
                    val i = images.indexOfFirst { it.id == o.id }
                    if (i >= 0) images[i] = o
                }
            }
        }
    }

    fun transformImageCmd(oldEl: ImageElement, newEl: ImageElement): Command = object : Command {
        override fun execute() {
            val i = images.indexOfFirst { it.id == oldEl.id }
            if (i >= 0) images[i] = newEl
        }
        override fun revert() {
            val i = images.indexOfFirst { it.id == oldEl.id }
            if (i >= 0) images[i] = oldEl
        }
    }

    fun moveTextsCmd(moves: List<Pair<TextElement, FloatArray>>): Command {
        val orig = moves.map { it.first.copy() }
        return object : Command {
            override fun execute() {
                for ((el, d) in moves) {
                    val i = texts.indexOf(el)
                    if (i >= 0) texts[i] = el.copy(x = el.x + d[0], y = el.y + d[1])
                }
            }
            override fun revert() {
                for (o in orig) {
                    val i = texts.indexOfFirst { it.id == o.id }
                    if (i >= 0) texts[i] = o
                }
            }
        }
    }

    fun moveStrokesCmd(moves: List<Pair<Stroke, FloatArray>>): Command {
        val orig = moves.map { it.first.copy() }
        return object : Command {
            override fun execute() {
                for ((el, d) in moves) {
                    val i = strokes.indexOf(el)
                    if (i >= 0) strokes[i] = el.copy(
                        points = el.points.map { StrokePoint(it.x + d[0], it.y + d[1], it.p) }
                    )
                }
            }
            override fun revert() {
                for (o in orig) {
                    val i = strokes.indexOfFirst { it.id == o.id }
                    if (i >= 0) strokes[i] = o
                }
            }
        }
    }

    fun moveShapesCmd(moves: List<Pair<ShapeElement, FloatArray>>): Command {
        val orig = moves.map { it.first.copy() }
        return object : Command {
            override fun execute() {
                for ((el, d) in moves) {
                    val i = shapes.indexOf(el)
                    if (i >= 0) shapes[i] = el.copy(x1 = el.x1 + d[0], y1 = el.y1 + d[1], x2 = el.x2 + d[0], y2 = el.y2 + d[1])
                }
            }
            override fun revert() {
                for (o in orig) {
                    val i = shapes.indexOfFirst { it.id == o.id }
                    if (i >= 0) shapes[i] = o
                }
            }
        }
    }

    fun addHighlightCmd(h: HighlightAnnotation): Command = object : Command {
        override fun execute() { highlights.add(h) }
        override fun revert() { highlights.remove(h) }
    }

    fun addUnderlineCmd(u: UnderlineAnnotation): Command = object : Command {
        override fun execute() { underlines.add(u) }
        override fun revert() { underlines.remove(u) }
    }

    fun removeAnnotationsCmd(h: List<HighlightAnnotation>, u: List<UnderlineAnnotation>): Command =
        object : Command {
            override fun execute() {
                highlights.removeAll(h.toSet()); underlines.removeAll(u.toSet())
            }
            override fun revert() { highlights.addAll(h); underlines.addAll(u) }
        }

    fun addPageCmd(): Command {
        lateinit var added: PageDef
        return object : Command {
            override fun execute() {
                val lastW = pages.lastOrNull()?.width ?: 595f
                val lastH = pages.lastOrNull()?.height ?: 842f
                added = PageDef(index = pages.size, width = lastW, height = lastH, pdfPage = -1)
                pages.add(added)
                _structureRevision.value = _structureRevision.value + 1
            }
            override fun revert() {
                pages.remove(added)
                // reindex
                pages.forEachIndexed { i, p -> if (p.index != i) pages[i] = p.copy(index = i) }
                _structureRevision.value = _structureRevision.value + 1
            }
        }
    }
}
