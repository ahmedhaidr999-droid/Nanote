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

    /** §9 canvas engine mode — PAGED (fixed pages) or INFINITE. */
    var canvasMode: CanvasMode = initial.canvasMode
        private set

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
        underlines = underlines.toList(),
        canvasMode = canvasMode
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
                val template = pages.lastOrNull()?.template ?: PageTemplateKind.BLANK
                added = PageDef(index = pages.size, width = lastW, height = lastH, pdfPage = -1, template = template)
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

    // =====================================================================
    // Part 2 — page management commands (§10)
    // =====================================================================

    /** Adds a page with explicit settings at [atIndex] (default: end). */
    fun addPageWithCmd(def: PageDef, atIndex: Int = pages.size): Command {
        var inserted: PageDef? = null
        return object : Command {
            override fun execute() {
                val idx = atIndex.coerceIn(0, pages.size)
                inserted = def.copy(index = idx)
                pages.add(idx, inserted!!)
                reindexPages()
                shiftElementPagesAtLeast(idx, +1)
                _structureRevision.value = _structureRevision.value + 1
            }
            override fun revert() {
                val idx = pages.indexOfFirst { it === inserted }
                if (idx >= 0) {
                    shiftElementPagesAtLeast(idx + 1, -1)
                    // remove the elements that lived on the inserted page itself
                    val doomed = idx
                    strokes.removeAll { it.page == doomed }
                    shapes.removeAll { it.page == doomed }
                    texts.removeAll { it.page == doomed }
                    images.removeAll { it.page == doomed }
                    pages.removeAt(idx)
                    reindexPages()
                    _structureRevision.value = _structureRevision.value + 1
                }
            }
        }
    }

    /** Deletes page [index] together with its elements; later pages shift down. */
    fun deletePageCmd(index: Int): Command {
        var removedPage: PageDef? = null
        var removedStrokes: List<Stroke> = emptyList()
        var removedShapes: List<ShapeElement> = emptyList()
        var removedTexts: List<TextElement> = emptyList()
        var removedImages: List<ImageElement> = emptyList()
        return object : Command {
            override fun execute() {
                if (index !in pages.indices) return
                removedPage = pages.removeAt(index)
                removedStrokes = strokes.filter { it.page == index }
                removedShapes = shapes.filter { it.page == index }
                removedTexts = texts.filter { it.page == index }
                removedImages = images.filter { it.page == index }
                strokes.removeAll(removedStrokes.toSet())
                shapes.removeAll(removedShapes.toSet())
                texts.removeAll(removedTexts.toSet())
                images.removeAll(removedImages.toSet())
                shiftElementPagesAtLeast(index + 1, -1)
                reindexPages()
                _structureRevision.value = _structureRevision.value + 1
            }
            override fun revert() {
                val at = index.coerceIn(0, pages.size)
                pages.add(at, removedPage ?: return)
                shiftElementPagesAtLeast(at, +1)
                strokes.addAll(removedStrokes.map { it.copy(page = at) })
                shapes.addAll(removedShapes.map { it.copy(page = at) })
                texts.addAll(removedTexts.map { it.copy(page = at) })
                images.addAll(removedImages.map { it.copy(page = at) })
                reindexPages()
                _structureRevision.value = _structureRevision.value + 1
            }
        }
    }

    /** Duplicates page [index] (with elements, fresh ids) right after it. */
    fun duplicatePageCmd(index: Int): Command {
        var newStrokes: List<Stroke> = emptyList()
        var newShapes: List<ShapeElement> = emptyList()
        var newTexts: List<TextElement> = emptyList()
        var newImages: List<ImageElement> = emptyList()
        var insertedAt = -1
        return object : Command {
            override fun execute() {
                if (index !in pages.indices) return
                insertedAt = index + 1
                val src = pages[index]
                pages.add(insertedAt, src.copy(index = insertedAt, title = ""))
                newStrokes = strokes.filter { it.page == index }.map { it.copy(id = newId(), page = insertedAt) }
                newShapes = shapes.filter { it.page == index }.map { it.copy(id = newId(), page = insertedAt) }
                newTexts = texts.filter { it.page == index }.map { it.copy(id = newId(), page = insertedAt) }
                newImages = images.filter { it.page == index }.map { it.copy(id = newId(), page = insertedAt) }
                strokes.addAll(newStrokes)
                shapes.addAll(newShapes)
                texts.addAll(newTexts)
                images.addAll(newImages)
                shiftElementPagesAtLeast(insertedAt + 1, +1)
                reindexPages()
                _structureRevision.value = _structureRevision.value + 1
            }
            override fun revert() {
                if (insertedAt !in pages.indices) return
                strokes.removeAll(newStrokes.toSet())
                shapes.removeAll(newShapes.toSet())
                texts.removeAll(newTexts.toSet())
                images.removeAll(newImages.toSet())
                shiftElementPagesAtLeast(insertedAt + 1, -1)
                pages.removeAt(insertedAt)
                reindexPages()
                _structureRevision.value = _structureRevision.value + 1
            }
        }
    }

    /** Renames page [index]. */
    fun renamePageCmd(index: Int, title: String): Command {
        val old = pages.getOrNull(index)
        if (old == null) return noopCommand()
        return object : Command {
            override fun execute() { if (index in pages.indices) pages[index] = pages[index].copy(title = title) }
            override fun revert() { if (index in pages.indices) pages[index] = old }
        }
    }

    /** Moves page [from] to [to]; all elements follow their page. */
    fun reorderPageCmd(from: Int, to: Int): Command {
        if (from == to || from !in pages.indices) return noopCommand()
        val target = to.coerceIn(0, pages.size - 1)
        return object : Command {
            override fun execute() {
                val page = pages.removeAt(from)
                pages.add(target, page)
                val mapping = buildReorderMapping(from, target)
                remapElementPages(mapping)
                reindexPages()
                _structureRevision.value = _structureRevision.value + 1
            }
            override fun revert() {
                val page = pages.removeAt(target)
                pages.add(from, page)
                val mapping = buildReorderMapping(target, from)
                remapElementPages(mapping)
                reindexPages()
                _structureRevision.value = _structureRevision.value + 1
            }
        }
    }

    /**
     * Replaces a page definition (template / size / orientation — §10).
     * Changing the background never touches the handwriting.
     */
    fun setPageDefCmd(index: Int, def: PageDef): Command {
        val old = pages.getOrNull(index) ?: return noopCommand()
        return object : Command {
            override fun execute() {
                if (index in pages.indices) pages[index] = def.copy(index = index)
                _structureRevision.value = _structureRevision.value + 1
            }
            override fun revert() {
                if (index in pages.indices) pages[index] = old
                _structureRevision.value = _structureRevision.value + 1
            }
        }
    }

    // =====================================================================
    // Part 2 — transform commands (§8 / §11)
    // =====================================================================

    /** Replaces strokes with transformed copies (move/scale/rotate batches). */
    fun transformStrokesCmd(pairs: List<Pair<Stroke, Stroke>>): Command = object : Command {
        override fun execute() {
            for ((old, new) in pairs) {
                val i = strokes.indexOfFirst { it.id == old.id }
                if (i >= 0) strokes[i] = new
            }
        }
        override fun revert() {
            for ((old, new) in pairs) {
                val i = strokes.indexOfFirst { it.id == new.id }
                if (i >= 0) strokes[i] = old
            }
        }
    }

    fun transformTextsCmd(pairs: List<Pair<TextElement, TextElement>>): Command = object : Command {
        override fun execute() {
            for ((old, new) in pairs) {
                val i = texts.indexOfFirst { it.id == old.id }
                if (i >= 0) texts[i] = new
            }
        }
        override fun revert() {
            for ((old, new) in pairs) {
                val i = texts.indexOfFirst { it.id == new.id }
                if (i >= 0) texts[i] = old
            }
        }
    }

    fun transformShapesCmd(pairs: List<Pair<ShapeElement, ShapeElement>>): Command = object : Command {
        override fun execute() {
            for ((old, new) in pairs) {
                val i = shapes.indexOfFirst { it.id == old.id }
                if (i >= 0) shapes[i] = new
            }
        }
        override fun revert() {
            for ((old, new) in pairs) {
                val i = shapes.indexOfFirst { it.id == new.id }
                if (i >= 0) shapes[i] = old
            }
        }
    }

    fun transformImagesCmd(pairs: List<Pair<ImageElement, ImageElement>>): Command = object : Command {
        override fun execute() {
            for ((old, new) in pairs) {
                val i = images.indexOfFirst { it.id == old.id }
                if (i >= 0) images[i] = new
            }
        }
        override fun revert() {
            for ((old, new) in pairs) {
                val i = images.indexOfFirst { it.id == new.id }
                if (i >= 0) images[i] = old
            }
        }
    }

    // =====================================================================
    // Part 2 — grouping (§8) & multi-type paste composites
    // =====================================================================

    /** Assigns one shared groupId to every listed element. */
    fun groupCmd(
        strokeIds: Set<String>, textIds: Set<String>, imageIds: Set<String>, shapeIds: Set<String>
    ): Command {
        val gid = newId()
        var oldStrokes: List<Stroke> = emptyList()
        var oldTexts: List<TextElement> = emptyList()
        var oldImages: List<ImageElement> = emptyList()
        var oldShapes: List<ShapeElement> = emptyList()
        return object : Command {
            override fun execute() {
                oldStrokes = strokes.filter { it.id in strokeIds }
                oldTexts = texts.filter { it.id in textIds }
                oldImages = images.filter { it.id in imageIds }
                oldShapes = shapes.filter { it.id in shapeIds }
                strokes.replaceAll { if (it.id in strokeIds) it.copy(groupId = gid) else it }
                texts.replaceAll { if (it.id in textIds) it.copy(groupId = gid) else it }
                images.replaceAll { if (it.id in imageIds) it.copy(groupId = gid) else it }
                shapes.replaceAll { if (it.id in shapeIds) it.copy(groupId = gid) else it }
            }
            override fun revert() {
                strokes.replaceAll { cur -> oldStrokes.firstOrNull { it.id == cur.id } ?: cur }
                texts.replaceAll { cur -> oldTexts.firstOrNull { it.id == cur.id } ?: cur }
                images.replaceAll { cur -> oldImages.firstOrNull { it.id == cur.id } ?: cur }
                shapes.replaceAll { cur -> oldShapes.firstOrNull { it.id == cur.id } ?: cur }
            }
        }
    }

    /** Strips the given [groupId] from every element carrying it. */
    fun ungroupCmd(groupId: String): Command {
        var oldStrokes: List<Stroke> = emptyList()
        var oldTexts: List<TextElement> = emptyList()
        var oldImages: List<ImageElement> = emptyList()
        var oldShapes: List<ShapeElement> = emptyList()
        return object : Command {
            override fun execute() {
                oldStrokes = strokes.filter { it.groupId == groupId }
                oldTexts = texts.filter { it.groupId == groupId }
                oldImages = images.filter { it.groupId == groupId }
                oldShapes = shapes.filter { it.groupId == groupId }
                strokes.replaceAll { if (it.groupId == groupId) it.copy(groupId = null) else it }
                texts.replaceAll { if (it.groupId == groupId) it.copy(groupId = null) else it }
                images.replaceAll { if (it.groupId == groupId) it.copy(groupId = null) else it }
                shapes.replaceAll { if (it.groupId == groupId) it.copy(groupId = null) else it }
            }
            override fun revert() {
                strokes.replaceAll { cur -> oldStrokes.firstOrNull { it.id == cur.id } ?: cur }
                texts.replaceAll { cur -> oldTexts.firstOrNull { it.id == cur.id } ?: cur }
                images.replaceAll { cur -> oldImages.firstOrNull { it.id == cur.id } ?: cur }
                shapes.replaceAll { cur -> oldShapes.firstOrNull { it.id == cur.id } ?: cur }
            }
        }
    }

    // =====================================================================
    // Part 2 — multi-type composites (§8 paste / cut)
    // =====================================================================

    /** Adds a mixed batch of elements in ONE undo step (paste). */
    fun addElementsCmd(
        newStrokes: List<Stroke>,
        newTexts: List<TextElement>,
        newImages: List<ImageElement>,
        newShapes: List<ShapeElement>
    ): Command = object : Command {
        override fun execute() {
            strokes.addAll(newStrokes)
            texts.addAll(newTexts)
            images.addAll(newImages)
            shapes.addAll(newShapes)
        }
        override fun revert() {
            strokes.removeAll(newStrokes.toSet())
            texts.removeAll(newTexts.toSet())
            images.removeAll(newImages.toSet())
            shapes.removeAll(newShapes.toSet())
        }
    }

    /** Removes a mixed batch of elements in ONE undo step (cut / delete selection). */
    fun removeElementsCmd(
        strokes: List<Stroke>,
        texts: List<TextElement>,
        images: List<ImageElement>,
        shapes: List<ShapeElement>
    ): Command = object : Command {
        override fun execute() {
            this@DocumentModel.strokes.removeAll(strokes.toSet())
            this@DocumentModel.texts.removeAll(texts.toSet())
            this@DocumentModel.images.removeAll(images.toSet())
            this@DocumentModel.shapes.removeAll(shapes.toSet())
        }
        override fun revert() {
            this@DocumentModel.strokes.addAll(strokes)
            this@DocumentModel.texts.addAll(texts)
            this@DocumentModel.images.addAll(images)
            this@DocumentModel.shapes.addAll(shapes)
        }
    }

    /** §9 — switches the canvas engine mode (PAGED ↔ INFINITE). */
    fun setCanvasModeCmd(mode: CanvasMode): Command {
        val old = canvasMode
        return object : Command {
            override fun execute() {
                canvasMode = mode
                _structureRevision.value = _structureRevision.value + 1
            }
            override fun revert() {
                canvasMode = old
                _structureRevision.value = _structureRevision.value + 1
            }
        }
    }

    // =====================================================================
    // Part 2 — page/element helpers
    // =====================================================================

    private fun noopCommand(): Command = object : Command {
        override fun execute() {}
        override fun revert() {}
    }

    private fun reindexPages() {
        pages.forEachIndexed { i, p -> if (p.index != i) pages[i] = p.copy(index = i) }
    }

    /** Shifts the page index of every element whose page >= [from] by [delta]. */
    private fun shiftElementPagesAtLeast(from: Int, delta: Int) {
        fun shift(page: Int): Int = if (page >= from) page + delta else page
        strokes.replaceAll { it.copy(page = shift(it.page)) }
        shapes.replaceAll { it.copy(page = shift(it.page)) }
        texts.replaceAll { it.copy(page = shift(it.page)) }
        images.replaceAll { it.copy(page = shift(it.page)) }
    }

    /**
     * Page permutation when a page moves [from] → [to]: returns oldIndex → newIndex.
     * Example: 0,1,2 with move(0→2): pages end as 1,2,0 → mapping {0→2, 1→0, 2→1}.
     */
    private fun buildReorderMapping(from: Int, to: Int): Map<Int, Int> {
        val mapping = HashMap<Int, Int>()
        val n = pages.size
        for (old in 0 until n) {
            val new = when {
                old == from -> to
                from < to && old in (from + 1)..to -> old - 1
                from > to && old in to until from -> old + 1
                else -> old
            }
            mapping[old] = new
        }
        return mapping
    }

    private fun remapElementPages(mapping: Map<Int, Int>) {
        fun remap(page: Int): Int = mapping[page] ?: page
        strokes.replaceAll { it.copy(page = remap(it.page)) }
        shapes.replaceAll { it.copy(page = remap(it.page)) }
        texts.replaceAll { it.copy(page = remap(it.page)) }
        images.replaceAll { it.copy(page = remap(it.page)) }
    }
}
