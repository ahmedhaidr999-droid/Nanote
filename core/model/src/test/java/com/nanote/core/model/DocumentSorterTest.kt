package com.nanote.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM tests for the shared sorting rules (locale-aware). */
class DocumentSorterTest {

    private fun doc(
        id: String,
        title: String,
        created: Long = 0,
        modified: Long = 0,
        opened: Long = 0,
        order: Int = 0
    ) = DocumentMeta(
        id = id, title = title, kind = NoteKind.NOTE,
        createdAt = created, updatedAt = modified, lastOpenedAt = opened, sortOrder = order
    )

    @Test
    fun `recently modified sorts descending by updatedAt`() {
        val docs = listOf(doc("a", "A", modified = 5), doc("b", "B", modified = 9), doc("c", "C", modified = 1))
        val sorted = DocumentSorter.sort(docs, SortOption.RECENTLY_MODIFIED)
        assertEquals(listOf("b", "a", "c"), sorted.map { it.id })
    }

    @Test
    fun `recently created sorts descending by createdAt`() {
        val docs = listOf(doc("a", "A", created = 50), doc("b", "B", created = 90), doc("c", "C", created = 10))
        val sorted = DocumentSorter.sort(docs, SortOption.RECENTLY_CREATED)
        assertEquals(listOf("b", "a", "c"), sorted.map { it.id })
    }

    @Test
    fun `custom order sorts by sortOrder`() {
        val docs = listOf(doc("a", "A", order = 2), doc("b", "B", order = 0), doc("c", "C", order = 1))
        val sorted = DocumentSorter.sort(docs, SortOption.CUSTOM)
        assertEquals(listOf("b", "c", "a"), sorted.map { it.id })
    }

    @Test
    fun `byLastActivity uses the most recent of opened modified created`() {
        val docs = listOf(
            doc("old", "Old", created = 1, modified = 2, opened = 0),
            doc("opened", "Opened", created = 1, modified = 2, opened = 100),
            doc("modified", "Modified", created = 1, modified = 50, opened = 0)
        )
        val sorted = DocumentSorter.byLastActivity(docs)
        assertEquals(listOf("opened", "modified", "old"), sorted.map { it.id })
    }

    @Test
    fun `blank titles sort last in name order`() {
        val docs = listOf(doc("blank", ""), doc("z", "Zebra"), doc("a", "Apple"))
        val sorted = DocumentSorter.sort(docs, SortOption.NAME)
        assertEquals(listOf("a", "z", "blank"), sorted.map { it.id })
    }
}
