package com.nanote.core.data

import com.nanote.core.data.DocumentRepository
import com.nanote.core.model.DocumentModel
import com.nanote.core.common.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Lifecycle of the last save operation, exposed to the UI. */
sealed interface SaveState {
    object Idle : SaveState
    object Saving : SaveState
    data class Saved(val atMillis: Long) : SaveState
    data class Error(val message: String?) : SaveState
}

/**
 * Watches one [DocumentModel] and persists it through [DocumentRepository]:
 *
 *  - a debounced save fires 2 s after the last committed change,
 *  - a periodic loop saves every 30 s while the model is dirty,
 *  - [detach] launches one final save for unsaved changes; callers that must
 *    wait for it (process death, screen exit) use [flush].
 *
 * attach/detach are expected to be called from the main dispatcher; the
 * save itself is serialized with [saveMutex] so debounced, periodic and
 * final saves can never interleave.
 */
class AutosaveManager(
    private val externalScope: CoroutineScope,
    private val repo: DocumentRepository
) {
    companion object {
        private const val TAG = "AutosaveManager"
        private const val DEBOUNCE_MS = 2_000L
        private const val PERIOD_MS = 30_000L
    }

    private val saveMutex = Mutex()

    private val _state = MutableStateFlow<SaveState>(SaveState.Idle)
    val state: StateFlow<SaveState> = _state

    private var docId: String? = null
    private var model: DocumentModel? = null

    private var revisionJob: Job? = null
    private var periodicJob: Job? = null
    private var debounceJob: Job? = null

    /** Final save launched by [detach] (or a re-[attach]); awaited by [flush]. */
    private var pendingFinalSave: CompletableDeferred<Boolean>? = null

    /**
     * Starts autosave for [model]. Cancels any previous attachment, saving it
     * first (fire and forget) when it still had unsaved changes.
     */
    fun attach(docId: String, model: DocumentModel) {
        cancelJobs()
        val previousId = this.docId
        val previousModel = this.model
        if (previousId != null && previousModel != null && previousModel.dirty) {
            fireFinalSave(previousId, previousModel)
        }
        this.docId = docId
        this.model = model
        _state.value = SaveState.Idle

        revisionJob = externalScope.launch {
            // drop(1): revision 0 is the initial value, not a change.
            model.revision.drop(1).collect { scheduleDebouncedSave() }
        }
        periodicJob = externalScope.launch {
            while (isActive) {
                delay(PERIOD_MS)
                if (model.dirty) saveNow()
            }
        }
    }

    /**
     * Saves the attached document right away.
     *
     * @return true when the document is now persisted (or was already clean);
     *         false when nothing is attached or the write failed.
     */
    suspend fun saveNow(): Boolean {
        val id = docId ?: return false
        val m = model ?: return false
        return performSave(id, m)
    }

    /**
     * Ensures everything is persisted: waits for any pending final save from
     * a previous [detach], then saves the currently attached document if it
     * is still dirty.
     */
    suspend fun flush() {
        pendingFinalSave?.let { deferred ->
            try {
                deferred.await()
            } catch (t: Throwable) {
                AppLog.w(TAG, "flush: final save failed (${t.message})")
            }
        }
        val id = docId ?: return
        val m = model ?: return
        if (m.dirty) performSave(id, m)
    }

    /**
     * Stops autosave for the attached document. If it is dirty, one final
     * save is launched in [externalScope] (never blocking the caller);
     * callers that need it completed await it via [flush].
     */
    fun detach() {
        val id = docId
        val m = model
        cancelJobs()
        docId = null
        model = null
        if (id != null && m != null && m.dirty) {
            fireFinalSave(id, m)
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /** Restarts the debounce timer; the save fires DEBOUNCE_MS after the last change. */
    private fun scheduleDebouncedSave() {
        debounceJob?.cancel()
        debounceJob = externalScope.launch {
            delay(DEBOUNCE_MS)
            saveNow()
        }
    }

    private fun cancelJobs() {
        revisionJob?.cancel()
        periodicJob?.cancel()
        debounceJob?.cancel()
        revisionJob = null
        periodicJob = null
        debounceJob = null
    }

    private fun fireFinalSave(id: String, model: DocumentModel) {
        val previous = pendingFinalSave
        val deferred = CompletableDeferred<Boolean>()
        pendingFinalSave = deferred
        externalScope.launch {
            try {
                // Serialize behind an earlier in-flight final save, if any.
                previous?.let { runCatching { it.await() } }
                deferred.complete(performSave(id, model))
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            }
        }
    }

    private suspend fun performSave(id: String, model: DocumentModel): Boolean =
        saveMutex.withLock {
            if (!model.dirty) {
                true
            } else {
                _state.value = SaveState.Saving
                val ok = try {
                    repo.saveDocument(id, model)
                } catch (t: Throwable) {
                    AppLog.e(TAG, "autosave failed for $id", t)
                    false
                }
                _state.value = if (ok) {
                    SaveState.Saved(System.currentTimeMillis())
                } else {
                    SaveState.Error("Autosave failed")
                }
                if (ok) AppLog.d(TAG, "autosaved $id")
                ok
            }
        }
}
