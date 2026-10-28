package com.pixli.imageresizer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlin.math.roundToInt

// ───────────────────────── Compress ─────────────────────────

@Composable
fun CompressScreen(vm: MainViewModel) {
    val img = vm.images.firstOrNull() ?: return
    var quality by remember { mutableIntStateOf(vm.defaultQuality) }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Bar("Compress Image") { vm.pop() }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            ImagePreview(img.uri)
            Spacer(Modifier.height(12.dp))
            MutedText("Original Size")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(img.sizeBytes.fmtSize(), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                MutedText("${img.width} × ${img.height}")
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Line)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Compression Quality", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                MutedText("$quality%")
            }
            Slider(value = quality.toFloat(), onValueChange = { quality = it.roundToInt() }, valueRange = 10f..100f)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PresetChip("Small", "(80-90%)", quality < 50, Modifier.weight(1f)) { quality = 30 }
                PresetChip("Medium", "(50-70%)", quality in 50..79, Modifier.weight(1f)) { quality = 70 }
                PresetChip("Large", "(20-50%)", quality >= 80, Modifier.weight(1f)) { quality = 85 }
            }
            if (vm.format == OutFormat.PNG) {
                Spacer(Modifier.height(12.dp))
                MutedText("PNG is lossless, so quality has no effect. Switch to JPG or WEBP in Settings > Image Format for smaller files.")
            }
        }
        PrimaryButton("Compress", Modifier.padding(16.dp)) { vm.processSingle(null, null, quality, "compressed") }
    }
}

// ───────────────────────── Resize ─────────────────────────

private enum class SizeChoice { CUSTOM, SMALL, MEDIUM, LARGE }

@Composable
fun ResizeScreen(vm: MainViewModel) {
    val img = vm.images.firstOrNull() ?: return
    var choice by remember {
        mutableStateOf(
            when (vm.defaultSize) {
                SizePreset.SMALL -> SizeChoice.SMALL
                SizePreset.MEDIUM -> SizeChoice.MEDIUM
                SizePreset.LARGE -> SizeChoice.LARGE
            }
        )
    }
    var w by remember { mutableStateOf(img.width.toString()) }
    var h by remember { mutableStateOf(img.height.toString()) }
    var lock by remember { mutableStateOf(true) }

    fun target(): Pair<Int, Int>? = when (choice) {
        SizeChoice.CUSTOM -> {
            val a = w.toIntOrNull()
            val b = h.toIntOrNull()
            if (a != null && b != null && a in 1..12000 && b in 1..12000) a to b else null
        }
        SizeChoice.SMALL -> ImageProcessor.fitDims(img, SizePreset.SMALL)
        SizeChoice.MEDIUM -> ImageProcessor.fitDims(img, SizePreset.MEDIUM)
        SizeChoice.LARGE -> ImageProcessor.fitDims(img, SizePreset.LARGE)
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Bar("Resize Image") { vm.pop() }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            ImagePreview(img.uri, Modifier.height(180.dp))
            Spacer(Modifier.height(12.dp))
            MutedText("Current Size")
            Row {
                Text("${img.width} × ${img.height}", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                MutedText("(${img.sizeBytes.fmtSize()})")
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Line)
            Text("Choose Size", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))

            Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Line), color = Color.White) {
                Column(Modifier.padding(12.dp)) {
                    ChoiceRow("Custom Size", "", choice == SizeChoice.CUSTOM) { choice = SizeChoice.CUSTOM }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                        OutlinedTextField(
                            value = w,
                            onValueChange = { v ->
                                val d = v.filter(Char::isDigit).take(5)
                                w = d
                                choice = SizeChoice.CUSTOM
                                if (lock) d.toIntOrNull()?.let {
                                    h = (it * img.height.toDouble() / img.width).roundToInt().coerceAtLeast(1).toString()
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        Text("  ×  ")
                        OutlinedTextField(
                            value = h,
                            onValueChange = { v ->
                                val d = v.filter(Char::isDigit).take(5)
                                h = d
                                choice = SizeChoice.CUSTOM
                                if (lock) d.toIntOrNull()?.let {
                                    w = (it * img.width.toDouble() / img.height).roundToInt().coerceAtLeast(1).toString()
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { lock = !lock }) {
                            Icon(Icons.Filled.Link, "Keep aspect ratio", tint = if (lock) Blue else Muted)
                        }
                    }
                    ChoiceRow("Small", SizePreset.SMALL.hint, choice == SizeChoice.SMALL) { choice = SizeChoice.SMALL }
                    ChoiceRow("Medium", SizePreset.MEDIUM.hint, choice == SizeChoice.MEDIUM) { choice = SizeChoice.MEDIUM }
                    ChoiceRow("Large", SizePreset.LARGE.hint, choice == SizeChoice.LARGE) { choice = SizeChoice.LARGE }
                }
            }
            target()?.let {
                Spacer(Modifier.height(10.dp))
                MutedText("Result: ${it.first} × ${it.second} px")
            }
        }
        PrimaryButton("Resize", Modifier.padding(16.dp), enabled = target() != null) {
            target()?.let { vm.processSingle(it.first, it.second, vm.defaultQuality, "resized") }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, fontSize = 14.sp)
        if (hint.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            MutedText(hint)
        }
    }
}

// ───────────────────────── Edit & Save ─────────────────────────

@Composable
fun EditScreen(vm: MainViewModel) {
    val r = vm.result ?: return
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(r.format.mime)) { uri ->
        if (uri != null) vm.exportTo(uri)
    }
    val smaller = r.sizeBytes <= r.originalBytes

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Bar("Edit & Save") { vm.pop() }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Box {
                ImagePreview(r.file, Modifier.height(240.dp))
                Surface(
                    onClick = { vm.pop() },
                    color = Color(0x99000000),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Edit, null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Edit", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("File Details", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            DetailRow("Size") {
                Text(r.sizeBytes.fmtSize(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                if (r.originalBytes > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (smaller) "(Reduced from ${r.originalBytes.fmtSize()})" else "(Larger than the ${r.originalBytes.fmtSize()} original)",
                        fontSize = 12.sp,
                        color = if (smaller) Color(0xFF1B8A4B) else Color(0xFFC0392B)
                    )
                }
            }
            DetailRow("Dimensions") { Text("${r.width} × ${r.height}", fontSize = 13.sp) }
            DetailRow("Format") { Text(r.format.label, fontSize = 13.sp) }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(Icons.Filled.Share, "Share", Modifier.weight(1f)) { shareFile(ctx, r.file, r.format.mime) }
                ActionButton(Icons.Filled.Save, "Save as…", Modifier.weight(1f)) { saveAs.launch(r.displayName) }
                Box(Modifier.weight(1f)) {
                    ActionButton(Icons.Filled.MoreHoriz, "More", Modifier.fillMaxWidth()) { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        OutFormat.entries.filter { it != r.format }.forEach { f ->
                            DropdownMenuItem(text = { Text("Convert to ${f.label}") }, onClick = { menu = false; vm.changeFormat(f) })
                        }
                    }
                }
            }
        }
        PrimaryButton("Save to Gallery", Modifier.padding(16.dp)) { vm.saveCurrent() }
    }
}

