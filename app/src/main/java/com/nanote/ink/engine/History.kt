package com.nanote.ink.engine

import com.nanote.domain.model.PageObject

/**
 * Undo/redo over [PageDoc]. Erase, pixel-erase, move, resize, rotate, recolor
 * and shape-snap all reduce to ReplaceStrokes so a single undo restores the
 * previous content exactly. Object edits (text/image/link) have their own
 * commands. History is per page and per editing session.
 */
interface Command {
    fun redo(doc: PageDoc)
    fun undo(doc: PageDoc)
}

class AddStrokes(private val s: List<Stroke>) : Command {
    override fun redo(doc: PageDoc) = doc.addStrokes(s)
    override fun undo(doc: PageDoc) = doc.removeStrokes(s.map { it.id })
}

/** Erase, pixel-erase, transform, recolor and shape-snap reduce to this. */
class ReplaceStrokes(private val old: List<Stroke>, private val new: List<Stroke>) : Command {
    override fun redo(doc: PageDoc) {
        doc.removeStrokes(old.map { it.id })
        doc.addStrokes(new)
    }

    override fun undo(doc: PageDoc) {
        doc.removeStrokes(new.map { it.id })
        doc.addStrokes(old)
    }
}

class AddObjects(private val objs: List<PageObject>) : Command {
    override fun redo(doc: PageDoc) = doc.addObjects(objs)
    override fun undo(doc: PageDoc) = doc.removeObjects(objs.map { it.id })
}

class ReplaceObject(private val oldId: String, private val old: PageObject, private val new: PageObject) : Command {
    override fun redo(doc: PageDoc) = doc.replaceObject(oldId, new)
    override fun undo(doc: PageDoc) = doc.replaceObject(new.id, old)
}

class RemoveObjects(private val objs: List<PageObject>) : Command {
    override fun redo(doc: PageDoc) = doc.removeObjects(objs.map { it.id })
    override fun undo(doc: PageDoc) = doc.addObjects(objs)
}

class History(private val limit: Int = 300) {
    private val undoStack = ArrayDeque<Command>()
    private val redoStack = ArrayDeque<Command>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val depth: Int get() = undoStack.size

    fun push(c: Command, doc: PageDoc) {
        c.redo(doc)
        undoStack.addLast(c)
        redoStack.clear()
        if (undoStack.size > limit) undoStack.removeFirst()
    }

    /** Push without executing (the caller already applied the change). */
    fun pushApplied(c: Command) {
        undoStack.addLast(c)
        redoStack.clear()
        if (undoStack.size > limit) undoStack.removeFirst()
    }

    fun undo(doc: PageDoc) {
        val c = undoStack.removeLastOrNull() ?: return
        c.undo(doc)
        redoStack.addLast(c)
    }

    fun redo(doc: PageDoc) {
        val c = redoStack.removeLastOrNull() ?: return
        c.redo(doc)
        undoStack.addLast(c)
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
