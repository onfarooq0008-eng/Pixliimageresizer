package com.pixli.imageresizer

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication<Application>()
    private val sp = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

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

    fun setFormat(v: OutFormat) { format = v; sp.edit().putString("format", v.name).apply() }
    fun setDefaultQuality(v: Int) { defaultQuality = v; sp.edit().putInt("quality", v).apply() }
    fun setDefaultSize(v: SizePreset) { defaultSize = v; sp.edit().putString("size", v.name).apply() }
    fun setKeepDate(v: Boolean) { keepDate = v; sp.edit().putBoolean("keepDate", v).apply() }
    fun setShowSize(v: Boolean) { showSize = v; sp.edit().putBoolean("showSize", v).apply() }

    // ---- shared UI state ----
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var progress by mutableStateOf("")

    val selected = mutableStateListOf<Uri>()
    var images by mutableStateOf<List<ImageInfo>>(emptyList()); private set

    var result by mutableStateOf<ProcessedImage?>(null); private set
    private var lastParams = Params(null, null, 70)
    private var lastTag = "compressed"

    var batchTab by mutableStateOf(Mode.COMPRESS); private set
    val batchChecked = mutableStateListOf<Boolean>()
    val batchResults = mutableStateMapOf<Int, ProcessedImage>()
    var batchSaved by mutableStateOf(false); private set

    var saved by mutableStateOf<List<SavedItem>>(emptyList()); private set

    /** Incremented after every successful save; the UI uses it to occasionally show an interstitial ad. */
    var saveEvents by mutableIntStateOf(0); private set

    init {
        File(ctx.cacheDir, "out").deleteRecursively()
        File(ctx.cacheDir, "camera").deleteRecursively()
    }

    // ---- selection ----
    fun startFlow(mode: Mode) { selected.clear(); push(Screen.Select(mode)) }
    fun toggle(uri: Uri) { if (!selected.remove(uri)) selected.add(uri) }
    fun addSelected(uris: List<Uri>) { uris.forEach { if (it !in selected) selected.add(it) } }

    fun confirmSelection(mode: Mode) {
        if (selected.isEmpty()) return
        val uris = selected.toList()
        viewModelScope.launch {
            busy = true
            val infos = withContext(Dispatchers.IO) {
                uris.mapNotNull { runCatching { ImageProcessor.queryInfo(ctx, it) }.getOrNull() }
            }
            busy = false
            if (infos.isEmpty()) { message = "Couldn't open the selected images"; return@launch }
            if (infos.size < uris.size) message = "Some images couldn't be opened"
            images = infos
            resetBatch()
            when {
                stack.any { it is Screen.Batch } -> while (stack.last() !is Screen.Batch) pop()
                infos.size > 1 || mode == Mode.BATCH -> {
                    batchTab = if (mode == Mode.RESIZE) Mode.RESIZE else Mode.COMPRESS
                    push(Screen.Batch)
                }
                mode == Mode.RESIZE -> push(Screen.Resize)
                else -> push(Screen.Compress)
            }
        }
    }

    // ---- single image ----
    fun processSingle(tw: Int?, th: Int?, quality: Int, tag: String, fmt: OutFormat = format) {
        val src = images.firstOrNull() ?: return
        viewModelScope.launch {
            busy = true
            val r = runCatching {
                withContext(Dispatchers.Default) { ImageProcessor.process(ctx, src, quality, fmt, tw, th, tag) }
            }
            busy = false
            r.onSuccess {
                result?.file?.parentFile?.deleteRecursively()
                result = it
                lastParams = Params(tw, th, quality)
                lastTag = tag
                if (current !is Screen.Edit) push(Screen.Edit)
            }.onFailure { message = "Couldn't process image: ${it.message}" }
        }
    }

    fun changeFormat(f: OutFormat) {
        val p = lastParams
        processSingle(p.targetW, p.targetH, p.quality, lastTag, f)
    }

    fun saveCurrent() {
        val r = result ?: return
        viewModelScope.launch {
            busy = true
            val uri = withContext(Dispatchers.IO) { ImageProcessor.saveToGallery(ctx, r, keepDate) }
            busy = false
            message = if (uri != null) "Saved to Pictures/${ImageProcessor.FOLDER}" else "Couldn't save the image"
            if (uri != null) saveEvents++
        }
    }

    fun exportTo(uri: Uri) {
        val r = result ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { out -> r.file.inputStream().use { it.copyTo(out) } }
                }.isSuccess
            }
            message = if (ok) "Saved" else "Couldn't save the file"
        }
    }

    // ---- batch ----
    private fun resetBatch() {
        batchChecked.clear()
        repeat(images.size) { batchChecked.add(true) }
        batchResults.clear()
        batchSaved = false
    }

    fun setBatchTab(m: Mode) { batchTab = m; batchResults.clear(); batchSaved = false }

    fun runBatch(resize: Boolean, quality: Int, preset: SizePreset) {
        val idx = images.indices.filter { batchChecked.getOrElse(it) { false } }
        if (idx.isEmpty()) { message = "Select at least one image"; return }
        val fmt = format
        val q = defaultQuality
        viewModelScope.launch {
            busy = true
            batchResults.clear()
            batchSaved = false
            var failed = 0
            idx.forEachIndexed { n, i ->
                progress = "Processing ${n + 1} of ${idx.size}…"
                val src = images[i]
                val r = runCatching {
                    withContext(Dispatchers.Default) {
                        if (resize) {
                            val (w, h) = ImageProcessor.fitDims(src, preset)
                            ImageProcessor.process(ctx, src, q, fmt, w, h, "resized")
                        } else {
                            ImageProcessor.process(ctx, src, quality, fmt, null, null, "compressed")
                        }
                    }
                }
                r.onSuccess { batchResults[i] = it }.onFailure { failed++ }
            }
            busy = false
            progress = ""
            if (failed > 0) message = "$failed image(s) failed"
        }
    }

    fun saveAll() {
        val items = batchResults.values.toList()
        viewModelScope.launch {
            busy = true
            progress = "Saving…"
            val ok = withContext(Dispatchers.IO) { items.count { ImageProcessor.saveToGallery(ctx, it, keepDate) != null } }
            busy = false
            progress = ""
            batchSaved = ok > 0
            message = "Saved $ok image(s) to Pictures/${ImageProcessor.FOLDER}"
            if (ok > 0) saveEvents++
        }
    }

    // ---- saved files ----
    fun refreshSaved() {
        viewModelScope.launch { saved = withContext(Dispatchers.IO) { ImageProcessor.loadSaved(ctx) } }
    }

    fun deleteSaved(item: SavedItem) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.delete(item.uri, null, null) > 0 }.getOrDefault(false) }
            if (ok) saved = saved.filter { it.uri != item.uri } else message = "Couldn't delete that file"
        }
    }
}
