package com.nanote.core.storage.format

import com.nanote.core.model.BackupEntry
import com.nanote.core.model.BackupManifest
import com.nanote.core.model.CURRENT_BACKUP_VERSION
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for the `.nanote-backup` archive format: write → read
 * → verify manifest, rows and extracted files. Runs on the plain JVM (the
 * format has no Android dependencies).
 */
class BackupFormatTest {

    private fun tempDir(): File = Files.createTempDirectory("nanote-backup").toFile()

    private fun noteRow(id: String, title: String, kind: String = "NOTE") = BackupNoteRow(
        id = id, title = title, kind = kind, favorite = false,
        folderId = null, createdAt = 1, updatedAt = 2, pageCount = 3
    )

    @Test
    fun roundTrip_preservesManifestRowsAndFiles() {
        val dir = tempDir()
        try {
            // --- build a fake document directory
            val docDir = File(dir, "documents/doc1").apply { mkdirs() }
            File(docDir, "doc.nanote").writeText("""{"version":2,"pages":[]}""")
            File(docDir, "source.pdf").writeBytes(byteArrayOf(1, 2, 3, 4))
            File(docDir, "images/pic.png").apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(9, 9))

            val manifest = BackupManifest(
                formatVersion = CURRENT_BACKUP_VERSION,
                appVersion = "1.2.0-test",
                createdAt = 42L,
                kind = "FULL",
                entries = listOf(BackupEntry("doc1", "My notebook", "NOTE", 1, null, 128))
            )
            val rows = BackupRows(
                notes = listOf(noteRow("doc1", "My notebook")),
                folders = listOf(BackupFolderRow("f1", "Uni", 0)),
                tags = listOf(BackupTagRow("study", 0xFF2563EB, 5)),
                noteTags = listOf(BackupNoteTagRow("doc1", "study")),
                pdfText = listOf(BackupPdfTextRow("doc1", 0, -1, "hello world")),
                bookmarks = listOf(BackupBookmarkRow("b1", "doc1", 0, -1, "Cover", 7))
            )

            val archiveFile = File(dir, "backup.nanote-backup")
            val percents = ArrayList<Int>()
            val ok = BackupFormat.write(
                archiveFile.outputStream(),
                rows,
                manifest,
                docDirProvider = { id -> if (id == "doc1") docDir else null },
                progress = { percents.add(it) }
            )
            assertTrue(ok)
            assertTrue(percents.isNotEmpty())
            assertEquals(100, percents.last().toInt())

            ZipFile(archiveFile).use { zip ->
                val readManifest = BackupFormat.readManifest(zip)
                assertNotNull(readManifest)
                assertEquals(CURRENT_BACKUP_VERSION, readManifest!!.formatVersion)
                assertEquals("FULL", readManifest.kind)
                assertEquals(42L, readManifest.createdAt)
                assertEquals(1, readManifest.entries.size)
                assertEquals("My notebook", readManifest.entries.first().title)
                assertEquals(128L, readManifest.entries.first().bytes)

                val readRows = BackupFormat.readRows(zip)
                assertEquals(1, readRows.notes.size)
                assertEquals("My notebook", readRows.notes.first().title)
                assertEquals("NOTE", readRows.notes.first().kind)
                assertEquals(1, readRows.folders.size)
                assertEquals("Uni", readRows.folders.first().name)
                assertEquals(1, readRows.tags.size)
                assertEquals("study", readRows.tags.first().name)
                assertEquals(1, readRows.noteTags.size)
                assertEquals(1, readRows.pdfText.size)
                assertEquals("hello world", readRows.pdfText.first().text)
                assertEquals(1, readRows.bookmarks.size)

                val outDir = File(dir, "restore-staging")
                val extracted = BackupFormat.extractFiles(zip, listOf("doc1"), outDir)
                assertEquals(listOf("doc1"), extracted)
                assertEquals(
                    """{"version":2,"pages":[]}""",
                    File(outDir, "doc1/doc.nanote").readText()
                )
                assertEquals(4L, File(outDir, "doc1/source.pdf").length())
                assertEquals(2L, File(outDir, "doc1/images/pic.png").length())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readManifest_rejectsFutureVersions() {
        val dir = tempDir()
        try {
            val manifest = BackupManifest(
                formatVersion = CURRENT_BACKUP_VERSION + 5,
                createdAt = 1L,
                kind = "FULL"
            )
            val archiveFile = File(dir, "future.nanote-backup")
            assertTrue(
                BackupFormat.write(archiveFile.outputStream(), BackupRows(), manifest, { null }) { }
            )
            ZipFile(archiveFile).use { zip ->
                assertNull(BackupFormat.readManifest(zip))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readManifest_returnsNullForNonBackupZip() {
        val dir = tempDir()
        try {
            val plain = File(dir, "plain.zip")
            ZipOutputStream(plain.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("random.txt"))
                zip.write("hello".toByteArray())
                zip.closeEntry()
            }
            ZipFile(plain).use { zip ->
                assertNull(BackupFormat.readManifest(zip))
                // Rows degrade to empty instead of throwing.
                assertTrue(BackupFormat.readRows(zip).notes.isEmpty())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun write_skipsMissingDocumentDirs() {
        val dir = tempDir()
        try {
            val manifest = BackupManifest(
                createdAt = 1L,
                kind = "SELECTION",
                entries = listOf(
                    BackupEntry("present", "Present", "NOTE", 1),
                    BackupEntry("absent", "Absent", "PDF", 2)
                )
            )
            val presentDir = File(dir, "documents/present").apply { mkdirs() }
            File(presentDir, "doc.nanote").writeText("{}")

            val archiveFile = File(dir, "partial.nanote-backup")
            val ok = BackupFormat.write(
                archiveFile.outputStream(),
                BackupRows(notes = listOf(noteRow("present", "Present"))),
                manifest,
                docDirProvider = { id -> if (id == "present") presentDir else null }
            )
            assertTrue(ok)
            ZipFile(archiveFile).use { zip ->
                assertNotNull(BackupFormat.readManifest(zip))
                val outDir = File(dir, "staging")
                val extracted = BackupFormat.extractFiles(zip, listOf("present", "absent"), outDir)
                assertEquals(listOf("present"), extracted)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun defaultFileName_isStableAndDated() {
        val cal = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.MARCH, 5, 14, 7, 0)
        }
        val name = BackupFormat.defaultFileName(cal.timeInMillis)
        assertEquals("nanote-backup-20260305-1407.nanote-backup", name)
    }
}
