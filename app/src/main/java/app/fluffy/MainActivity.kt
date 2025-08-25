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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.work.WorkInfo
import app.fluffy.data.repository.AppSettings
import app.fluffy.helper.DeviceUtils
import app.fluffy.io.FileSystemAccess
import app.fluffy.operations.ArchiveJobManager
import app.fluffy.ui.screens.*
import app.fluffy.ui.theme.FluffyTheme
import app.fluffy.viewmodel.*
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

class MainActivity : ComponentActivity() {

    private inline fun <reified T : ViewModel> vm(crossinline create: () -> T) =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
        }

    private val filesVM: FileBrowserViewModel by viewModels {
        vm {
            FileBrowserViewModel(
                AppGraph.io,
                AppGraph.fileSystemAccess,
                AppGraph.archive,
                AppGraph.settings
            )
        }
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
        val allGranted = permissions.values.all { it }
        if (allGranted) {
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

    @OptIn(ExperimentalMaterial3Api::class)
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
            try {
                contentResolver.takePersistableUriPermission(
                    target,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) { }

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

            FluffyTheme(
                darkTheme = dark,
                useAuroraTheme = settings.useAuroraTheme
            ) {
                Box(Modifier.fillMaxSize()) {
                    Surface {
                        val nav = rememberNavController()
                        val currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route

                        val browserState by filesVM.state.collectAsState()
                        val workInfos by tasksVM.workInfos.collectAsState()

                        // NEW: Task Center bottom sheet state
                        var showTaskCenter by rememberSaveable { mutableStateOf(false) }
                        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                        // Auto-refresh current dir when a task finishes (as before)
                        val seenFinished = remember { mutableSetOf<String>() }
                        LaunchedEffect(workInfos) {
                            var refreshNeeded = false
                            workInfos.forEach { wi ->
                                if (wi.state.isFinished && seenFinished.add(wi.id.toString())) {
                                    refreshNeeded = true
                                }
                            }
                            if (refreshNeeded) filesVM.refreshCurrentDir()
                        }

                        val seenRunning = remember { mutableSetOf<String>() }
                        LaunchedEffect(workInfos) {
                            val running = workInfos.filter { it.state == WorkInfo.State.RUNNING }
                            val newId = running.map { it.id.toString() }.firstOrNull { it !in seenRunning }
                            if (newId != null) {
                                seenRunning.addAll(running.map { it.id.toString() })
                                showTaskCenter = true
                            }
                        }

                        // Existing pending file open navigation
                        LaunchedEffect(browserState.pendingFileOpen) {
                            browserState.pendingFileOpen?.let { uri ->
                                val name = uri.lastPathSegment?.lowercase() ?: ""
                                if (FileSystemAccess.isArchiveFile(name)) {
                                    val encoded = URLEncoder.encode(uri.toString(), StandardCharsets.UTF_8.name())
                                    nav.navigate("archive/$encoded")
                                    filesVM.clearPendingFileOpen()
                                }
                            }
                        }

                        val content: @Composable () -> Unit = {
                            NavHost(navController = nav, startDestination = "files") {
                                composable("files") {
                                    FileBrowserScreen(
                                        state = browserState,
                                        onPickRoot = { pickRoot.launch(null) },
                                        onOpenDir = { filesVM.openDir(it) },
                                        onBack = { filesVM.goUp() },
                                        onExtractArchive = { archive, targetDir ->
                                            tasksVM.enqueueExtract(archive, targetDir, null)
                                        },
                                        onCreateZip = { sources, outName, targetDir ->
                                            tasksVM.enqueueCreateZip(sources, targetDir, outName)
                                            // Instead of navigating away, open Task Center
                                            showTaskCenter = true
                                        },
                                        onOpenSettings = { nav.navigate("settings") },
                                        onOpenTasks = {
                                            // Open in-app Task Center instead of a separate screen
                                            showTaskCenter = true
                                        },
                                        onOpenArchive = { arch ->
                                            val encoded = URLEncoder.encode(arch.toString(), StandardCharsets.UTF_8.name())
                                            nav.navigate("archive/$encoded")
                                        },
                                        onCopySelected = { list ->
                                            pendingCopy = list
                                            pickTargetDir.launch(null)
                                            showTaskCenter = true
                                        },
                                        onMoveSelected = { list ->
                                            pendingMove = list
                                            pickTargetDir.launch(null)
                                            showTaskCenter = true
                                        },
                                        onDeleteSelected = { list ->
                                            lifecycleScope.launch {
                                                list.forEach { AppGraph.io.deleteTree(it) }
                                                filesVM.refreshCurrentDir()
                                            }
                                        },
                                        onRenameOne = { uri, newName ->
                                            lifecycleScope.launch {
                                                if (newName.isNotBlank()) {
                                                    AppGraph.io.rename(uri, newName)
                                                    filesVM.refreshCurrentDir()
                                                }
                                            }
                                        },
                                        onCreate7z = { sources, outName, password, targetDir ->
                                            tasksVM.enqueueCreate7z(sources, targetDir, outName, password)
                                            showTaskCenter = true
                                        },
                                        onOpenFile = { file -> filesVM.openFile(file) },
                                        onQuickAccessClick = { item -> filesVM.openQuickAccessItem(item) },
                                        onRequestPermission = { requestStoragePermission() },
                                        onShowQuickAccess = { filesVM.showQuickAccess() },
                                        onCreateFolder = { name -> filesVM.createNewFolder(name) },
                                        onOpenWith = { uri, name ->
                                            val final = if (uri.scheme == "file") contentUriFor(File(uri.path!!)) else uri
                                            openWith(final, name)
                                        }
                                    )
                                }

                                composable(
                                    route = "archive/{uri}",
                                    arguments = listOf(navArgument("uri") { type = NavType.StringType })
                                ) { backStack ->
                                    val encoded = backStack.arguments?.getString("uri") ?: ""
                                    val uri = runCatching {
                                        URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()).toUri()
                                    }.getOrNull()

                                    if (uri != null) {
                                        ArchiveViewerScreen(
                                            archiveUri = uri,
                                            onBack = { nav.popBackStack() },
                                            onExtractTo = { arch, pwd ->
                                                val currentDir = when (val location = browserState.currentLocation) {
                                                    is BrowseLocation.FileSystem -> Uri.fromFile(location.file)
                                                    is BrowseLocation.SAF -> browserState.currentDir
                                                    else -> null
                                                }
                                                if (currentDir != null) {
                                                    tasksVM.enqueueExtract(arch, currentDir, pwd)
                                                    showTaskCenter = true
                                                    nav.popBackStack()
                                                } else {
                                                    pendingExtractArchive = arch
                                                    pendingExtractPassword = pwd
                                                    pendingExtractPaths = null
                                                    pickTargetDir.launch(null)
                                                    showTaskCenter = true
                                                }
                                            },
                                            onExtractSelected = { arch, paths, pwd ->
                                                val currentDir = when (val location = browserState.currentLocation) {
                                                    is BrowseLocation.FileSystem -> Uri.fromFile(location.file)
                                                    is BrowseLocation.SAF -> browserState.currentDir
                                                    else -> null
                                                }
                                                if (currentDir != null) {
                                                    tasksVM.enqueueExtract(arch, currentDir, pwd, paths)
                                                    showTaskCenter = true
                                                    nav.popBackStack()
                                                } else {
                                                    pendingExtractArchive = arch
                                                    pendingExtractPassword = pwd
                                                    pendingExtractPaths = paths
                                                    pickTargetDir.launch(null)
                                                    showTaskCenter = true
                                                }
                                            },
                                            onOpenAsFolder = { dirUri ->
                                                filesVM.openDir(dirUri)
                                                nav.popBackStack()
                                            }
                                        )
                                    }
                                }

                                composable("tasks") {
                                    TasksScreen(
                                        workInfos = tasksVM.workInfos.collectAsState().value,
                                        onBack = { nav.popBackStack() }
                                    )
                                }

                                composable("settings") {
                                    SettingsScreen(vm = settingsVM)
                                }
                            }
                        }

                        val isTV = DeviceUtils.isTV(this@MainActivity)
                        if (isTV) {
                            TvMainScreen(
                                onNavigate = { route -> nav.navigate(route) },
                                currentRoute = currentRoute
                            ) { content() }
                        } else {
                            content()
                        }

                        // Mini bottom overlay for the latest task (already present)
                        val active = remember(workInfos) {
                            val running = workInfos.filter { it.state == WorkInfo.State.RUNNING }
                            when {
                                running.isNotEmpty() -> running.last()
                                else -> workInfos.lastOrNull { it.state == WorkInfo.State.ENQUEUED }
                            }
                        }
                        if (active != null) {
                            MiniTaskIndicator(
                                wi = active,
                                onOpenTasks = { showTaskCenter = true }
                            )
                        }

                        if (showTaskCenter) {
                            ModalBottomSheet(
                                onDismissRequest = { showTaskCenter = false },
                                sheetState = sheetState,
                                dragHandle = { BottomSheetDefaults.DragHandle() }
                            ) {
                                TaskCenterSheet(
                                    workInfos = workInfos,
                                    onCancel = { id -> tasksVM.cancel(id) },
                                    onClearFinished = { /* let users dismiss finished tasks visually */ },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .navigationBarsPadding()
                                )
                            }
                        }
                    }

                    if (showPermissionDialog) {
                        AlertDialog(
                            onDismissRequest = { showPermissionDialog = false },
                            title = { Text("Storage Permission Required") },
                            text = { Text("Fluffy needs storage permission to browse and manage your files. Please grant the permission to continue.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    showPermissionDialog = false
                                    requestStoragePermission()
                                }) { Text("Grant Permission") }
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
    }

    private fun checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                requestManageStoragePermission()
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissions = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                if (ContextCompat.checkSelfPermission(
                        this,
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

    private fun openWith(uri: Uri, displayName: String) {
        val mime = FileSystemAccess.getMimeType(displayName)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(view, "Open with"))
    }

    private fun contentUriFor(file: File): Uri {
        return try {
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        } catch (_: Exception) {
            Uri.fromFile(file)
        }
    }
}

/**
 * Small, user-friendly task indicator bar shown at the bottom of the screen
 */
@Composable
private fun MiniTaskIndicator(
    wi: WorkInfo,
    onOpenTasks: () -> Unit
) {
    // Derive a friendly title from tags
    val title = remember(wi.tags) { friendlyTitle(wi) }
    // Progress value (0f..1f) if provided by workers, else null
    val progress = wi.progress.getFloat("progress", -1f).takeIf { it in 0f..1f }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        ElevatedCard(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(0.9f),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    TextButton(onClick = onOpenTasks) {
                        Text("Details")
                    }
                }
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                    )
                }
            }
        }
    }
}


