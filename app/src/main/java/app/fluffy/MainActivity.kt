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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import app.fluffy.helper.SafAvailability
import app.fluffy.helper.detectTarget
import app.fluffy.helper.launchImageViewer
import app.fluffy.helper.openWithExport
import app.fluffy.helper.purgeOldExports
import app.fluffy.helper.purgeOldViewerCache
import app.fluffy.helper.toViewableUris
import app.fluffy.io.FileSystemAccess
import app.fluffy.operations.ArchiveJobManager
import app.fluffy.ui.components.ConfirmationDialog
import app.fluffy.ui.components.DirectoryCounter
import app.fluffy.ui.screens.ArchiveViewerScreen
import app.fluffy.ui.screens.FileBrowserScreen
import app.fluffy.ui.screens.SettingsScreen
import app.fluffy.ui.screens.TasksScreen
import app.fluffy.ui.screens.TvMainScreen
import app.fluffy.ui.theme.FluffyTheme
import app.fluffy.util.ArchiveTypes.baseNameForExtraction
import app.fluffy.viewmodel.BrowseLocation
import app.fluffy.viewmodel.FileBrowserViewModel
import app.fluffy.viewmodel.SettingsViewModel
import app.fluffy.viewmodel.TasksViewModel
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

    private var isPickerMode = false
    private var pickerMimeType: String? = null

    // Pending operations that need a destination folder
    private var pendingCopy: List<Uri>? = null
    private var pendingMove: List<Uri>? = null
    private var pendingExtractArchive: Uri? = null
    private var pendingExtractPassword: String? = null
    private var pendingExtractPaths: List<String>? = null

    // SAF tree pickers
    private lateinit var pickRoot: ActivityResultLauncher<Uri?>
    private lateinit var pickTargetDir: ActivityResultLauncher<Uri?>

    private val showInAppFolderPicker = mutableStateOf(false)
    private val inAppFolderPickerTitle = mutableStateOf("Choose destination folder")
    private var pendingFolderPickCallback: ((Uri) -> Unit)? = null

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
            } else {
                requestRegularStoragePermissions()
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

        // Picker mode (GET_CONTENT / OPEN_DOCUMENT)
        isPickerMode = intent?.action in listOf(
            Intent.ACTION_GET_CONTENT,
            Intent.ACTION_OPEN_DOCUMENT
        )
        pickerMimeType = intent?.type
        if (isPickerMode) {
            filesVM.setPickerMode(true, pickerMimeType)
        }

        handleViewIntent(intent)
        checkStoragePermissions()

        purgeOldViewerCache()
        purgeOldExports()
        purgeOldIncoming()

        pickRoot = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                // SAF tree selection; persistable grants only make sense for content://
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                filesVM.openRoot(it)
            }
        }

        pickTargetDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val target = uri ?: return@registerForActivityResult
            handleTargetDirPicked(target)
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
                                    DirectoryCounter.invalidateAll()
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

                        LaunchedEffect(workInfos) {
                            var refreshNeeded = false
                            workInfos.forEach { wi ->
                                if (wi.state == WorkInfo.State.SUCCEEDED) {
                                    val workId = wi.id.toString()
                                    if (seenFinished.add(workId)) {
                                        refreshNeeded = true

                                        if (wi.tags.contains(ArchiveJobManager.TAG_MOVE) ||
                                            wi.tags.contains(ArchiveJobManager.TAG_CREATE_ZIP) ||
                                            wi.tags.contains(ArchiveJobManager.TAG_CREATE_7Z)
                                        ) {
                                            DirectoryCounter.invalidateAll()
                                        }
                                    }
                                }
                            }
                            if (refreshNeeded) filesVM.refreshCurrentDir()
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
                                        isPickerMode = isPickerMode,
                                        onPickFile = { uri -> returnPickedFile(uri) },

                                        // SAF pick root if usable; else fallback to in-app folder picker
                                        onPickRoot = { launchPickRootOrFallback() },

                                        // In-app folder picker (fallback) wiring
                                        pickFolderMode = showInAppFolderPicker.value,
                                        pickFolderTitle = inAppFolderPickerTitle.value,
                                        onPickFolder = { folderUri ->
                                            val cb = pendingFolderPickCallback
                                            dismissInAppFolderPicker()
                                            cb?.invoke(folderUri)
                                        },
                                        onCancelPickFolder = { dismissInAppFolderPicker() },

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
                                            launchPickTargetDirOrFallback()
                                        },
                                        onMoveSelected = { list ->
                                            pendingMove = list
                                            launchPickTargetDirOrFallback()
                                        },
                                        onDeleteSelected = { list ->
                                            lifecycleScope.launch {
                                                val s = AppGraph.settings.settingsFlow.first()
                                                val touchesShell = list.any { it.scheme == "root" || it.scheme == "shizuku" }
                                                val proceed: () -> Unit = {
                                                    lifecycleScope.launch {
                                                        list.forEach { AppGraph.io.deleteTree(it); DirectoryCounter.invalidateParent(it) }
                                                        DirectoryCounter.invalidateAll()
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
                                        },
                                        onRequestPermission = { requestStoragePermission() },
                                        onShowQuickAccess = { filesVM.showQuickAccess() },
                                        onCreateFolder = { name -> filesVM.createNewFolder(name) },

                                        onOpenWith = { uri, name ->
                                            lifecycleScope.launch {
                                                openWithExport(
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
                                                        onAfterEnqueue = { nav.popBackStack() }
                                                    )
                                                } else {
                                                    pendingExtractArchive = arch
                                                    pendingExtractPassword = pwd
                                                    pendingExtractPaths = null
                                                    launchPickTargetDirOrFallback()
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
                                                        onAfterEnqueue = { nav.popBackStack() }
                                                    )
                                                } else {
                                                    pendingExtractArchive = arch
                                                    pendingExtractPassword = pwd
                                                    pendingExtractPaths = paths
                                                    launchPickTargetDirOrFallback()
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

            is OpenTarget.Shared -> {
                handleSharedUris(target.uris)
            }

            OpenTarget.None -> Unit
        }
    }

    private fun launchPickRootOrFallback() {
        // Prefer SAF tree picker when it’s actually usable; otherwise fall back to in-app folder picker.
        if (SafAvailability.canOpenDocumentTree(this)) {
            runCatching {
                pickRoot.launch(null)
                return
            }
        }

        // Fallback: choose a folder in-app and open it for browsing.
        startInAppFolderPicker("Choose folder to browse") { folderUri ->
            openFolderUriInBrowser(folderUri)
        }
    }

    private fun launchPickTargetDirOrFallback() {
        // Prefer SAF tree picker when it’s actually usable; otherwise fall back to in-app folder picker.
        if (SafAvailability.canOpenDocumentTree(this)) {
            runCatching {
                pickTargetDir.launch(null)
                return
            }
        }

        startInAppFolderPicker("Choose destination folder") { folderUri ->
            handleTargetDirPicked(folderUri)
        }
    }

    private fun startInAppFolderPicker(title: String, onPicked: (Uri) -> Unit) {
        inAppFolderPickerTitle.value = title
        pendingFolderPickCallback = onPicked
        showInAppFolderPicker.value = true
    }

    private fun dismissInAppFolderPicker() {
        showInAppFolderPicker.value = false
        pendingFolderPickCallback = null
        inAppFolderPickerTitle.value = "Choose destination folder"
    }

    private fun openFolderUriInBrowser(uri: Uri) {
        when (uri.scheme) {
            "content" -> {
                // Treat as SAF tree root
                filesVM.openRoot(uri)
            }
            "file" -> {
                val p = uri.path ?: return
                filesVM.openFileSystemPath(File(p))
            }
            "root", "shizuku" -> {
                filesVM.openDir(uri)
            }
        }
    }

    private fun handleTargetDirPicked(target: Uri) {
        // If we came from the in-app picker, hide it now.
        showInAppFolderPicker.value = false
        pendingFolderPickCallback = null

        // Persist SAF grants only for content:// trees (and only if we can)
        if (target.scheme == "content") {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    target,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }

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
                overwriteMessage.value =
                    "$collisions item(s) with the same name exist in the destination. Overwrite them?"
                onOverwriteConfirm = { onEnqueue(true) }
                showOverwriteDialog.value = true
            } else {
                onEnqueue(false)
            }
        }

        lifecycleScope.launch {
            // COPY
            pendingCopy?.let { list ->
                confirmCollisionsAndEnqueue(target, list.map { AppGraph.io.queryDisplayName(it) }) { overwrite ->
                    confirmShellWrite(target) {
                        tasksVM.enqueueCopy(list, target, overwrite)
                        pendingCopy = null
                    }
                }
            }

            // MOVE
            pendingMove?.let { list ->
                confirmCollisionsAndEnqueue(target, list.map { AppGraph.io.queryDisplayName(it) }) { overwrite ->
                    confirmShellWrite(target) {
                        tasksVM.enqueueMove(list, target, overwrite)
                        pendingMove = null
                    }
                }
            }

            // EXTRACT (from archive viewer fallback)
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
                        overwriteMessage.value =
                            "Folder \"$subfolder\" already exists. Extract into it and overwrite files if needed?"
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
                            overwriteMessage.value =
                                "Folder \"$sub\" already exists. Extract into it and overwrite files if needed?"
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
            val needsWarn =
                settings.warnBeforeShellWrites && (targetDir.scheme == "root" || targetDir.scheme == "shizuku")
            if (needsWarn) {
                overwriteMessage.value =
                    "You're about to write using ${targetDir.scheme?.uppercase()} permissions. This can modify system files. Continue?"
                onOverwriteConfirm = proceedExtract
                showOverwriteDialog.value = true
            } else {
                proceedExtract()
            }
        }
    }

    private fun returnPickedFile(uri: Uri) {
        val resultIntent = Intent().apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            if (intent?.action == Intent.ACTION_OPEN_DOCUMENT) {
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        setResult(RESULT_OK, resultIntent)
        finish()
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
                if (canRequestManageStorage()) {
                    requestManageStoragePermission()
                } else {
                    requestRegularStoragePermissions()
                }
            }
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (canRequestManageStorage()) {
                requestManageStoragePermission()
            } else {
                requestRegularStoragePermissions()
            }
        } else {
            requestRegularStoragePermissions()
        }
    }

    private fun canRequestManageStorage(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        val specificIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = "package:$packageName".toUri()
        }
        if (specificIntent.resolveActivity(packageManager) != null) {
            return true
        }

        val generalIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        return generalIntent.resolveActivity(packageManager) != null
    }

    private fun requestRegularStoragePermissions() {
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

    private fun requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = "package:$packageName".toUri()

                if (intent.resolveActivity(packageManager) != null) {
                    manageStoragePermissionLauncher.launch(intent)
                } else {
                    val generalIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    if (generalIntent.resolveActivity(packageManager) != null) {
                        manageStoragePermissionLauncher.launch(generalIntent)
                    } else {
                        requestRegularStoragePermissions()
                    }
                }
            } catch (_: Exception) {
                requestRegularStoragePermissions()
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
                overwriteMessage.value =
                    "You're about to write using ${target.scheme?.uppercase()} permissions. This can modify system files. Continue?"
                onOverwriteConfirm = proceed
                showOverwriteDialog.value = true
            } else {
                proceed()
            }
        }
    }

    private fun sanitizeName(name: String): String =
        name.replace('/', '_').replace('\\', '_').ifBlank { "item" }

    private suspend fun stageSharedIfNeeded(uris: List<Uri>): List<Uri> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            uris.map { u ->
                if (u.scheme == "file") return@map u
                if (u.scheme == "root" || u.scheme == "shizuku") return@map u

                // Most shared URIs are content:// and may be temporary.
                if (u.scheme == "content") {
                    val name = sanitizeName(AppGraph.io.queryDisplayName(u))
                    val out = File(cacheDir, "incoming_${System.currentTimeMillis()}_$name")
                    runCatching {
                        AppGraph.io.openIn(u).use { input ->
                            out.outputStream().use { input.copyTo(it) }
                        }
                        return@map Uri.fromFile(out)
                    }.getOrElse {
                        // fall back to original URI
                        return@map u
                    }
                }

                u
            }
        }

    private fun handleSharedUris(uris: List<Uri>) {
        lifecycleScope.launch {
            // Stage first so work won’t break later
            val staged = stageSharedIfNeeded(uris)

            pendingCopy = staged
            launchPickTargetDirOrFallback()
        }
    }

    fun Context.purgeOldIncoming(maxAgeMs: Long = 72L * 3600_000L) {
        val now = System.currentTimeMillis()
        cacheDir.listFiles()?.forEach { f ->
            if (f.name.startsWith("incoming_") && now - f.lastModified() > maxAgeMs) {
                runCatching { f.delete() }
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

class FluffyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(applicationContext)
    }
}