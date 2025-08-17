package app.fluffy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.fluffy.data.repository.AppSettings
import app.fluffy.helper.DeviceUtils
import app.fluffy.ui.screens.FileBrowserScreen
import app.fluffy.ui.screens.SettingsScreen
import app.fluffy.ui.screens.TasksScreen
import app.fluffy.ui.screens.TvMainScreen
import app.fluffy.ui.theme.FluffyTheme
import app.fluffy.viewmodel.FileBrowserViewModel
import app.fluffy.viewmodel.SettingsViewModel
import app.fluffy.viewmodel.TasksViewModel
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {

    private inline fun <reified T : ViewModel> vm(crossinline create: () -> T) =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
        }

    private val filesVM: FileBrowserViewModel by viewModels {
        vm { FileBrowserViewModel(AppGraph.io, AppGraph.fileSystemAccess, AppGraph.archive, AppGraph.settings) }
    }
    private val tasksVM: TasksViewModel by viewModels {
        vm { TasksViewModel(this@MainActivity, AppGraph.archiveJobs) }
    }

    private val settingsVM: SettingsViewModel by viewModels {
        vm { SettingsViewModel(AppGraph.settings) }
    }


    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permission result
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            // Refresh file list
            filesVM.refreshCurrentDir()
        }
    }

    private val manageStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                filesVM.refreshCurrentDir()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(applicationContext)

        checkStoragePermissions()

        val pickRoot = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                filesVM.openRoot(it)
            }
        }

        var pendingCopy: List<Uri>? = null
        var pendingMove: List<Uri>? = null
        var pendingExtractArchive: Uri? = null
        var pendingExtractPassword: String? = null
        var pendingExtractPaths: List<String>? = null

        val pickTargetDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val target = uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                pendingCopy?.let { list ->
                    tasksVM.enqueueCopy(list, target)
                    pendingCopy = null
                }
                pendingMove?.let { list ->
                    tasksVM.enqueueMove(list, target)
                    pendingMove = null
                }
                pendingExtractArchive?.let { arch ->
                    tasksVM.enqueueExtract(arch, target, pendingExtractPassword, pendingExtractPaths)
                    pendingExtractArchive = null
                    pendingExtractPassword = null
                    pendingExtractPaths = null
                }
            }
        }

        setContent {
            val settings by AppGraph.settings.settingsFlow.collectAsState(initial = AppSettings())
            var showPermissionDialog by remember { mutableStateOf(false) }

            val dark = when (settings.themeMode) {
                0 -> isSystemInDarkTheme()
                1 -> false
                else -> true
            }
            val isTV = DeviceUtils.isTV(this)

            FluffyTheme(
                darkTheme = dark,
            ) {
                Surface {
                    val nav = rememberNavController()
                    val currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route

                    lifecycleScope.launch {
                        filesVM.state.collect { state ->
                            state.pendingFileOpen?.let { uri ->
                                // Check if it's an archive
                                val name = uri.lastPathSegment?.lowercase() ?: ""
                                val isArchive = name.endsWith(".zip") || name.endsWith(".7z") ||
                                        name.endsWith(".tar") || name.endsWith(".rar")

                                if (isArchive) {
                                    val encoded =
                                        URLEncoder.encode(uri.toString(), StandardCharsets.UTF_8.name())
                                    nav.navigate("archive?uri=$encoded")
                                    filesVM.clearPendingFileOpen()
                                }
                            }
                        }
                    }

                    val content: @Composable () -> Unit = {
                        NavHost(navController = nav, startDestination = "files") {
                            composable("files") {
                                FileBrowserScreen(
                                    state = filesVM.state.collectAsState().value,
                                    onPickRoot = { pickRoot.launch(null) },
                                    onOpenDir = { filesVM.openDir(it) },
                                    onBack = { filesVM.goUp() },
                                    onExtractArchive = { archive, targetDir ->
                                        tasksVM.enqueueExtract(archive, targetDir, null)
                                        nav.navigate("tasks")
                                    },
                                    onCreateZip = { sources, outName, targetDir ->
                                        tasksVM.enqueueCreateZip(sources, targetDir, outName)
                                        nav.navigate("tasks")
                                    },
                                    onOpenSettings = { nav.navigate("settings") },
                                    onOpenTasks = { nav.navigate("tasks") },
                                    onOpenArchive = { arch ->
                                        val encoded = URLEncoder.encode(arch.toString(), StandardCharsets.UTF_8.name())
                                        nav.navigate("archive?uri=$encoded")
                                    },
                                    onCopySelected = { list ->
                                        pendingCopy = list
                                        pickTargetDir.launch(null)
                                    },
                                    onMoveSelected = { list ->
                                        pendingMove = list
                                        pickTargetDir.launch(null)
                                    },
                                    onDeleteSelected = { list ->
                                        lifecycleScope.launch {
                                            list.forEach { AppGraph.io.deleteTree(it) }
                                            filesVM.refresh()
                                        }
                                    },
                                    onRenameOne = { uri, newName ->
                                        lifecycleScope.launch {
                                            if (newName.isNotBlank()) AppGraph.io.rename(uri, newName)
                                            filesVM.refresh()
                                        }
                                    },
                                    onCreate7z = { sources, outName, password, targetDir ->
                                        tasksVM.enqueueCreate7z(sources, targetDir, outName, password)
                                        nav.navigate("tasks")
                                    },
                                    // New handlers
                                    onOpenFile = { file ->
                                        filesVM.openFile(file)
                                    },
                                    onQuickAccessClick = { item ->
                                        item.file?.let { filesVM.openFileSystemPath(it) }
                                        item.uri?.let { filesVM.openDir(it) }
                                    },
                                    onRequestPermission = {
                                        requestStoragePermission()
                                    },
                                    onShowQuickAccess = {
                                        filesVM.showQuickAccess()
                                    }
                                )
                            }
                            composable("tasks") {
                                TasksScreen(
                                    workInfos = tasksVM.workInfos.collectAsState().value,
                                    onBack = { nav.popBackStack() }
                                )
                            }
                            composable("settings") {
                                // Use the tiny wrapper VM
                                SettingsScreen(vm = settingsVM)
                            }
                        }
                    }

                    if (isTV) {
                        TvMainScreen(
                            onNavigate = { route -> nav.navigate(route) },
                            currentRoute = currentRoute
                        ) { content() }
                    } else {
                        content()
                    }
                }

                // Permission dialog
                if (showPermissionDialog) {
                    AlertDialog(
                        onDismissRequest = { showPermissionDialog = false },
                        title = { Text("Storage Permission Required") },
                        text = {
                            Text("Fluffy needs storage permission to browse and manage your files. Please grant the permission to continue.")
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showPermissionDialog = false
                                requestStoragePermission()
                            }) {
                                Text("Grant Permission")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPermissionDialog = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }
    }

    private fun checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                // Show dialog or request permission
                requestManageStoragePermission()
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissions = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                if (ContextCompat.checkSelfPermission(
                        applicationContext,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            if (permissions.isNotEmpty()) {
                storagePermissionLauncher.launch(permissions.toTypedArray())
            }
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestManageStoragePermission()
        } else {
            val permissions = mutableListOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            storagePermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = "package:$packageName".toUri()
                manageStoragePermissionLauncher.launch(intent)
            } catch (_: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                manageStoragePermissionLauncher.launch(intent)
            }
        }
    }
}