package com.photostretcher.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photostretcher.app.engine.ImageDecoder
import com.photostretcher.app.engine.ImageSaver
import com.photostretcher.app.engine.MemoryPlan
import com.photostretcher.app.engine.SaveFormat
import com.photostretcher.app.engine.SavedImage
import com.photostretcher.app.engine.StretchMath
import com.photostretcher.app.engine.StretchRenderer
import com.photostretcher.app.model.StretchOp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the export is doing right now. */
sealed interface SaveState {
    data object Idle : SaveState
    data object Working : SaveState
    data class Done(val image: SavedImage, val reduced: Boolean) : SaveState
    data class Failed(val message: String) : SaveState
}

/** Everything the editor screen needs to know. */
data class EditorState(
    val photoUri: Uri? = null,
    val loading: Boolean = false,
    val message: String? = null,
    /** The original preview. Every re-render starts from here. */
    val original: Bitmap? = null,
    /** The result of applying [ops] to [original]. This is what the editor draws. */
    val result: Bitmap? = null,
    /** Stretches that have been committed with "Apply", in order. */
    val ops: List<StretchOp> = emptyList(),
    val redo: List<StretchOp> = emptyList(),
    val save: SaveState = SaveState.Idle,
) {
    val ready: Boolean get() = result != null
    val canUndo: Boolean get() = ops.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()
}

/**
 * Holds the photo and the list of applied stretches.
 *
 * Line positions live in the editor screen (they are throw away UI state) while the stretches
 * themselves are kept here, because undo, redo and the full resolution export all need them.
 */
class PhotoViewModel(application: Application) : AndroidViewModel(application) {

    var state by mutableStateOf(EditorState())
        private set

    private val io: CoroutineDispatcher = Dispatchers.IO
    private val work: CoroutineDispatcher = Dispatchers.Default
    private var job: Job? = null

    /** Size of the untouched original, used to plan the export. */
    private var fullWidth = 0
    private var fullHeight = 0

    fun open(uri: Uri) {
        job?.cancel()
        state = EditorState(photoUri = uri, loading = true)
        job = viewModelScope.launch {
            val loaded = runCatching { withContext(io) { ImageDecoder.decodePreview(getApplication(), uri) } }
            loaded.fold(
                onSuccess = { photo ->
                    fullWidth = photo.fullWidth
                    fullHeight = photo.fullHeight
                    state = state.copy(
                        loading = false,
                        original = photo.bitmap,
                        result = photo.bitmap,
                        message = null,
                    )
                },
                onFailure = { error ->
                    state = state.copy(
                        loading = false,
                        message = error.message ?: "That photo could not be opened.",
                    )
                },
            )
        }
    }

    /** Forgets the current photo and releases its bitmaps. */
    fun close() {
        job?.cancel()
        fullWidth = 0
        fullHeight = 0
        state = EditorState()
    }

    fun clearMessage() {
        state = state.copy(message = null)
    }

    fun dismissSave() {
        state = state.copy(save = SaveState.Idle)
    }

    /** Commits one stretch and makes the result the new starting point. */
    fun apply(op: StretchOp) {
        if (op.isNoOp) return
        val updated = state.ops + op
        state = state.copy(ops = updated, redo = emptyList(), save = SaveState.Idle)
        reRender(updated)
    }

    fun undo() {
        val ops = state.ops
        if (ops.isEmpty()) return
        val last = ops.last()
        state = state.copy(ops = ops.dropLast(1), redo = state.redo + last, save = SaveState.Idle)
        reRender(state.ops)
    }

    fun redo() {
        val redo = state.redo
        if (redo.isEmpty()) return
        val updated = state.ops + redo.last()
        state = state.copy(ops = updated, redo = redo.dropLast(1), save = SaveState.Idle)
        reRender(updated)
    }

    /** Throws every committed stretch away and starts again from the original photo. */
    fun restart() {
        state = state.copy(ops = emptyList(), redo = emptyList(), save = SaveState.Idle)
        reRender(emptyList())
    }

    private fun reRender(ops: List<StretchOp>) {
        val original = state.original ?: return
        job?.cancel()
        job = viewModelScope.launch {
            val rendered = withContext(work) { StretchRenderer.render(original, ops) }
            // No recycling: Compose may still be holding the previous bitmap in a frame that
            // has not been drawn yet, and the garbage collector frees bitmap memory anyway.
            state = state.copy(result = rendered)
        }
    }

    /**
     * Renders [ops] at the original resolution and writes the result to the gallery.
     *
     * The work happens on a background thread and is repeated with a smaller result if the
     * device runs out of memory, so a very large stretch cannot crash the app.
     */
    fun save(ops: List<StretchOp>, format: SaveFormat, smoothEdges: Boolean) {
        val uri = state.photoUri ?: return
        val useful = ops.filter { !it.isNoOp }
        if (useful.isEmpty()) return
        job?.cancel()
        state = state.copy(save = SaveState.Working)
        job = viewModelScope.launch {
            var attempt = 1f
            while (true) {
                val outcome = runCatching { withContext(io) { export(uri, useful, format, smoothEdges, attempt) } }
                val error = outcome.exceptionOrNull()
                if (error == null) {
                    val result = outcome.getOrThrow()
                    state = state.copy(save = SaveState.Done(result, reduced = attempt < 1f))
                    return@launch
                }
                if (error !is OutOfMemoryError || attempt <= 0.13f) {
                    state = state.copy(save = SaveState.Failed(error.message ?: "The picture could not be saved."))
                    return@launch
                }
                // Not enough memory: free everything we can and try again, smaller.
                attempt /= 2f
                System.gc()
            }
        }
    }

    private fun export(
        uri: Uri,
        ops: List<StretchOp>,
        format: SaveFormat,
        smoothEdges: Boolean,
        attempt: Float,
    ): SavedImage {
        val context = getApplication<Application>()
        val (baseWidth, baseHeight) = if (fullWidth > 0 && fullHeight > 0) {
            fullWidth to fullHeight
        } else {
            ImageDecoder.readFullSize(context, uri)
        }
        val (outWidth, outHeight) = StretchMath.outputSize(baseWidth, baseHeight, ops)
        val plannedScale = MemoryPlan.scaleFor(outWidth, outHeight) * attempt
        val sourceEdge = MemoryPlan.sourceLongEdge(baseWidth, baseHeight, ops, plannedScale)

        val source = ImageDecoder.decodeForExport(context, uri, sourceEdge)
        val rendered = try {
            val layoutOut = StretchMath.outputSize(source.width, source.height, ops)
            val scale = MemoryPlan.scaleFor(layoutOut.first, layoutOut.second) * attempt
            StretchRenderer.render(source, ops, smoothEdges, scale)
        } finally {
            source.recycle()
        }
        return try {
            ImageSaver.save(context, rendered, format)
        } finally {
            rendered.recycle()
        }
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }
}
