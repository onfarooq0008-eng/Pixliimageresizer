package com.pixli.imageresizer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private enum class Dialog { NONE, FORMAT, QUALITY, SIZE, ABOUT, PRIVACY }

@Composable
fun SettingsScreen(vm: MainViewModel) {
    var dialog by remember { mutableStateOf(Dialog.NONE) }

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().verticalScroll(rememberScrollState())) {
        Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        Surface(color = Color.White, shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column {
                NavRow(Icons.Filled.Image, "Image Format", "${vm.format.label} (Default)") { dialog = Dialog.FORMAT }
                HorizontalDivider(color = Line)
                NavRow(Icons.Filled.Tune, "Compression Quality", "${vm.defaultQuality}% (Default)") { dialog = Dialog.QUALITY }
                HorizontalDivider(color = Line)
                NavRow(Icons.Filled.PhotoSizeSelectLarge, "Default Resize Size", "${vm.defaultSize.label} (${vm.defaultSize.w}×${vm.defaultSize.h})") { dialog = Dialog.SIZE }
                HorizontalDivider(color = Line)
                NavRow(Icons.Filled.FolderOpen, "Save Location", "Pictures/${ImageProcessor.FOLDER}", chevron = false) {}
                HorizontalDivider(color = Line)
                SwitchRow(Icons.Filled.CalendarMonth, "Keep Original Date", vm.keepDate) { vm.setKeepDate(it) }
                HorizontalDivider(color = Line)
                SwitchRow(Icons.Filled.Storage, "Show File Size", vm.showSize) { vm.setShowSize(it) }
                HorizontalDivider(color = Line)
                NavRow(Icons.Filled.Info, "About App", null) { dialog = Dialog.ABOUT }
                HorizontalDivider(color = Line)
                NavRow(Icons.Filled.PrivacyTip, "Privacy Policy", null) { dialog = Dialog.PRIVACY }
            }
        }
        Spacer(Modifier.padding(8.dp))
    }

    when (dialog) {
        Dialog.NONE -> {}
        Dialog.FORMAT -> AlertDialog(
            onDismissRequest = { dialog = Dialog.NONE },
            title = { Text("Image Format") },
            text = {
                Column {
                    OutFormat.entries.forEach { f ->
                        Row(Modifier.fillMaxWidth().clickable { vm.setFormat(f); dialog = Dialog.NONE }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = vm.format == f, onClick = { vm.setFormat(f); dialog = Dialog.NONE })
                            Text(f.label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = Dialog.NONE }) { Text("Close") } }
        )
        Dialog.QUALITY -> {
            var q by remember { mutableIntStateOf(vm.defaultQuality) }
            AlertDialog(
                onDismissRequest = { dialog = Dialog.NONE },
                title = { Text("Compression Quality: $q%") },
                text = { Slider(value = q.toFloat(), onValueChange = { q = it.roundToInt() }, valueRange = 10f..100f) },
                confirmButton = { TextButton(onClick = { vm.setDefaultQuality(q); dialog = Dialog.NONE }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { dialog = Dialog.NONE }) { Text("Cancel") } }
            )
        }
        Dialog.SIZE -> AlertDialog(
            onDismissRequest = { dialog = Dialog.NONE },
            title = { Text("Default Resize Size") },
            text = {
                Column {
                    SizePreset.entries.forEach { p ->
                        Row(Modifier.fillMaxWidth().clickable { vm.setDefaultSize(p); dialog = Dialog.NONE }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = vm.defaultSize == p, onClick = { vm.setDefaultSize(p); dialog = Dialog.NONE })
                            Text("${p.label} ${p.hint}")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = Dialog.NONE }) { Text("Close") } }
        )
        Dialog.ABOUT -> AlertDialog(
            onDismissRequest = { dialog = Dialog.NONE },
            title = { Text("Pixli – Image Resizer") },
            text = { Text("Version 1.0.0\nSmaller Size • Better Quality") },
            confirmButton = { TextButton(onClick = { dialog = Dialog.NONE }) { Text("OK") } }
        )
        Dialog.PRIVACY -> AlertDialog(
            onDismissRequest = { dialog = Dialog.NONE },
            title = { Text("Privacy Policy") },
            text = {
                Text(
                    "All compression and resizing happens on your device. Your images are never uploaded " +
                        "and the app itself collects no personal data. Saved images go to " +
                        "Pictures/${ImageProcessor.FOLDER} on your device.\n\n" +
                        "The app shows ads through the Yandex Mobile Ads SDK, which may use device identifiers " +
                        "such as the advertising ID to serve ads. See Yandex's privacy policy for details."
                )
            },
            confirmButton = { TextButton(onClick = { dialog = Dialog.NONE }) { Text("OK") } }
        )
    }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, sub: String?, chevron: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Blue, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (sub != null) MutedText(sub)
        }
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted)
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Blue, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
