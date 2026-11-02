package com.pixli.imageresizer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Photo permissions per Android version:
 *  - 34+ : READ_MEDIA_IMAGES, or READ_MEDIA_VISUAL_USER_SELECTED when the user picks "Select photos" (partial access)
 *  - 33  : READ_MEDIA_IMAGES
 *  - 29-32: READ_EXTERNAL_STORAGE
 * The grid is optional: the system photo picker and the file picker need no permission at all.
 */
private fun galleryPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 ->
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@Composable
fun SelectScreen(vm: MainViewModel, mode: Mode) {
    val ctx = LocalContext.current
    val permissions = remember { galleryPermissions() }
    fun hasAccess() = permissions.any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    var granted by remember { mutableStateOf(hasAccess()) }
    var gallery by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var camUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasAccess() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.addSelected(it) }
    val docs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addSelected(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) camUri?.let { vm.addSelected(listOf(it)) }
    }

    /** Launching can throw when the device has no app for the request (no camera app, no file picker...). */
    fun launchSafely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: ActivityNotFoundException) {
            Log.w("PixliSelect", "No activity for $what", e)
            vm.reportError("No app found to $what")
        } catch (e: SecurityException) {
            Log.w("PixliSelect", "Not allowed to $what", e)
            vm.reportError("Not allowed to $what")
        }
    }

    LaunchedEffect(granted) {
        if (granted) gallery = withContext(Dispatchers.IO) { ImageProcessor.loadGallery(ctx) }
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Bar("Select Image") { vm.pop() }
        TabRow(selectedTabIndex = tab, containerColor = Color.White) {
            listOf("Gallery", "Camera", "Files").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Bg)) {
            when (tab) {
                0 -> if (!granted) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("Allow access to show your photos here, or use the system photo picker (no permission needed).", textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { launchSafely("request photo access") { permLauncher.launch(permissions) } }) { Text("Allow access") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            launchSafely("pick photos") {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        }) { Text("Use photo picker instead") }
                    }
                } else if (gallery.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("No photos found. You can also use the photo picker.", textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = {
                            launchSafely("pick photos") {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        }) { Text("Open photo picker") }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(gallery, key = { it.toString() }) { uri ->
                            val checked = uri in vm.selected
                            Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).clickable { vm.toggle(uri) }) {
                                AsyncImage(
                                    model = uri, contentDescription = null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().background(Line)
                                )
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                                        .background(if (checked) Blue else Color.Transparent)
                                        .border(1.5.dp, Color.White, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (checked) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }

                1 -> CenterAction(Icons.Filled.CameraAlt, "Take a new photo", "Open camera") {
                    launchSafely("take a photo") {
                        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
                        val f = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
                        val u = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                        camUri = u
                        camera.launch(u)
                    }
                }

                else -> CenterAction(Icons.Filled.Description, "Pick images from Files or other apps", "Browse files") {
                    launchSafely("browse files") { docs.launch(arrayOf("image/*")) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(12.dp)).background(Blue)
                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${vm.selected.size} selected", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { vm.confirmSelection(mode) },
                enabled = vm.selected.isNotEmpty() && !vm.busy,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = 0.5f))
            ) {
                Text("Next", fontWeight = FontWeight.SemiBold)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }
    }
}

@Composable
private fun CenterAction(icon: ImageVector, text: String, button: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = Blue, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text(text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onClick) { Text(button) }
    }
}
