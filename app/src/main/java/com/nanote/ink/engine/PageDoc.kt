package com.nanote.ink.engine

/**
 * In-memory page: the stroke list (insertion-ordered = z order), its
 * SpatialGrid, typed/text/image objects and a revision counter that
 * invalidates cached tiles. All methods run on the main thread only.
 *
 * [PageDoc] plus [History] plus the tile cache form the editing session state
 * of one page; history lives only for the editing session.
 */
class PageDoc(
    val widthPt: Float,
    val heightPt: Float,
    initialStrokes: List<Stroke> = emptyList()
) {
    private val strokes = LinkedHashMap<Int, Stroke>()
    val grid = SpatialGrid()
    val objects = ArrayList<com.nanote.domain.model.PageObject>()

    var revision: Long = 0
        private set
    var maxStrokeId: Int = 0
        private set

    private class Change(val rev: Long, val b: Bounds)

    private val changeLog = ArrayDeque<Change>()

    init {
        if (initialStrokes.isNotEmpty()) addStrokes(initialStrokes)
    }

    val strokeCount: Int get() = strokes.size

    fun nextStrokeId(): Int {
        maxStrokeId += 1
        return maxStrokeId
    }

    fun addStrokes(list: List<Stroke>) {
        if (list.isEmpty()) return
        val dirty = Bounds()
        for (s in list) {
            strokes[s.id] = s
            grid.insert(s.id, s.bounds)
            dirty.union(s.bounds)
            if (s.id > maxStrokeId) maxStrokeId = s.id
        }
        bump(dirty)
    }

    fun removeStrokes(ids: List<Int>) {
        if (ids.isEmpty()) return
        val dirty = Bounds()
        for (id in ids) {
            val s = strokes.remove(id) ?: continue
            grid.remove(id, s.bounds)
            dirty.union(s.bounds)
        }
        bump(dirty)
    }

    fun strokeById(id: Int): Stroke? = strokes[id]

    fun containsStroke(id: Int): Boolean = strokes.containsKey(id)

    /** Copy of the stroke list in z order. Called on saves and tile renders. */
    fun strokesSnapshot(): List<Stroke> = ArrayList(strokes.values)

    fun objectsSnapshot(): List<com.nanote.domain.model.PageObject> = ArrayList(objects)

    fun addObjects(objs: List<com.nanote.domain.model.PageObject>) {
        objects.addAll(objs)
        val dirty = Bounds()
        for (o in objs) {
            dirty.union(o.anchorX - 4f, o.anchorY - 4f)
            dirty.union(o.anchorX + 4f, o.anchorY + 4f)
        }
        bump(dirty)
    }

    fun removeObjects(ids: List<String>) {
        val it = objects.iterator()
        var removed = false
        val dirty = Bounds()
        while (it.hasNext()) {
            val o = it.next()
            if (o.id in ids) {
                dirty.union(o.anchorX - 4f, o.anchorY - 4f)
                dirty.union(o.anchorX + 4f, o.anchorY + 4f)
                it.remove()
                removed = true
            }
        }
        if (removed) bump(dirty)
    }

    fun replaceObject(oldId: String, new: com.nanote.domain.model.PageObject) {
        val idx = objects.indexOfFirst { it.id == oldId }
        if (idx >= 0) {
            val old = objects[idx]
            val dirty = Bounds()
            dirty.union(old.anchorX - 4f, old.anchorY - 4f)
            dirty.union(old.anchorX + 4f, old.anchorY + 4f)
            objects[idx] = new
            dirty.union(new.anchorX - 4f, new.anchorY - 4f)
            dirty.union(new.anchorX + 4f, new.anchorY + 4f)
            bump(dirty)
        }
    }

    private fun bump(dirty: Bounds) {
        revision += 1
        changeLog.addLast(Change(revision, dirty))
        while (changeLog.size > MAX_CHANGES) changeLog.removeFirst()
    }

    /**
     * Union of all regions changed strictly AFTER [rev]. A tile captured at
     * [rev] can keep its bitmap when this returns null for the tile rect.
     */
    fun dirtySince(rev: Long, out: Bounds): Boolean {
        var any = false
        out.reset()
        for (c in changeLog) {
            if (c.rev > rev && !c.b.isEmpty) {
                out.union(c.b)
                any = true
            }
        }
        return any
    }

    companion object {
        private const val MAX_CHANGES = 512
    }
}