@Composable
private fun TaskCenterSheet(
    workInfos: List<WorkInfo>,
    onCancel: (UUID) -> Unit,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val active = workInfos.filter { !it.state.isFinished }
    val finished = workInfos.filter { it.state.isFinished }.takeLast(10)

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Task Center",
            style = MaterialTheme.typography.titleMedium
        )

        if (active.isEmpty() && finished.isEmpty()) {
            Text(
                "No tasks at the moment.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (active.isNotEmpty()) {
            Text(
                "In progress",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            active.forEach { wi -> TaskRowCompact(wi, onCancel = onCancel) }
        }

        if (finished.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Recent",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            finished.asReversed().forEach { wi -> TaskRowCompact(wi, onCancel = null) }
        }
    }
}

@Composable
private fun TaskRowCompact(
    wi: WorkInfo,
    onCancel: ((UUID) -> Unit)?,
) {
    val title = friendlyTitle(wi)
    val progress = wi.progress.getFloat("progress", -1f).takeIf { it in 0f..1f }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        wi.state.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (onCancel != null && wi.state == WorkInfo.State.RUNNING) {
                    TextButton(onClick = { onCancel(wi.id) }) { Text("Cancel") }
                }
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .padding(top = 8.dp)
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .padding(top = 8.dp)
                )
            }
        }
    }
}

private fun friendlyTitle(wi: WorkInfo): String {
    val t = wi.tags
    return when {
        t.contains(ArchiveJobManager.TAG_EXTRACT) -> "Extracting files…"
        t.contains(ArchiveJobManager.TAG_CREATE_ZIP) -> "Creating ZIP…"
        t.contains(ArchiveJobManager.TAG_CREATE_7Z) -> "Creating 7z…"
        t.contains(ArchiveJobManager.TAG_MOVE) -> "Moving files…"
        t.contains(ArchiveJobManager.TAG_COPY) -> "Copying files…"
        else -> "Working…"
    }
}