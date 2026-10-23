package com.nanote.data.files

import com.nanote.domain.model.PageObject
import com.nanote.ink.engine.InkFormat
import com.nanote.ink.engine.Stroke
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

@Serializable
private data class PageSidecar(
    val schema: Int = 1,
    val objects: List<PageObject> = emptyList()
)

data class LoadedPage(
    val strokes: List<Stroke>,
    val objects: List<PageObject>
)

/**
 * Stroke + object persistence on plain files:
 *
 *   filesDir/notebooks/<notebookId>/pages/<pageId>.ink    strokes (binary, deflated)
 *   filesDir/notebooks/<notebookId>/pages/<pageId>.json  text boxes, images, links
 *   filesDir/notebooks/<notebookId>/assets/<assetId>.*   imported PDFs and images
 *   filesDir/thumbs/<pageId>.webp                         300 px thumbnails
 *
 * Writes are atomic (.bak rotation); loads fall back to the .bak when the
 * main file is corrupt. All heavy work runs on Dispatchers.IO via the
 * repositories.
 */
class PageStore(private val notebooksRoot: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun notebookDir(notebookId: String): File = File(notebooksRoot, notebookId)

    /** The notebooks root dir (filesDir/notebooks). */
    fun notebooksRoot(): File = notebooksRoot
    fun pagesDir(notebookId: String): File = File(notebookDir(notebookId), "pages")
    fun assetsDir(notebookId: String): File = File(notebookDir(notebookId), "assets")
    fun pageInk(notebookId: String, pageId: String): File = File(pagesDir(notebookId), "$pageId.ink")
    fun pageJson(notebookId: String, pageId: String): File = File(pagesDir(notebookId), "$pageId.json")
    fun assetFile(notebookId: String, assetId: String, ext: String): File =
        File(assetsDir(notebookId), "$assetId.$ext")

    @Throws(IOException::class)
    fun save(notebookId: String, pageId: String, strokes: List<Stroke>, objects: List<PageObject>) {
        writeAtomic(pageInk(notebookId, pageId), InkFormat.encode(strokes))
        writeAtomic(
            pageJson(notebookId, pageId),
            json.encodeToString(PageSidecar.serializer(), PageSidecar(objects = objects)).toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * Loads strokes and objects. A corrupt main file falls back to its .bak
     * (spec: "on load, fall back to the .bak if the main file is corrupt").
     */
    @Throws(IOException::class)
    fun load(notebookId: String, pageId: String): LoadedPage {
        val ink = pageInk(notebookId, pageId)
        val strokes = loadStrokes(ink) ?: loadStrokes(File(ink.parentFile, ink.name + ".bak")) ?: emptyList()

        val sidecar = pageJson(notebookId, pageId)
        val objects = loadObjects(sidecar)
            ?: loadObjects(File(sidecar.parentFile, sidecar.name + ".bak"))
            ?: emptyList()
        return LoadedPage(strokes, objects)
    }

    private fun loadStrokes(f: File): List<Stroke>? {
        if (!f.exists() || f.length() == 0L) return null
        return try {
            InkFormat.decode(f.readBytes())
        } catch (_: Exception) {
            null // corrupt -> caller falls back to .bak
        }
    }

    private fun loadObjects(f: File): List<PageObject>? {
        if (!f.exists() || f.length() == 0L) return null
        return try {
            json.decodeFromString<PageSidecar>(f.readText(Charsets.UTF_8)).objects
        } catch (_: Exception) {
            null
        }
    }

    /** Public object-sidecar decoder (used by archive import). */
    fun decodeObjects(bytes: ByteArray): List<PageObject> = try {
        json.decodeFromString<PageSidecar>(String(bytes, Charsets.UTF_8)).objects
    } catch (_: Exception) {
        emptyList()
    }

    fun deletePage(notebookId: String, pageId: String) {
        deleteWithSiblings(pageInk(notebookId, pageId))
        deleteWithSiblings(pageJson(notebookId, pageId))
    }

    fun deleteNotebook(notebookId: String) {
        notebooksRoot.resolve(notebookId).deleteRecursively()
    }

    /** Copies the stroke + object files of a page (used by duplicate flows). */
    fun copyPage(srcNotebook: String, srcPage: String, dstNotebook: String, dstPage: String) {
        val ink = pageInk(srcNotebook, srcPage)
        val json = pageJson(srcNotebook, srcPage)
        if (ink.exists()) writeAtomic(pageInk(dstNotebook, dstPage), ink.readBytes())
        if (json.exists()) writeAtomic(pageJson(dstNotebook, dstPage), json.readBytes())
    }

    /** Stores an imported asset (image/PDF) inside the notebook; always copied in. */
    fun saveAsset(notebookId: String, assetId: String, bytes: ByteArray, ext: String): File {
        val f = assetFile(notebookId, assetId, ext)
        writeAtomic(f, bytes)
        return f
    }

    fun listAssets(notebookId: String): List<File> =
        assetsDir(notebookId).listFiles()?.toList() ?: emptyList()

    /** Lists page files present on disk for a notebook (used by restore/verify). */
    fun listPageIds(notebookId: String): List<String> {
        val dir = pagesDir(notebookId)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.extension == "ink" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }
}
