package com.nanote.data.files

import com.nanote.domain.model.PageObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The `.nanote` notebook archive: a ZIP of
 *   manifest.json            (schema, notebook + page metadata, objects)
 *   pages/<pageId>.ink       stroke data
 *   pages/<pageId>.json      object sidecar
 *   assets/<file>            imported PDFs / images
 *
 * Pure JVM (java.util.zip) so it is unit-testable. Schema per spec 7.
 */
object NotebookArchive {

    const val EXT = "nanote"
    const val MANIFEST = "manifest.json"

    @Serializable
    data class ArchiveNotebook(
        val id: String,
        val title: String,
        val coverArgb: Int,
        val createdAt: Long,
        val updatedAt: Long,
        val pdfAsset: String? = null
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
        val objects: List<PageObject> = emptyList()
    )

    @Serializable
    data class ArchiveManifest(
        val schema: Int = 1,
        val kind: String = "notebook",
        val app: String = "Nanote",
        val notebook: ArchiveNotebook,
        val pages: List<ArchivePage>,
        val assets: List<String> = emptyList()
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun write(
        out: java.io.OutputStream,
        manifest: ArchiveManifest,
        pageFiles: Map<String, Pair<File?, File?>>, // pageId -> (ink, json)
        assetFiles: Map<String, File> // name in archive -> file
    ) {
        ZipOutputStream(out.buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }

            put(MANIFEST, json.encodeToString(ArchiveManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
            for ((pageId, files) in pageFiles) {
                if (files.first?.exists() == true) put("pages/$pageId.ink", files.first!!.readBytes())
                if (files.second?.exists() == true) put("pages/$pageId.json", files.second!!.readBytes())
            }
            for ((name, f) in assetFiles) {
                if (f.exists()) put("assets/$name", f.readBytes())
            }
        }
    }

    data class ReadResult(
        val manifest: ArchiveManifest,
        /** pageId -> (ink bytes, json bytes) */
        val pageFiles: Map<String, Pair<ByteArray?, ByteArray?>>,
        /** name -> bytes */
        val assets: Map<String, ByteArray>
    )

    fun read(input: java.io.InputStream): ReadResult {
        val pageFiles = HashMap<String, Pair<ByteArray?, ByteArray?>>()
        val assets = HashMap<String, ByteArray>()
        var manifestJson: String? = null

        ZipInputStream(input.buffered()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                val bytes = zip.readBytes()
                when {
                    name == MANIFEST -> manifestJson = String(bytes, Charsets.UTF_8)
                    name.startsWith("pages/") && name.endsWith(".ink") ->
                        pageFiles.merge(name.removePrefix("pages/").removeSuffix(".ink"), bytes to null) { old, new ->
                            (old.first ?: new.first) to (old.second ?: new.second)
                        }
                    name.startsWith("pages/") && name.endsWith(".json") ->
                        pageFiles.merge(name.removePrefix("pages/").removeSuffix(".json"), null to bytes) { old, new ->
                            (old.first ?: new.first) to (old.second ?: new.second)
                        }
                    name.startsWith("assets/") ->
                        assets[name.removePrefix("assets/")] = bytes
                }
                entry = zip.nextEntry
            }
        }

        val manifest = json.decodeFromString(
            ArchiveManifest.serializer(),
            requireNotNull(manifestJson) { "Archive has no manifest.json" }
        )
        return ReadResult(manifest, pageFiles, assets)
    }
}
