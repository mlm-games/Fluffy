package app.fluffy

import android.Manifest
import android.app.Application
import android.content.Context
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
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
import app.fluffy.helper.OpenTarget
import app.fluffy.helper.detectTarget
import app.fluffy.helper.launchImageViewer
import app.fluffy.helper.openWithExport
import app.fluffy.helper.purgeOldExports
import app.fluffy.helper.purgeOldViewerCache
import app.fluffy.helper.toViewableUris
import app.fluffy.io.FileSystemAccess
import app.fluffy.operations.ArchiveJobManager
import app.fluffy.ui.components.ConfirmationDialog
import app.fluffy.ui.screens.*
import app.fluffy.ui.theme.FluffyTheme
import app.fluffy.util.ArchiveTypes.baseNameForExtraction
import app.fluffy.viewmodel.*
import kotlinx.coroutines.flow.first
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
            filesVM.onPermissionsChanged()
        }
    }

    private val manageStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                filesVM.onPermissionsChanged()
            }
        }
    }

    private val showOverwriteDialog = mutableStateOf(false)
    private val overwriteMessage = mutableStateOf("")
    private var onOverwriteConfirm: (() -> Unit)? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(applicationContext)

        handleViewIntent(intent)

        checkStoragePermissions()

        purgeOldViewerCache()
        purgeOldExports()

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

            fun confirmCollisionsAndEnqueue(
                target: Uri,
                sourcesDisplayNames: List<String>,
                onEnqueue: (overwrite: Boolean) -> Unit
            ) {
                fun namesInDir(parent: Uri): Set<String> {
                    return when (parent.scheme) {
                        "file" -> {
                            val f = File(parent.path!!)
                            (f.listFiles()?.map { it.name } ?: emptyList()).toSet()
                        }
                        "content" -> {
                            val p = DocumentFile.fromTreeUri(this, parent)
                                ?: DocumentFile.fromSingleUri(this, parent)
                            p?.listFiles()?.mapNotNull { it.name }?.toSet() ?: emptySet()
                        }
                        else -> emptySet()
                    }
                }

                val destNames = namesInDir(target)
                val collisions = sourcesDisplayNames.count { it in destNames }
                if (collisions > 0) {
                    overwriteMessage.value = "$collisions item(s) with the same name exist in the destination. Overwrite them?"
                    onOverwriteConfirm = { onEnqueue(true) }
                    showOverwriteDialog.value = true
                } else {
                    onEnqueue(false)
                }
            }

            lifecycleScope.launch {
                // COPY/MOVE conflicts
                pendingCopy?.let { list ->
                    confirmCollisionsAndEnqueue(target, list.map { AppGraph.io.queryDisplayName(it) }) { overwrite ->
                        confirmShellWrite(target) {
                            tasksVM.enqueueCopy(list, target, overwrite)
                            pendingCopy = null
                        }
                    }
                }
                pendingMove?.let { list ->
                    confirmCollisionsAndEnqueue(target, list.map { AppGraph.io.queryDisplayName(it) }) { overwrite ->
                        confirmShellWrite(target) {
                            tasksVM.enqueueMove(list, target, overwrite)
                            pendingMove = null
                        }
                    }
                }

                // EXTRACT: if extracting into subfolder and it exists -> confirm
                pendingExtractArchive?.let { arch ->
                    val settings = AppGraph.settings.settingsFlow.first()
                    val name = AppGraph.io.queryDisplayName(arch)
                    val subfolder = baseNameForExtraction(name)
                    val mayUseSubfolder = settings.extractIntoSubfolder
                    val enqueueExtract = {
                        confirmShellWrite(target) {
                            tasksVM.enqueueExtract(arch, target, pendingExtractPassword, pendingExtractPaths)
                            pendingExtractArchive = null
                            pendingExtractPassword = null
                            pendingExtractPaths = null
                        }
                    }
                    if (mayUseSubfolder) {
                        val exists = childExists(target, subfolder)
                        if (exists) {
                            overwriteMessage.value = "Folder \"$subfolder\" already exists. Extract into it and overwrite files if needed?"
                            onOverwriteConfirm = { enqueueExtract() }
                            showOverwriteDialog.value = true
                        } else {
                            enqueueExtract()
                        }
                    } else {
                        enqueueExtract()
                    }
                }
            }
        }

        setContent {
            val settings by AppGraph.settings.settingsFlow.collectAsState(initial = AppSettings())

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

                        var showTaskCenter by rememberSaveable { mutableStateOf(false) }
                        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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

                        LaunchedEffect(browserState.pendingArchiveOpen) {
                            browserState.pendingArchiveOpen?.let { uri ->
                                val encoded = URLEncoder.encode(uri.toString(), StandardCharsets.UTF_8.name())
                                nav.navigate("archive/$encoded")
                                filesVM.clearPendingArchiveOpen()
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
                                            extractWithConfirm(
                                                archive = archive,
                                                password = null,
                                                includePaths = null,
                                                targetDir = targetDir,
                                                onAfterEnqueue = { showTaskCenter = true }
                                            )
                                        },
                                        onCreateZip = { sources, outName, targetDir, overwrite ->
                                            confirmShellWrite(targetDir) {
                                                tasksVM.enqueueCreateZip(sources, targetDir, outName, overwrite)
                                                showTaskCenter = true
                                            }
                                        },
                                        onCreate7z = { sources, outName, password, targetDir, overwrite ->
                                            confirmShellWrite(targetDir) {
                                                tasksVM.enqueueCreate7z(sources, targetDir, outName, password, overwrite)
                                                showTaskCenter = true
                                            }
                                        },
                                        onOpenSettings = { nav.navigate("settings") },
                                        onOpenTasks = { showTaskCenter = true },
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
                                                val s = AppGraph.settings.settingsFlow.first()
                                                val touchesShell = list.any { it.scheme == "root" || it.scheme == "shizuku" }
                                                val proceed: () -> Unit = {
                                                    lifecycleScope.launch {
                                                        list.forEach { AppGraph.io.deleteTree(it) }
                                                        filesVM.refreshCurrentDir()
                                                    }
                                                }
                                                if (s.warnBeforeShellWrites && touchesShell) {
                                                    overwriteMessage.value = "You're about to delete using elevated (root/shizuku) access. Continue?"
                                                    onOverwriteConfirm = proceed
                                                    showOverwriteDialog.value = true
                                                } else {
                                                    proceed()
                                                }
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
                                        onOpenFile = { file -> filesVM.openFile(file) },
                                        onQuickAccessClick = { item ->
                                            lifecycleScope.launch {
                                                val uri = item.uri
                                                if (uri?.scheme == "shizuku") {
                                                    val ok = app.fluffy.shell.ShizukuAccess.ensurePermission()
                                                    if (!ok) return@launch
                                                }
                                                filesVM.openQuickAccessItem(item)
                                            }
                                        },                                        onRequestPermission = { requestStoragePermission() },
                                        onShowQuickAccess = { filesVM.showQuickAccess() },
                                        onCreateFolder = { name -> filesVM.createNewFolder(name) },

                                        onOpenWith = { uri, name ->
                                            lifecycleScope.launch {
                                                openWithExport( // For sharable uri
                                                    src = uri,
                                                    displayName = name,
                                                    preferMime = settings.preferContentResolverMime
                                                )
                                            }
                                        },
                                        showFileCount = settings.showFileCount
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
                                                    extractWithConfirm(
                                                        archive = arch,
                                                        password = pwd,
                                                        includePaths = null,
                                                        targetDir = currentDir,
                                                        onAfterEnqueue = {
                                                            showTaskCenter = true
                                                            nav.popBackStack()
                                                        }
                                                    )
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
                                                    extractWithConfirm(
                                                        archive = arch,
                                                        password = pwd,
                                                        includePaths = paths,
                                                        targetDir = currentDir,
                                                        onAfterEnqueue = {
                                                            showTaskCenter = true
                                                            nav.popBackStack()
                                                        }
                                                    )
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
                                    onClearFinished = { /* visual clear only */ },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .navigationBarsPadding()
                                )
                            }
                        }
                    }

                    if (showOverwriteDialog.value) {
                        ConfirmationDialog(
                            title = "Overwrite?",
                            message = overwriteMessage.value,
                            onConfirm = {
                                showOverwriteDialog.value = false
                                onOverwriteConfirm?.invoke()
                                onOverwriteConfirm = null
                            },
                            onDismiss = {
                                showOverwriteDialog.value = false
                                onOverwriteConfirm = null
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(inIntent: Intent) {
        when (val target = inIntent.detectTarget()) {
            is OpenTarget.Images -> {
                lifecycleScope.launch {
                    val safe = applicationContext.toViewableUris(target.uris)
                    applicationContext.launchImageViewer(safe, startIndex = 0, title = target.title)
                }
            }
            is OpenTarget.Archive -> filesVM.setPendingArchiveOpen(target.uri)
            OpenTarget.None -> Unit
        }
    }

    private fun extractWithConfirm(
        archive: Uri,
        password: String?,
        includePaths: List<String>?,
        targetDir: Uri,
        onAfterEnqueue: (() -> Unit)? = null
    ) {
        lifecycleScope.launch {
            val settings = AppGraph.settings.settingsFlow.first()
            val proceedExtract: () -> Unit = {
                lifecycleScope.launch {
                    if (settings.extractIntoSubfolder) {
                        val sub = baseNameForExtraction(AppGraph.io.queryDisplayName(archive))
                        val exists = childExists(targetDir, sub)
                        val enqueue = {
                            tasksVM.enqueueExtract(archive, targetDir, password, includePaths)
                            onAfterEnqueue?.invoke()
                        }
                        if (exists) {
                            overwriteMessage.value = "Folder \"$sub\" already exists. Extract into it and overwrite files if needed?"
                            onOverwriteConfirm = enqueue as (() -> Unit)?
                            showOverwriteDialog.value = true
                        } else {
                            enqueue()
                        }
                    } else {
                        tasksVM.enqueueExtract(archive, targetDir, password, includePaths)
                        onAfterEnqueue?.invoke()
                    }
                }
            }
            val needsWarn = settings.warnBeforeShellWrites && (targetDir.scheme == "root" || targetDir.scheme == "shizuku")
            if (needsWarn) {
                overwriteMessage.value = "You're about to write using ${targetDir.scheme?.uppercase()} permissions. This can modify system files. Continue?"
                onOverwriteConfirm = proceedExtract
                showOverwriteDialog.value = true
            } else {
                proceedExtract()
            }
        }
    }

    private fun childExists(parent: Uri, name: String): Boolean {
        return when (parent.scheme) {
            "file" -> File(File(parent.path!!), name).exists()
            "content" -> {
                val p = DocumentFile.fromTreeUri(this, parent)
                    ?: DocumentFile.fromSingleUri(this, parent)
                p?.findFile(name) != null
            }
            else -> false
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

    private fun contentUriFor(file: File): Uri {
        return try {
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        } catch (_: Exception) {
            Uri.fromFile(file)
        }
    }

    private fun confirmShellWrite(target: Uri, proceed: () -> Unit) {
        lifecycleScope.launch {
            val s = AppGraph.settings.settingsFlow.first()
            val needsWarn = s.warnBeforeShellWrites && (target.scheme == "root" || target.scheme == "shizuku")
            if (needsWarn) {
                overwriteMessage.value = "You're about to write using ${target.scheme?.uppercase()} permissions. This can modify system files. Continue?"
                onOverwriteConfirm = proceed
                showOverwriteDialog.value = true
            } else {
                proceed()
            }
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

class ImageViewerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_IMAGES = "images"
        const val EXTRA_INITIAL_INDEX = "initial"
        const val EXTRA_TITLE = "title"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(applicationContext)

        val fromExtras = intent.getStringArrayListExtra(EXTRA_IMAGES)?.mapNotNull { runCatching { it.toUri() }.getOrNull() }.orEmpty()
        val fromData = intent.data?.let { listOf(it) }.orEmpty()
        val fromClip = buildList {
            intent.clipData?.let { cd ->
                for (i in 0 until cd.itemCount) {
                    cd.getItemAt(i)?.uri?.let { add(it) }
                }
            }
        }

        val allUris = (fromExtras + fromData + fromClip).distinct()
        if (allUris.isEmpty()) {
            finish()
            return
        }

        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val start = intent.getIntExtra(EXTRA_INITIAL_INDEX, 0).coerceIn(0, (allUris.size - 1).coerceAtLeast(0))
        val title = intent.getStringExtra(EXTRA_TITLE)

        setContent {
            val settings = AppGraph.settings.settingsFlow.collectAsState(initial = AppSettings()).value
            val dark = when (settings.themeMode) {
                0 -> isSystemInDarkTheme()
                1 -> false
                else -> true
            }
            FluffyTheme(darkTheme = dark, useAuroraTheme = settings.useAuroraTheme) {
                FullscreenImageViewer(
                    images = allUris.map { it.toString() },
                    initialPage = start,
                    onClose = { finish() }
                )
            }
        }
    }
}

class FluffyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(applicationContext)
    }
}