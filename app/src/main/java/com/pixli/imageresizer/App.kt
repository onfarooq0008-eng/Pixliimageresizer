package com.pixli.imageresizer

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext

@Composable
fun App(vm: MainViewModel) {
    val screen = vm.current
    val root = screen is Screen.Home || screen is Screen.Tools || screen is Screen.Saved || screen is Screen.Settings
    val snack = remember { SnackbarHostState() }
    val activity = LocalActivity.current
    val appContext = LocalContext.current.applicationContext
    val interstitials = remember { InterstitialManager(appContext) }

    LaunchedEffect(vm.saveEvents) {
        if (vm.saveEvents > 0) activity?.let { interstitials.onSaved(it) }
    }

    BackHandler(enabled = vm.stack.size > 1) { vm.pop() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snack.showSnackbar(it); vm.message = null }
    }

    Scaffold(
        containerColor = Bg,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = { if (root) BottomBar(screen, vm) }
    ) { inner ->
        Box(
            Modifier.padding(inner)
                .then(if (root) Modifier else Modifier.navigationBarsPadding())
                .imePadding()
                .fillMaxSize()
        ) {
            when (screen) {
                Screen.Home -> HomeScreen(vm)
                Screen.Tools -> ToolsScreen(vm)
                Screen.Saved -> SavedScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
                is Screen.Select -> SelectScreen(vm, screen.mode)
                Screen.Compress -> CompressScreen(vm)
                Screen.Resize -> ResizeScreen(vm)
                Screen.Edit -> EditScreen(vm)
                Screen.Batch -> BatchScreen(vm)
            }
            BusyOverlay(vm.busy, vm.progress)
        }
    }
}

@Composable
private fun BottomBar(screen: Screen, vm: MainViewModel) {
    val items = listOf<Triple<Screen, ImageVector, String>>(
        Triple(Screen.Home, Icons.Filled.Home, "Home"),
        Triple(Screen.Tools, Icons.Filled.Build, "Tools"),
        Triple(Screen.Saved, Icons.Filled.Folder, "Saved"),
        Triple(Screen.Settings, Icons.Filled.Settings, "Settings")
    )
    NavigationBar(containerColor = Color.White) {
        items.forEach { (s, icon, label) ->
            NavigationBarItem(
                selected = screen == s,
                onClick = { vm.goRoot(s) },
                icon = { Icon(icon, label) },
                label = { Text(label) }
            )
        }
    }
}
