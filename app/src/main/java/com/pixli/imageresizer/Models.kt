package com.pixli.imageresizer

import android.net.Uri
import java.io.File
import java.util.Locale

enum class OutFormat(val label: String, val ext: String, val mime: String) {
    JPG("JPG", "jpg", "image/jpeg"),
    PNG("PNG", "png", "image/png"),
    WEBP("WEBP", "webp", "image/webp")
}

enum class SizePreset(val label: String, val w: Int, val h: Int) {
    SMALL("Small", 640, 480),
    MEDIUM("Medium", 1280, 720),
    LARGE("Large", 1920, 1080);

    val hint: String get() = "(${w}×${h})"
}

enum class Mode { COMPRESS, RESIZE, BATCH }

data class ImageInfo(val uri: Uri, val name: String, val width: Int, val height: Int, val sizeBytes: Long)

data class ProcessedImage(
    val file: File,
    val displayName: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val originalBytes: Long,
    val format: OutFormat,
    val source: ImageInfo
)

data class SavedItem(val uri: Uri, val name: String, val sizeBytes: Long)

data class Params(val targetW: Int?, val targetH: Int?, val quality: Int)

sealed interface Screen {
    data object Home : Screen
    data object Tools : Screen
    data object Saved : Screen
    data object Settings : Screen
    data class Select(val mode: Mode) : Screen
    data object Compress : Screen
    data object Resize : Screen
    data object Edit : Screen
    data object Batch : Screen
}

fun Long.fmtSize(): String = when {
    this < 1024 -> "$this B"
    this < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", this / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", this / 1048576.0)
}
