package com.nanote.app.core.data.format

import com.nanote.app.core.model.CURRENT_FORMAT_VERSION
import com.nanote.app.core.model.DocumentContent
import com.nanote.app.core.model.DocumentModel
import com.nanote.app.util.AppLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

/**
 * On-disk serialization of a document: a single JSON file ("doc.nanote").
 *
 * Writes are atomic (temp file + rename) so a crash can never truncate a
 * previously good file. Reads first try a strict parse and, when that fails,
 * fall back to a lenient per-field salvage that drops only the broken arrays
 * instead of the whole document.
 */
object DocumentFormat {

    private const val TAG = "DocumentFormat"

    private val writer = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val strictReader = Json { ignoreUnknownKeys = true }
    private val salvageReader = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Atomically persists [content] to [file].
     *
     * @return false (and logs) when the write fails; the previous file, if
     *         any, is left untouched in that case.
     */
    suspend fun save(file: File, content: DocumentContent): Boolean = withContext(Dispatchers.IO) {
        try {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            val tmp = File(parent, file.name + ".tmp")
            tmp.outputStream().use { out ->
                out.write(
                    writer
                        .encodeToString(DocumentContent.serializer(), content)
                        .toByteArray(Charsets.UTF_8)
                )
                out.flush()
            }
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                // Rare cross-filesystem rename failure: fall back to a copy.
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            true
        } catch (t: Throwable) {
            AppLog.e(TAG, "save failed for ${file.path}", t)
            false
        }
    }

    /**
     * Loads [file] back into a [DocumentModel].
     *
     * @return `(model, partiallyRecovered)` or `null` when the file does not
     *         exist or is unrecoverable. When the strict parse fails, each
     *         element array is decoded independently and broken fields are
     *         replaced with empty lists (logged) — the model is then flagged
     *         as partially recovered so the UI can warn the user.
     */
    suspend fun load(file: File): Pair<DocumentModel, Boolean>? = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            AppLog.w(TAG, "load: ${file.path} does not exist")
            return@withContext null
        }
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            AppLog.e(TAG, "load: cannot read ${file.path}", t)
            return@withContext null
        }

        // 1) Strict parse.
        try {
            val content = strictReader.decodeFromString(DocumentContent.serializer(), text)
            return@withContext DocumentModel(content) to false
        } catch (t: Throwable) {
            AppLog.e(TAG, "strict parse failed for ${file.name}, attempting salvage", t)
        }

        // 2) Lenient salvage: decode each array field separately.
        try {
            val obj = salvageReader.parseToJsonElement(text).jsonObject
            val content = DocumentContent(
                version = readVersion(obj),
                pages = salvageList(obj, "pages"),
                strokes = salvageList(obj, "strokes"),
                shapes = salvageList(obj, "shapes"),
                texts = salvageList(obj, "texts"),
                images = salvageList(obj, "images"),
                highlights = salvageList(obj, "highlights"),
                underlines = salvageList(obj, "underlines")
            )
            val model = DocumentModel(content)
            model.partiallyRecovered = true
            AppLog.w(
                TAG,
                "salvaged ${file.name}: pages=${content.pages.size} strokes=${content.strokes.size}" +
                    " shapes=${content.shapes.size} texts=${content.texts.size} images=${content.images.size}"
            )
            model to true
        } catch (t: Throwable) {
            AppLog.e(TAG, "salvage failed for ${file.path}", t)
            null
        }
    }

    private fun readVersion(obj: JsonObject): Int = try {
        obj["version"]?.jsonPrimitive?.intOrNull ?: CURRENT_FORMAT_VERSION
    } catch (t: Throwable) {
        CURRENT_FORMAT_VERSION
    }

    /**
     * Decodes one array field of the salvage object, defaulting to an empty
     * list when the field is missing, null, or broken.
     */
    private inline fun <reified T> salvageList(obj: JsonObject, field: String): List<T> = try {
        val element = obj[field]
        if (element == null || element is JsonNull) {
            emptyList()
        } else {
            salvageReader.decodeFromJsonElement(ListSerializer(serializer<T>()), element)
        }
    } catch (t: Throwable) {
        AppLog.e(TAG, "salvage: dropping field '$field' (${t.message})")
        emptyList()
    }
}
