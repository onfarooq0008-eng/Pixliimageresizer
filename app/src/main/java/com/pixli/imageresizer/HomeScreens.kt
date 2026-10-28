package com.pixli.imageresizer

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

@Composable
fun HomeScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().background(Blue).statusBarsPadding()
                .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 28.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                "Pixli\nImage Resizer", color = Color.White, fontSize = 22.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { vm.goRoot(Screen.Settings) }) {
                Icon(Icons.Filled.Settings, "Settings", tint = Color.White)
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                onClick = { vm.startFlow(Mode.COMPRESS) },
                color = Blue,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AddPhotoAlternate, null, tint = Color.White, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Select Image", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Choose from gallery or camera", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolTile(Icons.Filled.Compress, "Compress Image", "Reduce file size", Modifier.weight(1f)) { vm.startFlow(Mode.COMPRESS) }
                ToolTile(Icons.Filled.PhotoSizeSelectLarge, "Resize Image", "Change dimensions", Modifier.weight(1f)) { vm.startFlow(Mode.RESIZE) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolTile(Icons.Filled.Collections, "Batch Process", "Compress & resize multiple images", Modifier.weight(1f)) { vm.startFlow(Mode.BATCH) }
                ToolTile(Icons.Filled.FolderOpen, "My Files", "View saved images", Modifier.weight(1f)) { vm.goRoot(Screen.Saved) }
            }
            NativeAdCard()
        }
    }
}

@Composable
fun ToolsScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tools", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
        ToolRow(Icons.Filled.Compress, "Compress Image", "Reduce file size") { vm.startFlow(Mode.COMPRESS) }
        ToolRow(Icons.Filled.PhotoSizeSelectLarge, "Resize Image", "Change dimensions") { vm.startFlow(Mode.RESIZE) }
        ToolRow(Icons.Filled.Collections, "Batch Process", "Compress & resize multiple images") { vm.startFlow(Mode.BATCH) }
    }
}

@Composable
private fun ToolRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.White, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Blue, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                MutedText(sub)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted)
        }
    }
}

@Composable
fun SavedScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.refreshSaved() }
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding()) {
        Text("Saved", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        if (vm.saved.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.FolderOpen, null, tint = Muted, modifier = Modifier.size(48.dp))
                Spacer(Modifier.width(8.dp))
                MutedText("Images you save will appear here (Pictures/${ImageProcessor.FOLDER}).")
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(vm.saved, key = { it.uri.toString() }) { item ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = item.uri, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Line)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.name, maxLines = 1, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            if (vm.showSize) MutedText(item.sizeBytes.fmtSize())
                        }
                        IconButton(onClick = {
                            val i = Intent(Intent.ACTION_SEND).apply {
                                type = "image/*"
                                putExtra(Intent.EXTRA_STREAM, item.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            ctx.startActivity(Intent.createChooser(i, null))
                        }) { Icon(Icons.Filled.Share, "Share", tint = Blue) }
                        IconButton(onClick = { vm.deleteSaved(item) }) { Icon(Icons.Filled.Delete, "Delete", tint = Muted) }
                    }
                    HorizontalDivider(color = Line)
                }
            }
        }
        NativeAdCard()
    }
}