@Composable
private fun DetailRow(label: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        MutedText(label, Modifier.width(96.dp))
        value()
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = RoundedCornerShape(12.dp), modifier = modifier.height(64.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            Text(label, fontSize = 11.sp)
        }
    }
}

// ───────────────────────── Batch ─────────────────────────

@Composable
fun BatchScreen(vm: MainViewModel) {
    var quality by remember { mutableIntStateOf(vm.defaultQuality) }
    var preset by remember { mutableStateOf(vm.defaultSize) }
    val resize = vm.batchTab == Mode.RESIZE
    val hasResults = vm.batchResults.isNotEmpty()

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Bar("Batch Process") { vm.pop() }
        TabRow(selectedTabIndex = if (resize) 1 else 0, containerColor = Color.White) {
            Tab(selected = !resize, onClick = { vm.updateBatchTab(Mode.COMPRESS) }, text = { Text("Compress") })
            Tab(selected = resize, onClick = { vm.updateBatchTab(Mode.RESIZE) }, text = { Text("Resize") })
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${vm.images.size} images selected", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.push(Screen.Select(Mode.BATCH)) }) { Text("+ Add More") }
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(vm.images) { i, img ->
                val res = vm.batchResults[i]
                val checked = vm.batchChecked.getOrElse(i) { true }
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (i < vm.batchChecked.size) vm.batchChecked[i] = !checked
                    }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = img.uri, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Line)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(img.name, maxLines = 1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        val sizeText = when {
                            res != null -> "${img.sizeBytes.fmtSize()} → ${res.sizeBytes.fmtSize()}"
                            vm.showSize -> img.sizeBytes.fmtSize()
                            else -> "${img.width} × ${img.height}"
                        }
                        MutedText(sizeText)
                    }
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).background(if (checked) Blue else Line),
                        contentAlignment = Alignment.Center
                    ) { if (checked) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                }
                HorizontalDivider(color = Line)
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (resize) {
                Text("Resize to (fits inside, keeps aspect ratio)", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SizePreset.entries.forEach { p ->
                        PresetChip(p.label, p.hint, preset == p, Modifier.weight(1f)) { preset = p; vm.batchResults.clear() }
                    }
                }
            } else {
                Row {
                    Text("Compression Quality", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    MutedText("$quality%")
                }
                Slider(value = quality.toFloat(), onValueChange = { quality = it.roundToInt() }, valueRange = 10f..100f)
            }
            if (hasResults) {
                PrimaryButton(
                    if (vm.batchSaved) "Saved ✓" else "Save All to Gallery",
                    enabled = !vm.batchSaved
                ) { vm.saveAll() }
                OutlinedButton(
                    onClick = { vm.runBatch(resize, quality, preset) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Process Again") }
            } else {
                PrimaryButton("Start Processing") { vm.runBatch(resize, quality, preset) }
            }
        }
    }
}
