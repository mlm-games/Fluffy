package app.fluffy

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
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
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.runtime.entryProvider
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
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
import app.fluffy.ui.components.DirectoryCounter
import app.fluffy.ui.components.snackbar.LauncherSnackbarHost
import app.fluffy.ui.components.snackbar.SnackbarManager
import app.fluffy.ui.screens.ArchiveViewerScreen
import app.fluffy.ui.screens.FileBrowserScreen
import app.fluffy.ui.screens.SettingsScreen
import app.fluffy.ui.screens.TasksScreen
import app.fluffy.ui.screens.TvMainScreen
import app.fluffy.ui.theme.FluffyTheme
import app.fluffy.ui.util.ScreenKey
import app.fluffy.viewmodel.BrowseLocation
import app.fluffy.viewmodel.FileBrowserViewModel
import app.fluffy.viewmodel.SettingsViewModel
import app.fluffy.viewmodel.TasksViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.compose.koinInject
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {

    // Picker mode (GET_CONTENT / OPEN_DOCUMENT)
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

    private val filesVM: FileBrowserViewModel by viewModel()
    private val tasksVM: TasksViewModel by viewModel()
    private val settingsVM: SettingsViewModel by viewModel()

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

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
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
        applicationContext.purgeOldIncoming()

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
            val s by AppGraph.settings.settingsFlow.collectAsState(initial = AppSettings())

            val dark = when (s.themeMode) {
                0 -> isSystemInDarkTheme()
                1 -> false
                else -> true
            }

            FluffyTheme(
                darkTheme = dark,
                useAuroraTheme = s.useAuroraTheme
            ) {
                val backStack = rememberNavBackStack(
                    ScreenKey.Files
                )

                val currentRoute: String? = when (backStack.lastOrNull()) {
                    is ScreenKey.Files -> "files"
                    is ScreenKey.Tasks -> "tasks"
                    is ScreenKey.Settings -> "settings"
                    is ScreenKey.Archive -> "archive"
                    else -> null
                }

                val browserState by filesVM.state.collectAsState()
                val workInfos by tasksVM.workInfos.collectAsState()

                var showTaskCenter by rememberSaveable { mutableStateOf(false) }
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                // Refresh after jobs finish
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

                // Auto-open task center when new RUNNING appears
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
                            backStack.add(ScreenKey.Archive(uri = uri.toString()))
                            filesVM.clearPendingFileOpen()
                        }
                    }
                }

                LaunchedEffect(browserState.pendingArchiveOpen) {
                    browserState.pendingArchiveOpen?.let { uri ->
                        backStack.add(ScreenKey.Archive(uri = uri.toString()))
                        filesVM.clearPendingArchiveOpen()
                    }
                }

                val content: @Composable () -> Unit = {
                    // Root surface for background; keep transparent if you rely on wallpaper etc.
                    Surface {
                        NavDisplay(
                            backStack = backStack,
                            onBack = {
                                if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                            },
                            entryDecorators = listOf(
                                rememberSaveableStateHolderNavEntryDecorator(),
                                rememberViewModelStoreNavEntryDecorator(),
                            ),
                            entryProvider = entryProvider {
                                entry<ScreenKey.Files> {
                                    FileBrowserScreen(
                                        state = browserState,
                                        isPickerMode = isPickerMode,
                                        onPickFile = { uri -> returnPickedFile(uri) },

                                        onPickRoot = { launchPickRootOrFallback(s.alwaysUseInAppFolderPicker) },

                                        // In-app folder picker (fallback)
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

                                        onOpenSettings = { backStack.add(ScreenKey.Settings) },
                                        onOpenTasks = { showTaskCenter = true },

                                        onOpenArchive = { arch ->
                                            backStack.add(ScreenKey.Archive(uri = arch.toString()))
                                        },

                                        onCopySelected = { list ->
                                            pendingCopy = list
                                            launchPickTargetDirOrFallback(s.alwaysUseInAppFolderPicker)
                                        },

                                        onMoveSelected = { list ->
                                            pendingMove = list
                                            launchPickTargetDirOrFallback(s.alwaysUseInAppFolderPicker)
                                        },

                                        onDeleteSelected = { list ->
                                            lifecycleScope.launch {
                                                val ss = AppGraph.settings.settingsFlow.first()
                                                val touchesShell = list.any { it.scheme == "root" || it.scheme == "shizuku" }
                                                val proceed: () -> Unit = {
                                                    lifecycleScope.launch {
                                                        list.forEach {
                                                            AppGraph.io.deleteTree(it)
                                                            DirectoryCounter.invalidateParent(it)
                                                        }
                                                        DirectoryCounter.invalidateAll()
                                                        filesVM.refreshCurrentDir()
                                                    }
                                                }
                                                if (ss.warnBeforeShellWrites && touchesShell) {
                                                    overwriteMessage.value =
                                                        "You're about to delete using elevated (root/shizuku) access. Continue?"
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
                                                    preferMime = s.preferContentResolverMime
                                                )
                                            }
                                        },

                                        showFileCount = s.showFileCount
                                    )
                                }

                                entry<ScreenKey.Archive> { args ->
                                    val uri = runCatching { args.uri.toUri() }.getOrNull() ?: return@entry
                                    ArchiveViewerScreen(
                                        archiveUri = uri,
                                        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                                        onExtractTo = { arch, pwd ->
                                            // reuse your existing logic: try current dir first; else prompt
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
                                                    onAfterEnqueue = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
                                                )
                                            } else {
                                                pendingExtractArchive = arch
                                                pendingExtractPassword = pwd
                                                pendingExtractPaths = null
                                                launchPickTargetDirOrFallback(s.alwaysUseInAppFolderPicker)
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
                                                    onAfterEnqueue = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
                                                )
                                            } else {
                                                pendingExtractArchive = arch
                                                pendingExtractPassword = pwd
                                                pendingExtractPaths = paths
                                                launchPickTargetDirOrFallback(s.alwaysUseInAppFolderPicker)
                                            }
                                        },
                                        onOpenAsFolder = { dirUri ->
                                            filesVM.openDir(dirUri)
                                            if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                                        }
                                    )
                                }

                                entry<ScreenKey.Tasks> {
                                    TasksScreen(
                                        workInfos = workInfos,
                                        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
                                    )
                                }

                                entry<ScreenKey.Settings> {
                                    SettingsScreen(vm = settingsVM)
                                }
                            }
                        )
                    }
                }

                val isTV = DeviceUtils.isTV(this@MainActivity)

                val snackbarHostState = remember { SnackbarHostState() }
                val snackbarManager: SnackbarManager = koinInject()


                Scaffold(
                    snackbarHost = {
                        LauncherSnackbarHost(
                            hostState = snackbarHostState,
                            manager = snackbarManager
                        )
                    }
                ) {
                    if (isTV) {
                        TvMainScreen(
                            onNavigate = { route ->
                                when (route) {
                                    "files" -> {
                                        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                                        if (backStack.lastOrNull() != ScreenKey.Files) {
                                            backStack.add(ScreenKey.Files)
                                        }
                                    }

                                    "tasks" -> {
                                        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                                        backStack.add(ScreenKey.Tasks)
                                    }

                                    "settings" -> {
                                        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                                        backStack.add(ScreenKey.Settings)
                                    }

                                    else -> Unit
                                }
                            },
                            currentRoute = currentRoute
                        ) { content() }
                    } else {
                        content()
                    }
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

                if (showOverwriteDialog.value) {
                    ConfirmationDialog(
                        title = "Confirm",
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun extractWithConfirm(
        archive: Uri,
        password: String?,
        includePaths: List<String>?,
        targetDir: Uri,
        onAfterEnqueue: () -> Unit = {},
    ) {
        confirmShellWrite(targetDir) {
            tasksVM.enqueueExtract(archive, targetDir, password, includePaths)
            onAfterEnqueue()
        }
    }

    private fun launchPickRootOrFallback(alwaysInApp: Boolean) {
        if (alwaysInApp) {
            showInAppFolderPicker.value = true
            inAppFolderPickerTitle.value = "Choose root folder"
            pendingFolderPickCallback = { uri -> filesVM.openRoot(uri) }
        } else {
            pickRoot.launch(null)
        }
    }

    private fun launchPickTargetDirOrFallback(alwaysInApp: Boolean) {
        if (alwaysInApp) {
            showInAppFolderPicker.value = true
            inAppFolderPickerTitle.value = "Choose destination folder"
            pendingFolderPickCallback = { uri -> handleTargetDirPicked(uri) }
        } else {
            pickTargetDir.launch(null)
        }
    }

    private fun dismissInAppFolderPicker() {
        showInAppFolderPicker.value = false
        pendingFolderPickCallback = null
    }

    private fun handleTargetDirPicked(target: Uri) {
        // Apply whichever pending operation exists. Clear pending after.
        val copy = pendingCopy
        val move = pendingMove
        val exArch = pendingExtractArchive

        when {
            copy != null -> {
                pendingCopy = null
                confirmShellWrite(target) {
                    tasksVM.enqueueCopy(copy, target, overwrite = false)
                }
            }

            move != null -> {
                pendingMove = null
                confirmShellWrite(target) {
                    tasksVM.enqueueMove(move, target, overwrite = false)
                }
            }

            exArch != null -> {
                val pwd = pendingExtractPassword
                val paths = pendingExtractPaths
                pendingExtractArchive = null
                pendingExtractPassword = null
                pendingExtractPaths = null

                extractWithConfirm(
                    archive = exArch,
                    password = pwd,
                    includePaths = paths,
                    targetDir = target
                )
            }

            else -> Unit
        }
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent == null) return

        when (val target = intent.detectTarget()) {

            is OpenTarget.Images -> {
                // Convert root:// and shizuku:// to viewable content URIs, then open viewer
                lifecycleScope.launch {
                    val viewable = applicationContext.toViewableUris(target.uris)
                    applicationContext.launchImageViewer(
                        uris = viewable,
                        startIndex = 0,
                        title = target.title
                    )
                }
            }

            is OpenTarget.Archive -> {
                filesVM.setPendingArchiveOpen(target.uri)
            }

            is OpenTarget.Shared -> {
                handleSharedUris(target.uris)
            }

            OpenTarget.None -> {
                // nein
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
        if (specificIntent.resolveActivity(packageManager) != null) return true

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

                if (u.scheme == "content") {
                    val name = sanitizeName(AppGraph.io.queryDisplayName(u))
                    val out = File(cacheDir, "incoming_${System.currentTimeMillis()}_$name")
                    runCatching {
                        AppGraph.io.openIn(u).use { input ->
                            out.outputStream().use { input.copyTo(it) }
                        }
                        return@map Uri.fromFile(out)
                    }.getOrElse {
                        return@map u
                    }
                }

                u
            }
        }

    private fun handleSharedUris(uris: List<Uri>) {
        lifecycleScope.launch {
            val staged = stageSharedIfNeeded(uris)
            pendingCopy = staged
            val s = AppGraph.settings.settingsFlow.first()
            launchPickTargetDirOrFallback(s.alwaysUseInAppFolderPicker)
        }
    }

    private fun Context.purgeOldIncoming(maxAgeMs: Long = 72L * 3600_000L) {
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
    val title = remember(wi.tags) { friendlyTitle(wi) }
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
    onCancel: ((UUID) -> Unit)?
) {
    val title = remember(wi.tags) { friendlyTitle(wi) }
    val progress = wi.progress.getFloat("progress", -1f).takeIf { it in 0f..1f }
    val isRunning = wi.state == WorkInfo.State.RUNNING

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
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

                if (isRunning && onCancel != null) {
                    TextButton(
                        onClick = { onCancel(wi.id) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Cancel")
                    }
                }
            }

            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
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