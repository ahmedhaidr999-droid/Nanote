package com.nanote.archive

import com.google.common.truth.Truth.assertThat
import com.nanote.data.files.NotebookArchive
import com.nanote.ink.engine.PenType
import com.nanote.ink.engine.strokeOf
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

class NotebookArchiveTest {

    private val stroke = strokeOf(
        1, PenType.BALLPOINT, 0xFF123456.toInt(), 2f,
        floatArrayOf(0f, 0f, 10f, 10f, 20f, 5f)
    )

    private fun tempDir(): File {
        val d = File("/tmp", "nanote-archive-" + Random.nextLong().toString(16))
        d.mkdirs()
        return d
    }

    @Test
    fun `round trip keeps page files`() {
        val root = tempDir()
        val nbId = "nb-1"
        val pageId = "page-1"
        val dir = File(File(root, nbId), "pages")
        dir.mkdirs()
        val inkBytes = com.nanote.ink.engine.InkFormat.encode(listOf(stroke))
        File(dir, "$pageId.ink").writeBytes(inkBytes)

        val page = NotebookArchive.ArchivePage(
            id = pageId, position = 0, widthPt = 595f, heightPt = 842f,
            paper = "blank", bgArgb = 0xFFFFFFFF.toInt()
        )
        val manifest = NotebookArchive.ArchiveManifest(
            notebook = NotebookArchive.ArchiveNotebook(
                id = nbId, title = "Test", coverArgb = 0xFF4356C4.toInt(),
                createdAt = 1, updatedAt = 2
            ),
            pages = listOf(page)
        )

        val out = ByteArrayOutputStream()
        NotebookArchive.write(
            out, manifest,
            pageFiles = mapOf(pageId to (File(dir, "$pageId.ink") to null)),
            assetFiles = emptyMap()
        )
        val read = NotebookArchive.read(ByteArrayInputStream(out.toByteArray()))

        assertThat(read.manifest.notebook.title).isEqualTo("Test")
        assertThat(read.pageFiles).containsKey(pageId)
        val (ink, _) = read.pageFiles[pageId]!!
        assertThat(ink).isNotNull()
        assertThat(com.nanote.ink.engine.InkFormat.decode(ink!!)).isEqualTo(listOf(stroke))
        root.deleteRecursively()
    }
}
