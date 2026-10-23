package com.nanote.data.files

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Whole-library backup (spec: Backup/Restore through SAF): a ZIP of
 *   manifest.json                                metadata (folders/notebooks/pages/tags)
 *   data/notebooks/<nbId>/pages/<pageId>.ink     strokes
 *   data/notebooks/<nbId>/pages/<pageId>.json    objects
 *   data/notebooks/<nbId>/assets/<file>          imported PDFs/images
 *
 * Restore merges by UUID; when a notebook id already exists the incoming copy
 * keeps BOTH (fresh id) or is skipped — the user chooses in the dialog.
 */
object LibraryArchive {

    const val MANIFEST = "manifest.json"
    private const val DATA_PREFIX = "data/"

    @Serializable
    data class ArchiveFolder(
        val id: String,
        val parentId: String?,
        val name: String,
        val createdAt: Long,
        val updatedAt: Long
    )

    @Serializable
    data class ArchivePage(
        val id: String,
        val position: Int,
        val widthPt: Float,
        val heightPt: Float,
        val paper: String,
        val bgArgb: Int,
        val pdfPageIndex: Int? = null,
        val objects: List<com.nanote.domain.model.PageObject> = emptyList()
    )

    @Serializable
    data class ArchiveNotebook(
        val id: String,
        val folderId: String?,
        val title: String,
        val coverArgb: Int,
        val pinned: Boolean,
        val pinOrder: Int,
        val pdfAsset: String? = null,
        val createdAt: Long,
        val updatedAt: Long,
        val pages: List<ArchivePage>
    )

    @Serializable
    data class ArchiveTag(val id: String, val name: String, val notebookId: String)

    @Serializable
    data class ArchiveManifest(
        val schema: Int = 1,
        val kind: String = "library",
        val app: String = "Nanote",
        val exportedAt: Long = System.currentTimeMillis(),
        val folders: List<ArchiveFolder> = emptyList(),
        val notebooks: List<ArchiveNotebook> = emptyList(),
        val tags: List<ArchiveTag> = emptyList()
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** Streams the manifest + the whole notebooks data tree into [out]. */
    fun write(out: OutputStream, manifest: ArchiveManifest, notebooksRoot: File) {
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(json.encodeToString(ArchiveManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            notebooksRoot.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(notebooksRoot).path.replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry(DATA_PREFIX + rel))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    class ReadResult(
        val manifest: ArchiveManifest,
        /** relative path under notebooksRoot -> bytes */
        val files: Map<String, ByteArray>
    )

    fun read(input: InputStream): ReadResult {
        val files = HashMap<String, ByteArray>()
        var manifestJson: String? = null

        ZipInputStream(input.buffered()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == MANIFEST) {
                    manifestJson = String(zip.readBytes(), Charsets.UTF_8)
                } else if (name.startsWith(DATA_PREFIX)) {
                    files[name.removePrefix(DATA_PREFIX)] = zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }

        val manifest = json.decodeFromString(
            ArchiveManifest.serializer(),
            requireNotNull(manifestJson) { "Backup has no manifest.json" }
        )
        return ReadResult(manifest, files)
    }
}
