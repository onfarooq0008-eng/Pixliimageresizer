package com.pixli.imageresizer

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private companion object { const val TAG = "PixliVM" }

    private val ctx: Context get() = getApplication<Application>()
    private val sp = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ---- ads: owned here so they survive rotation and never hold an Activity ----
    val interstitials = InterstitialManager(app)
    val nativeHome = NativeAdSlot(app)
    val nativeSaved = NativeAdSlot(app)

    // ---- navigation (tiny back stack) ----
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun goRoot(s: Screen) { stack.clear(); stack.add(s) }

    // ---- settings ----
    var format by mutableStateOf(
        OutFormat.entries.firstOrNull { it.name == sp.getString("format", "JPG") } ?: OutFormat.JPG
    ); private set
    var defaultQuality by mutableIntStateOf(sp.getInt("quality", 70)); private set
    var defaultSize by mutableStateOf(
        SizePreset.entries.firstOrNull { it.name == sp.getString("size", "MEDIUM") } ?: SizePreset.MEDIUM
    ); private set
    var keepDate by mutableStateOf(sp.getBoolean("keepDate", true)); private set
    var showSize by mutableStateOf(sp.getBoolean("showSize", true)); private set

    fun updateFormat(v: OutFormat) { format = v; sp.edit().putString("format", v.name).apply() }
    fun updateDefaultQuality(v: Int) { defaultQuality = v; sp.edit().putInt("quality", v).apply() }
    fun updateDefaultSize(v: SizePreset) { defaultSize = v; sp.edit().putString("size", v.name).apply() }
    fun updateKeepDate(v: Boolean) { keepDate = v; sp.edit().putBoolean("keepDate", v).apply() }
    fun updateShowSize(v: Boolean) { showSize = v; sp.edit().putBoolean("showSize", v).apply() }

    // ---- shared UI state ----
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set
    var progress by mutableStateOf(""); private set
    private var progressDone by mutableIntStateOf(0)
    private var progressTotal by mutableIntStateOf(0)
    val progressFraction: Float? get() = if (progressTotal > 0) progressDone.toFloat() / progressTotal else null

    val selected = mutableStateListOf<Uri>()
    /** The image(s) being worked on: one for Compress/Resize, any number for Batch. */
    val images = mutableStateListOf<ImageInfo>()

    var result by mutableStateOf<ProcessedImage?>(null); private set
    private var resultSaved = false
    private var lastParams = Params(null, null, 70)
    private var lastTag = "compressed"

    // ---- batch state (keyed by source Uri so removing/adding images never shifts results) ----
    var batchTab by mutableStateOf(Mode.COMPRESS); private set
    val batchChecked = mutableStateMapOf<Uri, Boolean>()
    val batchResults = mutableStateMapOf<Uri, ProcessedImage>()
    val batchFailures = mutableStateMapOf<Uri, String>()
    private val batchSavedKeys = mutableStateMapOf<Uri, Boolean>()
    var batchRunning by mutableStateOf(false); private set
    private var batchJob: Job? = null

    val batchAllSaved: Boolean get() = batchResults.isNotEmpty() && batchResults.keys.all { batchSavedKeys[it] == true }

    var saved by mutableStateOf<List<SavedItem>>(emptyList()); private set

    /** Incremented after every successful save; the UI uses it to occasionally show an interstitial ad. */
    var saveEvents by mutableIntStateOf(0); private set

    init {
        deleteCache()
    }

    override fun onCleared() {
        batchJob?.cancel()
        interstitials.destroy()
        nativeHome.discard()
        nativeSaved.discard()
        deleteCache()
        super.onCleared()
    }

    private fun deleteCache() {
        File(ctx.cacheDir, "out").deleteRecursively()
        File(ctx.cacheDir, "camera").deleteRecursively()
    }

    private fun discard(r: ProcessedImage?) {
        r?.file?.parentFile?.deleteRecursively()
    }

    // ───────────── selection ─────────────

    fun startFlow(mode: Mode) { selected.clear(); push(Screen.Select(mode)) }
    fun startAddMore() { selected.clear(); push(Screen.Select(Mode.BATCH)) }
    fun toggle(uri: Uri) { if (!selected.remove(uri)) selected.add(uri) }
    fun addSelected(uris: List<Uri>) { uris.forEach { if (it !in selected) selected.add(it) } }

    fun confirmSelection(mode: Mode) {
        if (busy || selected.isEmpty()) return
        val addingToBatch = stack.any { it is Screen.Batch }
        val known = images.map { it.uri }.toSet()
        val requested = selected.toList().distinct()
        val fresh = if (addingToBatch) requested.filter { it !in known } else requested
        val duplicates = requested.size - fresh.size
        if (fresh.isEmpty()) {
            message = "Those images are already in the batch"
            if (addingToBatch) while (stack.last() !is Screen.Batch) pop()
            return
        }
        viewModelScope.launch {
            busy = true
            progress = ""
            val infos = ArrayList<ImageInfo>(fresh.size)
            var unreadable = 0
            try {
                withContext(Dispatchers.IO) {
                    for (uri in fresh) {
                        try {
                            infos += ImageProcessor.queryInfo(ctx, uri)
                        } catch (e: ImageProcessingException) {
                            Log.w(TAG, "Skipping unreadable image $uri: ${e.message}")
                            unreadable++
                        } catch (e: SecurityException) {
                            Log.w(TAG, "No permission for $uri", e)
                            unreadable++
                        } catch (e: java.io.IOException) {
                            Log.w(TAG, "Cannot read $uri", e)
                            unreadable++
                        }
                    }
                }
            } finally {
                busy = false
            }
            if (infos.isEmpty()) { message = "Couldn't open the selected images"; return@launch }
            val notes = buildList {
                if (unreadable > 0) add("$unreadable couldn't be opened")
                if (duplicates > 0) add("$duplicates already added")
            }
            if (notes.isNotEmpty()) message = notes.joinToString(" · ")

            if (addingToBatch) {
                images.addAll(infos)
                infos.forEach { batchChecked[it.uri] = true }
                while (stack.last() !is Screen.Batch) pop()
            } else {
                clearBatchOutputs()
                images.clear()
                images.addAll(infos)
                batchChecked.clear()
                infos.forEach { batchChecked[it.uri] = true }
                if (infos.size > 1 || mode == Mode.BATCH) {
                    batchTab = if (mode == Mode.RESIZE) Mode.RESIZE else Mode.COMPRESS
                    push(Screen.Batch)
                } else if (mode == Mode.RESIZE) {
                    push(Screen.Resize)
                } else {
                    push(Screen.Compress)
                }
            }
        }
    }

    // ───────────── single image ─────────────

    fun processSingle(tw: Int?, th: Int?, quality: Int, tag: String, fmt: OutFormat = format) {
        val src = images.firstOrNull() ?: return
        if (busy) return
        viewModelScope.launch {
            busy = true
            progress = ""
            try {
                val r = withContext(Dispatchers.Default) { ImageProcessor.process(ctx, src, quality, fmt, tw, th, tag) }
                discard(result)
                result = r
                resultSaved = false
                lastParams = Params(tw, th, quality)
                lastTag = tag
                if (r.downscaledForMemory) {
                    message = "Low memory: the image was processed at a smaller size (${r.width} × ${r.height})."
                }
                if (current !is Screen.Edit) push(Screen.Edit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImageProcessingException) {
                message = e.message ?: "Couldn't process the image"
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected processing error", e)
                message = "Couldn't process the image: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun changeFormat(f: OutFormat) {
        val p = lastParams
        processSingle(p.targetW, p.targetH, p.quality, lastTag, f)
    }

    fun saveCurrent() {
        val r = result ?: return
        if (busy) return
        if (resultSaved) { message = "Already saved to Pictures/${ImageProcessor.FOLDER}"; return }
        viewModelScope.launch {
            busy = true
            progress = ""
            try {
                when (val res = withContext(Dispatchers.IO) { ImageProcessor.saveToGallery(ctx, r, keepDate) }) {
                    is SaveResult.Ok -> {
                        resultSaved = true
                        message = "Saved to Pictures/${ImageProcessor.FOLDER}"
                        saveEvents++
                    }
                    is SaveResult.Failed -> message = "Couldn't save: ${res.reason}"
                }
            } finally {
                busy = false
            }
        }
    }

    fun exportTo(uri: Uri) {
        val r = result ?: return
        viewModelScope.launch {
            message = when (val res = withContext(Dispatchers.IO) { ImageProcessor.exportTo(ctx, r, uri) }) {
                is SaveResult.Ok -> "Saved"
                is SaveResult.Failed -> "Couldn't save: ${res.reason}"
            }
        }
    }

    // ───────────── batch ─────────────

    private fun clearBatchOutputs() {
        batchResults.values.forEach { discard(it) }
        batchResults.clear()
        batchFailures.clear()
        batchSavedKeys.clear()
    }

    fun updateBatchTab(m: Mode) {
        if (batchRunning || busy) return
        batchTab = m
        clearBatchOutputs()
    }

    fun removeImage(uri: Uri) {
        if (batchRunning || busy) return
        images.removeAll { it.uri == uri }
        batchChecked.remove(uri)
        batchFailures.remove(uri)
        batchSavedKeys.remove(uri)
        discard(batchResults.remove(uri))
    }

    fun toggleChecked(uri: Uri) {
        if (batchRunning) return
        batchChecked[uri] = !(batchChecked[uri] ?: true)
    }

    fun cancelBatch() { batchJob?.cancel() }

    fun runBatch(resize: Boolean, quality: Int, preset: SizePreset) {
        if (batchRunning || busy) return
        val targets = images.filter { batchChecked[it.uri] != false }
        if (targets.isEmpty()) { message = "Select at least one image"; return }
        val fmt = format
        val resizeQuality = defaultQuality

        targets.forEach { discard(batchResults.remove(it.uri)); batchFailures.remove(it.uri); batchSavedKeys.remove(it.uri) }
        batchRunning = true
        progressDone = 0
        progressTotal = targets.size
        progress = "Processing 1 of ${targets.size}…"

        batchJob = viewModelScope.launch {
            var ok = 0
            var failed = 0
            var cancelled = false
            try {
                for (src in targets) {
                    ensureActive()
                    progress = "Processing ${progressDone + 1} of ${targets.size}…"
                    try {
                        val r = withContext(Dispatchers.Default) {
                            if (resize) {
                                val (w, h) = ImageProcessor.fitDims(src, preset)
                                ImageProcessor.process(ctx, src, resizeQuality, fmt, w, h, "resized")
                            } else {
                                ImageProcessor.process(ctx, src, quality, fmt, null, null, "compressed")
                            }
                        }
                        batchResults[src.uri] = r
                        ok++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Batch item failed: ${src.name}", e)
                        batchFailures[src.uri] = e.message ?: "Failed"
                        failed++
                    }
                    progressDone++
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } finally {
                batchRunning = false
                progress = ""
                progressDone = 0
                progressTotal = 0
                message = when {
                    cancelled -> "Cancelled · $ok processed" + if (failed > 0) ", $failed failed" else ""
                    failed > 0 -> "$ok processed · $failed failed"
                    else -> "$ok processed"
                }
            }
        }
    }

    fun saveAll() {
        if (batchRunning || busy) return
        val pending = batchResults.entries.filter { batchSavedKeys[it.key] != true }.map { it.key to it.value }
        if (pending.isEmpty()) { message = "Everything is already saved"; return }
        viewModelScope.launch {
            busy = true
            progressDone = 0
            progressTotal = pending.size
            var ok = 0
            var failed = 0
            try {
                for ((uri, r) in pending) {
                    progress = "Saving ${progressDone + 1} of ${pending.size}…"
                    val res = withContext(Dispatchers.IO) { ImageProcessor.saveToGallery(ctx, r, keepDate) }
                    if (res is SaveResult.Ok) {
                        batchSavedKeys[uri] = true
                        ok++
                    } else {
                        Log.w(TAG, "Batch save failed for ${r.displayName}: $res")
                        failed++
                    }
                    progressDone++
                }
            } finally {
                busy = false
                progress = ""
                progressDone = 0
                progressTotal = 0
            }
            message = "Saved $ok to Pictures/${ImageProcessor.FOLDER}" + if (failed > 0) " · $failed failed" else ""
            if (ok > 0) saveEvents++
        }
    }

    // ───────────── saved files ─────────────

    fun refreshSaved() {
        viewModelScope.launch { saved = withContext(Dispatchers.IO) { ImageProcessor.loadSaved(ctx) } }
    }

    fun deleteSaved(item: SavedItem) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.delete(item.uri, null, null) > 0
                } catch (e: SecurityException) {
                    // Android only lets an app delete media it created itself (not after a reinstall).
                    Log.w(TAG, "Delete not permitted for ${item.uri}", e)
                    false
                }
            }
            if (ok) saved = saved.filter { it.uri != item.uri } else message = "Couldn't delete that file"
        }
    }

    fun reportError(text: String) { message = text }
}
