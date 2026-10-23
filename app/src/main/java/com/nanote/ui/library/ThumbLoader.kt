package com.nanote.ui.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory LruCache of decoded page thumbnails (webp on disk under files/thumbs).
 * Decode happens on the calling dispatcher (call from IO).
 */
@Singleton
class ThumbLoader @Inject constructor() {

    private val cache = object : LruCache<String, Bitmap>(48) {}

    fun load(pageId: String, file: File): Bitmap? {
        if (!file.exists()) return null
        cache.get(pageId)?.let { return it }
        return try {
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            cache.put(pageId, bmp)
            bmp
        } catch (e: Exception) {
            null
        }
    }

    fun invalidate(pageId: String) {
        cache.remove(pageId)
    }
}
