package com.nanote.ui.library

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanote.data.repo.NotebookRepository
import com.nanote.di.ThumbsDir
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Loads the real first-page thumbnail of notebooks (filesDir/thumbs/<pageId>.webp)
 * off the main thread; results cached in memory for recomposition.
 */
@HiltViewModel
class FolderListViewModel @Inject constructor(
    private val repo: NotebookRepository,
    private val thumbLoader: ThumbLoader,
    @ThumbsDir private val thumbsDir: File
) : ViewModel() {

    private val thumbs = MutableStateFlow<Map<String, Bitmap?>>(emptyMap())
    private val inFlight = HashSet<String>()

    fun thumbnailFlow(notebookId: String): Flow<Bitmap?> {
        request(notebookId)
        return thumbs.map { it[notebookId] }
    }

    private fun request(notebookId: String) {
        if (thumbs.value.containsKey(notebookId) || !inFlight.add(notebookId)) return
        viewModelScope.launch {
            val bmp = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val pageId = repo.firstPageId(notebookId) ?: return@withContext null
                    thumbLoader.load(pageId, File(thumbsDir, "$pageId.webp"))
                } catch (e: Exception) {
                    null
                }
            }
            // mark as loaded even when null so we don't re-query forever
            thumbs.value = thumbs.value + (notebookId to bmp)
        }
    }
}
