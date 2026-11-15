package com.nanote.core.storage.format

import com.nanote.core.common.AppLog
import com.nanote.core.model.CURRENT_BACKUP_VERSION
import com.nanote.core.model.BackupEntry
import com.nanote.core.model.BackupManifest
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// ---------------------------------------------------------------------------
// Serializable row mirrors (decoupled from Room so the format is stable
// across app versions and testable on the plain JVM).
// ---------------------------------------------------------------------------

@kotlinx.serialization.Serializable
data class BackupNoteRow(
    val id: String,
    val title: String,
    val kind: String,
    val favorite: Boolean,
    val folderId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int,
    val coverColor: String = "BLUE",
    val icon: String = "NOTEBOOK",
    val pageTemplate: String = "LINED",
    val pageOrientation: String = "PORTRAIT",
    val pageSize: String = "A4",
    val lastOpenedAt: Long = 0,
    val sortOrder: Int = 0,
    val fileSizeBytes: Long = 0,
    val sourceFileName: String? = null
)

@kotlinx.serialization.Serializable
data class BackupFolderRow(
    val id: String,
    val name: String,
    val sortOrder: Int,
    val parentId: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

@kotlinx.serialization.Serializable
data class BackupTagRow(
    val name: String,
    val color: Long,
    val createdAt: Long
)

@kotlinx.serialization.Serializable
data class BackupNoteTagRow(
    val noteId: String,
    val tagName: String
)

@kotlinx.serialization.Serializable
data class BackupPdfTextRow(
    val documentId: String,
    val pageIndex: Int,
    val pdfPage: Int,
    val text: String
)

@kotlinx.serialization.Serializable
data class BackupBookmarkRow(
    val id: String,
    val documentId: String,
    val pageIndex: Int,
    val pdfPage: Int,
    val title: String,
    val createdAt: Long
)

/** All index rows carried by one archive. */
data class BackupRows(
    val notes: List<BackupNoteRow> = emptyList(),
    val folders: List<BackupFolderRow> = emptyList(),
    val tags: List<BackupTagRow> = emptyList(),
    val noteTags: List<BackupNoteTagRow> = emptyList(),
    val pdfText: List<BackupPdfTextRow> = emptyList(),
    val bookmarks: List<BackupBookmarkRow> = emptyList()
)

/**
 * The `.nanote-backup` archive format (versioned, documented, offline).
 *
 * Layout:
 * ```
 * manifest.json      — BackupManifest {formatVersion, appVersion, createdAt, kind, entries}
 * folders.json       — folder rows referenced by the backed-up documents
 * notes.json         — document index rows
 * tags.json          — tag rows
 * note_tags.json     — document ⇄ tag assignments
 * pdf_text.json      — extracted PDF text index (search survives restore)
 * bookmarks.json     — PDF bookmarks
 * files/<docId>/…    — full document directories (doc.nanote, source.pdf, images…)
 * ```
 *
 * Writing is streamed; reading is random-access through [ZipFile] (the
 * caller stages the SAF stream into a temp file first). Unknown future
 * format versions are rejected by [readManifest].
 */
object BackupFormat {

    private const val TAG = "BackupFormat"

    const val MANIFEST_ENTRY = "manifest.json"
    const val NOTES_ENTRY = "notes.json"
    const val FOLDERS_ENTRY = "folders.json"
    const val TAGS_ENTRY = "tags.json"
    const val NOTE_TAGS_ENTRY = "note_tags.json"
    const val PDF_TEXT_ENTRY = "pdf_text.json"
    const val BOOKMARKS_ENTRY = "bookmarks.json"
    const val FILES_PREFIX = "files/"

    const val EXTENSION = "nanote-backup"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ------------------------------------------------------------------
    // writing
    // ------------------------------------------------------------------

    /**
     * Streams a complete archive to [target].
     *
     * @param rows the index rows to embed
     * @param manifest the archive manifest (entries should match the ids in
     *        [docDirProvider])
     * @param docDirProvider resolves a document id to its on-disk directory
     *        (its whole tree is embedded); null skips that id
     * @param progress optional 0..100 callback (called after each document)
     * @return true on success; [target] is best-effort closed by the caller
     */
    fun write(
        target: OutputStream,
        rows: BackupRows,
        manifest: BackupManifest,
        docDirProvider: (String) -> File?,
        progress: (Int) -> Unit = {}
    ): Boolean {
        return try {
            ZipOutputStream(target.buffered()).use { zip ->
                putJson(zip, MANIFEST_ENTRY, json.encodeToString(BackupManifest.serializer(), manifest))
                putJson(zip, NOTES_ENTRY, json.encodeToString(ListSerializer(BackupNoteRow.serializer()), rows.notes))
                putJson(zip, FOLDERS_ENTRY, json.encodeToString(ListSerializer(BackupFolderRow.serializer()), rows.folders))
                putJson(zip, TAGS_ENTRY, json.encodeToString(ListSerializer(BackupTagRow.serializer()), rows.tags))
                putJson(zip, NOTE_TAGS_ENTRY, json.encodeToString(ListSerializer(BackupNoteTagRow.serializer()), rows.noteTags))
                putJson(zip, PDF_TEXT_ENTRY, json.encodeToString(ListSerializer(BackupPdfTextRow.serializer()), rows.pdfText))
                putJson(zip, BOOKMARKS_ENTRY, json.encodeToString(ListSerializer(BackupBookmarkRow.serializer()), rows.bookmarks))

                val ids = manifest.entries.map { it.id }.distinct()
                ids.forEachIndexed { index, id ->
                    val dir = docDirProvider(id)
                    if (dir != null && dir.exists()) {
                        zipDir(zip, dir, id)
                    } else {
                        AppLog.w(TAG, "write: missing dir for $id, skipped")
                    }
                    progress(((index + 1) * 100) / ids.size.coerceAtLeast(1))
                }
            }
            true
        } catch (t: Throwable) {
            AppLog.e(TAG, "write failed", t)
            false
        }
    }

    private fun putJson(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun zipDir(zip: ZipOutputStream, dir: File, docId: String) {
        val base = FILES_PREFIX + docId + "/"
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = file.relativeTo(dir).invariantSeparatorsPath
            val entry = ZipEntry(base + rel).apply {
                time = file.lastModified()
            }
            zip.putNextEntry(entry)
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    // ------------------------------------------------------------------
    // reading
    // ------------------------------------------------------------------

    /** Reads and validates the manifest; null when missing/corrupt/unsupported. */
    fun readManifest(zip: ZipFile): BackupManifest? {
        return try {
            val entry = zip.getEntry(MANIFEST_ENTRY) ?: run {
                AppLog.w(TAG, "readManifest: $MANIFEST_ENTRY missing")
                return null
            }
            val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            val manifest = json.decodeFromString(BackupManifest.serializer(), text)
            if (manifest.formatVersion > CURRENT_BACKUP_VERSION) {
                AppLog.w(TAG, "readManifest: future version ${manifest.formatVersion}")
                return null
            }
            manifest
        } catch (t: Throwable) {
            AppLog.e(TAG, "readManifest failed", t)
            null
        }
    }

    /** Reads every index row; missing files yield empty lists. */
    fun readRows(zip: ZipFile): BackupRows {
        return try {
            BackupRows(
                notes = readList(zip, NOTES_ENTRY, BackupNoteRow.serializer()),
                folders = readList(zip, FOLDERS_ENTRY, BackupFolderRow.serializer()),
                tags = readList(zip, TAGS_ENTRY, BackupTagRow.serializer()),
                noteTags = readList(zip, NOTE_TAGS_ENTRY, BackupNoteTagRow.serializer()),
                pdfText = readList(zip, PDF_TEXT_ENTRY, BackupPdfTextRow.serializer()),
                bookmarks = readList(zip, BOOKMARKS_ENTRY, BackupBookmarkRow.serializer())
            )
        } catch (t: Throwable) {
            AppLog.e(TAG, "readRows failed", t)
            BackupRows()
        }
    }

    private fun <T> readList(zip: ZipFile, name: String, serializer: kotlinx.serialization.KSerializer<T>): List<T> {
        val entry = zip.getEntry(name) ?: return emptyList()
        val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
        return json.decodeFromString(ListSerializer(serializer), text)
    }

    /**
     * Extracts `files/<docId>/…` of the given ids into [targetDir] under
     * `targetDir/<docId>/…`. Returns the ids actually extracted.
     */
    fun extractFiles(
        zip: ZipFile,
        docIds: Collection<String>,
        targetDir: File,
        progress: (Int) -> Unit = {}
    ): List<String> {
        val extracted = ArrayList<String>()
        val wanted = docIds.toHashSet()
        try {
            val entries = zip.entries().asSequence().toList()
            var done = 0
            for (entry in entries) {
                if (!entry.name.startsWith(FILES_PREFIX)) continue
                val rest = entry.name.removePrefix(FILES_PREFIX)
                if (rest.isEmpty()) continue
                val docId = rest.substringBefore('/')
                if (docId !in wanted) continue
                val rel = rest.substringAfter('/')
                if (rel.isEmpty()) continue
                val outFile = File(File(targetDir, docId), rel)
                outFile.parentFile?.mkdirs()
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (docId !in extracted) extracted.add(docId)
                done++
                progress(((done * 100) / entries.size.coerceAtLeast(1)))
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "extractFiles failed", t)
        }
        return extracted
    }

    /** Default archive file name for a given timestamp. */
    fun defaultFileName(nowMillis: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
        fun pad(n: Int) = n.toString().padStart(2, '0')
        return "nanote-backup-" +
            "${cal.get(java.util.Calendar.YEAR)}" +
            "${pad(cal.get(java.util.Calendar.MONTH) + 1)}" +
            "${pad(cal.get(java.util.Calendar.DAY_OF_MONTH))}" +
            "-" +
            "${pad(cal.get(java.util.Calendar.HOUR_OF_DAY))}" +
            "${pad(cal.get(java.util.Calendar.MINUTE))}" +
            ".$EXTENSION"
    }
}
