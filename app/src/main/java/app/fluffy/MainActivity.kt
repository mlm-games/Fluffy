package app.fluffy

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.runtime.entryProvider
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.work.WorkInfo
import app.fluffy.R
import app.fluffy.cache.CacheManager
import app.fluffy.data.repository.AppSettings
import app.fluffy.data.repository.QueueAddResult
import app.fluffy.data.repository.QueuedTask
import app.fluffy.data.repository.SettingsRepository
import app.fluffy.data.repository.applyAppLocale
import app.fluffy.io.SafIo
import app.fluffy.platform.StorageAccessPolicy
import app.fluffy.provider.LocalDocumentsProvider
import app.fluffy.helper.OpenTarget
import app.fluffy.helper.detectTarget
import app.fluffy.helper.launchImageViewer
import app.fluffy.helper.launchTextEditor
import app.fluffy.helper.openContent
import app.fluffy.helper.shareWithFolders
import app.fluffy.helper.toViewableUris
import app.fluffy.io.FileSystemAccess
import app.fluffy.operations.ArchiveJobManager
import app.fluffy.search.SearchHit
import app.fluffy.shell.ShizukuAccess
import app.fluffy.ui.components.ConfirmationDialog
import app.fluffy.ui.components.DirectoryCounter
import app.fluffy.ui.components.snackbar.LauncherSnackbarHost
import app.fluffy.ui.components.snackbar.SnackbarManager
import app.fluffy.ui.screens.ArchiveViewerScreen
import app.fluffy.ui.screens.FileBrowserScreen
import app.fluffy.ui.screens.SearchScreen
import app.fluffy.ui.screens.SettingsScreen
import app.fluffy.ui.screens.TasksScreen
import app.fluffy.ui.theme.FluffyTheme
import app.fluffy.ui.util.ScreenKey
import app.fluffy.util.AppLog
import app.fluffy.viewmodel.BrowseLocation
import app.fluffy.viewmodel.FileBrowserState
import app.fluffy.viewmodel.FileBrowserViewModel
import app.fluffy.viewmodel.PendingAction
import app.fluffy.viewmodel.PendingTaskRun
import app.fluffy.viewmodel.SearchViewModel
import app.fluffy.viewmodel.SettingsViewModel
import app.fluffy.viewmodel.TasksViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // Picker mode (GET_CONTENT / OPEN_DOCUMENT / OPEN_DOCUMENT_TREE / CREATE_DOCUMENT)
    private var isPickerMode = false
    private var isTreePickMode = false
    private var isCreateDocumentMode = false
    private var pickerAction: String? = null
    private var pickerMimeType: String? = null
    private var createDocumentInitialName: String? = null

    // Pending operations that need a destination folder
    private var pendingAction: PendingAction = PendingAction.None
    private var pendingFolderCallback: ((Uri) -> Unit)? = null
    private var folderPickerActive = false
    private var showTaskCenter by mutableStateOf(false)

    // SAF tree pickers
    private lateinit var pickRoot: ActivityResultLauncher<Uri?>
    private lateinit var pickTargetDir: ActivityResultLauncher<Uri?>

    private val showInAppFolderPicker = mutableStateOf(false)
    private val inAppFolderPickerTitle = mutableStateOf("")

    private val io: SafIo by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val storageAccessPolicy: StorageAccessPolicy by inject()
    private val snackbar: SnackbarManager by inject()
    private val cacheManager: CacheManager by inject()

    private val filesVM: FileBrowserViewModel by viewModel()
    private val tasksVM: TasksViewModel by viewModel()
    private val settingsVM: SettingsViewModel by viewModel()

    private fun getCurrentDirUri(browserState: FileBrowserState): Uri? {
        return browserState.currentLocation?.let { loc ->
            when (loc) {
                is BrowseLocation.SAF -> loc.uri
                is BrowseLocation.FileSystem -> Uri.fromFile(loc.file)
                else -> null
            }
        }
    }

    private fun parentDirUriOf(fileUri: Uri): Uri {
        val path = fileUri.path
        if (path.isNullOrEmpty()) return fileUri
        val cut = path.trimEnd('/').lastIndexOf('/')
        if (cut < 0) return fileUri
        val parentPath = path.trimEnd('/').substring(0, cut).ifEmpty { "/" }
        return fileUri.buildUpon().path(parentPath).build()
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
        if (storageAccessPolicy.hasStoragePermission()) {
            filesVM.onPermissionsChanged()
        } else {
            requestRegularStoragePermissions()
        }
    }

    private val showOverwriteDialog = mutableStateOf(false)
    private val overwriteMessage = mutableStateOf("")
    private var onOverwriteConfirm: (() -> Unit)? = null

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tasksVM.releaseClaim()

        inAppFolderPickerTitle.value = "Choose destination folder"

        pickerAction = intent?.action
        if (pickerAction != null && !isExternalPickerAction(pickerAction)) {
            AppLog.w("MainActivity", "ignoring non-picker action on MainActivity: $pickerAction")
            pickerAction = null
        }
        isTreePickMode = pickerAction == Intent.ACTION_OPEN_DOCUMENT_TREE
        isCreateDocumentMode = pickerAction == Intent.ACTION_CREATE_DOCUMENT
        isPickerMode = pickerAction in listOf(
            Intent.ACTION_GET_CONTENT,
            Intent.ACTION_OPEN_DOCUMENT,
            Intent.ACTION_OPEN_DOCUMENT_TREE,
            Intent.ACTION_CREATE_DOCUMENT
        )
        pickerMimeType = intent?.type
        createDocumentInitialName = intent?.getStringExtra(Intent.EXTRA_TITLE)
        if (isPickerMode) {
            filesVM.setPickerMode(true, pickerMimeType)
        }
        run {
            val initial: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI)
            }
            initial?.let { uri ->
                lifecycleScope.launch {
                    try {
                        when (uri.scheme) {
                            "file" -> {
                                val f = File(uri.path ?: return@launch)
                                if (f.exists()) {
                                    val target = if (f.isFile) f.parentFile ?: f else f
                                    filesVM.openFileSystemPath(target)
                                }
                            }
                            "content" -> {
                                val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                                    ?: runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                                if (docId != null) {
                                    val f = File(docId)
                                    if (f.exists() && f.isDirectory) {
                                        filesVM.openFileSystemPath(f)
                                        return@launch
                                    }
                                    if (f.exists()) {
                                        filesVM.openFileSystemPath(f.parentFile ?: f)
                                        return@launch
                                    }
                                }
                                filesVM.openDir(uri)
                            }
                            else -> filesVM.openDir(uri)
                        }
                    } catch (e: Exception) {
                        AppLog.w("MainActivity", "openDir failed: $uri", e)
                    }
                }
            }
        }

        handleViewIntent(intent)
        checkStoragePermissions()

        pickRoot = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            clearFolderPicker()
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
            val callback = pendingFolderCallback ?: ::handleTargetDirPicked
            clearFolderPicker()
            if (uri != null) {
                callback(uri)
            } else {
                tasksVM.releaseClaim()
            }
        }

        lifecycleScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                applyAppLocale(settings.language.languageTag)
            }
        }

        setContent {
            val s by settingsRepository.settingsFlow.collectAsState(initial = AppSettings())

            val dark = when (s.themeMode) {
                0 -> isSystemInDarkTheme()
                1 -> false
                else -> true
            }

            FluffyTheme(
                darkTheme = dark,
                dynamicColor = s.dynamicColor,
                useAuroraTheme = s.useAuroraTheme && !s.dynamicColor,
                oledBlack = s.oledBlack
            ) {
                val backStack = rememberNavBackStack(
                    ScreenKey.Files
                )

                val browserState by filesVM.state.collectAsState()
                val workInfos by tasksVM.workInfos.collectAsState()
                val queuedTasks by tasksVM.queuedTasks.collectAsState()
                val pendingRun by tasksVM.pendingRun.collectAsState()

                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                var seenFinished by rememberSaveable { mutableStateOf(emptySet<String>()) }
                LaunchedEffect(workInfos) {
                    var refreshNeeded = false
                    workInfos.forEach { wi ->
                        if (wi.state.isFinished && wi.id.toString() !in seenFinished) {
                            seenFinished = seenFinished + wi.id.toString()
                            refreshNeeded = true
                            getCurrentDirUri(browserState)?.let { DirectoryCounter.invalidate(it) }
                            DirectoryCounter.invalidateAll()
                        }
                    }
                    if (refreshNeeded) filesVM.refreshCurrentDir()
                }

                var seenRunning by rememberSaveable { mutableStateOf(emptySet<String>()) }
                LaunchedEffect(workInfos) {
                    val running = workInfos.filter { it.state == WorkInfo.State.RUNNING }
                    val newId = running.map { it.id.toString() }.firstOrNull { it !in seenRunning }
                    if (newId != null) {
                        seenRunning = seenRunning + running.map { it.id.toString() }.toSet()
                        showTaskCenter = true
                    }
                }

                LaunchedEffect(browserState.pendingAction) {
                    when (val action = browserState.pendingAction) {
                        is PendingAction.OpenFile -> {
                            val name = action.uri.lastPathSegment?.lowercase() ?: ""
                            if (FileSystemAccess.isArchiveFile(name)) {
                                backStack.add(ScreenKey.Archive(uri = action.uri.toString()))
                                filesVM.clearPendingAction()
                            } else {
                                lifecycleScope.launch {
                                    val display = io.queryDisplayName(action.uri)
                                    runCatching {
                                        openContent(
                                            src = action.uri,
                                            displayName = display,
                                            preferBuiltInViewers = s.preferBuiltInViewers,
                                            preferMime = s.preferContentResolverMime
                                        )
                                    }.onFailure {
                                        AppLog.w("MainActivity", "openContent failed", it)
                                    }
                                    filesVM.clearPendingAction()
                                }
                            }
                        }

                        is PendingAction.OpenArchive -> {
                            backStack.add(ScreenKey.Archive(uri = action.uri.toString()))
                            filesVM.clearPendingAction()
                        }

                        else -> Unit
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
                                        isPickerMode = isPickerMode && !isTreePickMode && !isCreateDocumentMode,
                                        onPickFile = { uri -> returnPickedFile(uri) },

                                        onPickRoot = { launchPickRootOrFallback(s.alwaysUseInAppFolderPicker) },

                                        // In-app folder picker (fallback)
                                        pickFolderMode = showInAppFolderPicker.value,
                                        pickFolderTitle = inAppFolderPickerTitle.value,
                                        onPickFolder = { folderUri ->
                                            val cb = pendingFolderCallback
                                            dismissInAppFolderPicker()
                                            cb?.invoke(folderUri)
                                        },
                                        onCancelPickFolder = {
                                            dismissInAppFolderPicker()
                                            tasksVM.releaseClaim()
                                        },

                                        // TV-friendly tree/document picker (DocumentsProvider)
                                        isTreePickMode = isTreePickMode || isCreateDocumentMode,
                                        isCreateDocumentMode = isCreateDocumentMode,
                                        createDocumentInitialName = createDocumentInitialName,
                                        onPickTreeFolder = { folderUri -> returnPickedTree(folderUri) },
                                        onCancelTreePick = { setResult(RESULT_CANCELED); finish() },
                                        onCreateDocumentConfirmed = { parentUri, name -> returnCreatedDocument(parentUri, name) },

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

                                        onCreateZip = zip@{ req ->
                                            if (s.taskMode) {
                                                addQueuedTask(
                                                    QueuedTask.CreateZip(
                                                        sources = req.sources.map { it.toString() },
                                                        outName = req.outName,
                                                        encryption = req.encryption.name,
                                                        comment = req.comment,
                                                        splitBytes = if (s.zipSplitEnabled) req.splitBytes else null,
                                                        hasPassword = !req.password.isNullOrBlank(),
                                                    )
                                                )
                                            } else {
                                                val target = req.targetDir
                                                    ?: getCurrentDirUri(filesVM.state.value)
                                                if (target == null) return@zip
                                                confirmShellWrite(target) {
                                                    tasksVM.enqueueCreateZip(
                                                        sources = req.sources,
                                                        targetDir = target,
                                                        outName = req.outName,
                                                        password = req.password,
                                                        overwrite = req.overwrite,
                                                        encryption = req.encryption.name,
                                                        comment = req.comment,
                                                        splitBytes = if (s.zipSplitEnabled) {
                                                            req.splitBytes ?: -1L
                                                        } else {
                                                            -1L
                                                        },
                                                    )
                                                    showTaskCenter = true
                                                }
                                            }
                                        },

                                        taskMode = s.taskMode,
                                        zipSplitEnabled = s.zipSplitEnabled,
                                        onZipSplitEnabledChange = { enabled ->
                                            lifecycleScope.launch {
                                                settingsRepository.updateSettings {
                                                    it.copy(zipSplitEnabled = enabled)
                                                }
                                            }
                                        },

                                        onCreate7z = sevenZ@{ sources, outName, password, targetDir, overwrite ->
                                            if (s.taskMode) {
                                                addQueuedTask(
                                                    QueuedTask.Create7z(
                                                        sources = sources.map { it.toString() },
                                                        outName = outName,
                                                        hasPassword = !password.isNullOrBlank(),
                                                    )
                                                )
                                            } else {
                                                val target = targetDir
                                                    ?: getCurrentDirUri(filesVM.state.value)
                                                    ?: return@sevenZ
                                                confirmShellWrite(target) {
                                                    tasksVM.enqueueCreate7z(
                                                        sources, target, outName, password, overwrite
                                                    )
                                                    showTaskCenter = true
                                                }
                                            }
                                        },

                                        onOpenSettings = {
                                            if (backStack.lastOrNull() != ScreenKey.Settings) {
                                                backStack.add(ScreenKey.Settings)
                                            }
                                        },
                                        onOpenTasks = { showTaskCenter = true },
                                        onAddBookmark = { name ->
                                            if (name.isNotBlank()) {
                                                filesVM.buildBookmarkFromCurrentLocation(name)?.let { bookmark ->
                                                    filesVM.addBookmark(bookmark)
                                                }
                                            }
                                        },

                                        onOpenArchive = { arch ->
                                            backStack.add(ScreenKey.Archive(uri = arch.toString()))
                                        },

                                        onOpenRecursiveSearch = { dirUri ->
                                            backStack.add(ScreenKey.Search(scope = dirUri.toString()))
                                        },

                                        onCopySelected = { list ->
                                            if (s.taskMode) {
                                                addQueuedTask(
                                                    QueuedTask.Copy(sources = list.map { it.toString() })
                                                )
                                            } else {
                                                pendingAction = PendingAction.Copy(list)
                                                launchPickTargetDirOrFallback(s.alwaysUseInAppFolderPicker)
                                            }
                                        },

                                        onMoveSelected = { list ->
                                            if (s.taskMode) {
                                                addQueuedTask(
                                                    QueuedTask.Move(sources = list.map { it.toString() })
                                                )
                                            } else {
                                                pendingAction = PendingAction.Move(list)
                                                launchPickTargetDirOrFallback(s.alwaysUseInAppFolderPicker)
                                            }
                                        },

                                        onDeleteSelected = { list ->
                                            lifecycleScope.launch {
                                                val ss = settingsRepository.settingsFlow.first()
                                                val touchesShell = list.any { it.scheme == "root" || it.scheme == "shizuku" }
                                                val proceed: () -> Unit = {
                                                    lifecycleScope.launch {
                                                        list.forEach {
                                                            io.deleteTree(it)
                                                            DirectoryCounter.invalidateParent(it)
                                                        }
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

                                        onRenameOne = { uri, newName, _ ->
                                            lifecycleScope.launch {
                                                if (newName.isNotBlank()) {
                                                    io.rename(uri, newName)
                                                    filesVM.refreshCurrentDir()
                                                }
                                            }
                                        },

                                        onOpenFile = { file -> filesVM.openFile(file) },

                                        onQuickAccessClick = { item ->
                                            lifecycleScope.launch {
                                                val uri = item.uri
                                                if (uri?.scheme == "shizuku") {
                                                    val ok = ShizukuAccess.ensurePermission()
                                                    if (!ok) return@launch
                                                }
                                                filesVM.openQuickAccessItem(item)
                                            }
                                        },
                                        onBookmarkClick = { bookmark ->
                                            lifecycleScope.launch {
                                                if (bookmark.access == "shizuku") {
                                                    val ok = ShizukuAccess.ensurePermission()
                                                    if (!ok) return@launch
                                                }
                                                filesVM.navigateToBookmark(bookmark)
                                            }
                                        },
                                        onRemoveBookmark = { bookmark ->
                                            filesVM.removeBookmark(bookmark)
                                        },
                                        customBookmarks = filesVM.customBookmarks.collectAsState().value,

                                        onRequestPermission = { requestStoragePermission() },
                                        onShowQuickAccess = { filesVM.showQuickAccess() },
                                        onCreateFolder = { name ->
                                            filesVM.createNewFolder(name)
                                            getCurrentDirUri(browserState)?.let { DirectoryCounter.invalidate(it) }
                                        },
                                        onCreateFile = { name ->
                                            filesVM.createNewFile(name)
                                            getCurrentDirUri(browserState)?.let { DirectoryCounter.invalidate(it) }
                                        },
                                        onPasteClipboard = { name ->
                                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                            filesVM.createFileFromClipboard(name, text)
                                            getCurrentDirUri(browserState)?.let { DirectoryCounter.invalidate(it) }
                                        },

                                        onShareSelected = { uris ->
                                            lifecycleScope.launch {
                                                val sources = uris.map { uri ->
                                                    val name = runCatching { io.queryDisplayName(uri) }
                                                        .getOrNull()?.takeIf { it.isNotBlank() }
                                                        ?: uri.lastPathSegment?.substringAfterLast('/')
                                                            ?.takeIf { it.isNotBlank() }
                                                        ?: "file"
                                                    uri to name
                                                }
                                                shareWithFolders(sources) { msg ->
                                                    snackbar.show(msg)
                                                }
                                            }
                                        },

                                        onOpenContent = { uri, name ->
                                            lifecycleScope.launch {
                                                openContent(
                                                    src = uri,
                                                    displayName = name.ifBlank {
                                                        uri.lastPathSegment ?: "file"
                                                    },
                                                    preferBuiltInViewers = s.preferBuiltInViewers,
                                                    preferMime = s.preferContentResolverMime,
                                                    forceChooser = false
                                                )
                                            }
                                        },

                                        onOpenWith = { uri, name ->
                                            lifecycleScope.launch {
                                                openContent(
                                                    src = uri,
                                                    displayName = name.ifBlank {
                                                        uri.lastPathSegment ?: "file"
                                                    },
                                                    preferBuiltInViewers = s.preferBuiltInViewers,
                                                    preferMime = s.preferContentResolverMime,
                                                    forceChooser = true
                                                )
                                            }
                                        },

                                        showFileCount = s.showFileCount,
                                        showStorageInfo = s.showStorageInfo,
                                        viewMode = s.viewMode,
                                        showThumbnails = s.showThumbnails,
                                        onViewModeChange = { mode ->
                                            lifecycleScope.launch {
                                                settingsRepository.updateSettings { it.copy(viewMode = mode) }
                                            }
                                        },
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
                                                pendingAction = PendingAction.Extract(
                                                    archive = arch,
                                                    password = pwd,
                                                    includePaths = null
                                                )
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
                                                pendingAction = PendingAction.Extract(
                                                    archive = arch,
                                                    password = pwd,
                                                    includePaths = paths
                                                )
                                                launchPickTargetDirOrFallback(s.alwaysUseInAppFolderPicker)
                                            }
                                        },
                                        onOpenAsFolder = { dirUri ->
                                            filesVM.openDir(dirUri)
                                            if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                                        }
                                    )
                                }

                                entry<ScreenKey.Search> { args ->
                                    val scopeUri = runCatching { args.scope.toUri() }.getOrNull()
                                    val searchVm: SearchViewModel = koinViewModel()
                                    LaunchedEffect(scopeUri) { scopeUri?.let(searchVm::setScope) }
                                    SearchScreen(
                                        scopeLabel = scopeUri?.path ?: args.scope,
                                        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                                        onOpenResult = { hit ->
                                            val target = if (hit.isDir) hit.uri else parentDirUriOf(hit.uri)
                                            val path = if (target.scheme == "file") target.path else null
                                            if (path != null) {
                                                filesVM.openFileSystemPath(File(path))
                                            } else {
                                                filesVM.openDir(target)
                                            }
                                            if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                                        },
                                        viewModel = searchVm
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
                            queuedTasks = queuedTasks,
                            currentDir = getCurrentDirUri(browserState),
                            onRunHere = ::runQueuedTaskHere,
                            onChooseFolder = { task ->
                                chooseTargetForQueuedTask(task, s.alwaysUseInAppFolderPicker)
                            },
                            onDeleteTask = { task -> tasksVM.removeTask(task.id) },
                            onClearQueue = { tasksVM.clearQueue() },
                            onCancel = { id -> tasksVM.cancel(id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                        )
                    }
                }

                pendingRun?.let { run: PendingTaskRun ->
                    RunTaskDialog(
                        task = run.task,
                        target = run.target,
                        onDismiss = {
                            tasksVM.dismissRun()
                            tasksVM.releaseClaim()
                        },
                        onRun = { password, overwrite ->
                            tasksVM.runTask(run.task, run.target, password, overwrite)
                            tasksVM.dismissRun()
                            tasksVM.releaseClaim()
                        }
                    )
                }

                if (showOverwriteDialog.value) {
                    ConfirmationDialog(
                        title = stringResource(R.string.confirm),
                        message = overwriteMessage.value,
                        onConfirm = {
                            showOverwriteDialog.value = false
                            onOverwriteConfirm?.invoke()
                            onOverwriteConfirm = null
                        },
                        onDismiss = {
                            showOverwriteDialog.value = false
                            onOverwriteConfirm = null
                            tasksVM.releaseClaim()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DirectoryCounter.invalidateAll()
        filesVM.refreshCurrentDir()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
        if (isExternalPickerAction(intent.action)) {
            AppLog.w("MainActivity", "forwarding picker intent to PickerActivity: ${intent.action}")
            runCatching {
                startActivity(Intent(intent).setClass(this, PickerActivity::class.java))
            }.onFailure {
                AppLog.w("MainActivity", "picker forward failed", it)
            }
        }
    }

    private fun isExternalPickerAction(action: String?): Boolean = action in listOf(
        Intent.ACTION_GET_CONTENT,
        Intent.ACTION_OPEN_DOCUMENT,
        Intent.ACTION_OPEN_DOCUMENT_TREE,
        Intent.ACTION_CREATE_DOCUMENT
    )

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

    private fun clearFolderPicker() {
        folderPickerActive = false
        pendingFolderCallback = null
    }

    private fun launchPickRootOrFallback(alwaysInApp: Boolean) {
        if (folderPickerActive) return
        folderPickerActive = true
        if (alwaysInApp) {
            showInAppFolderPicker.value = true
            inAppFolderPickerTitle.value = "Choose root folder"
            pendingFolderCallback = { uri -> filesVM.openRoot(uri) }
        } else {
            pickRoot.launch(null)
        }
    }

    private fun launchPickTargetDirOrFallback(
        alwaysInApp: Boolean,
        onPicked: ((Uri) -> Unit)? = null,
    ) {
        if (folderPickerActive) return
        folderPickerActive = true
        val callback = onPicked ?: ::handleTargetDirPicked
        if (alwaysInApp) {
            showInAppFolderPicker.value = true
            inAppFolderPickerTitle.value = "Choose destination folder"
            pendingFolderCallback = callback
        } else {
            pendingFolderCallback = callback
            pickTargetDir.launch(null)
        }
    }

    private fun persistSourceAccess(sources: List<Uri>) {
        sources.filter { it.scheme == "content" }.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
    }

    private fun localDocumentsAuthority(): String {
        val runtimeAuth = "${packageName}.documents"
        return if (packageManager.resolveContentProvider(runtimeAuth, 0) != null) {
            runtimeAuth
        } else {
            LocalDocumentsProvider.AUTHORITY
        }
    }

    private fun documentId(uri: Uri): String? =
        runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()

    private fun hasSourceAccess(uri: Uri): Boolean {
        if (uri.scheme != "content") return true
        if (uri.authority == localDocumentsAuthority()) return true
        val requestedId = documentId(uri) ?: return false
        return contentResolver.persistedUriPermissions.any { permission ->
            if (permission.uri.authority != uri.authority) return@any false
            if (!permission.isReadPermission) return@any false
            val grantedId = documentId(permission.uri) ?: return@any false
            grantedId == requestedId || requestedId.startsWith("$grantedId/")
        }
    }

    private fun addQueuedTask(task: QueuedTask) {
        persistSourceAccess(task.sources.map { Uri.parse(it) })
        lifecycleScope.launch {
            val result = tasksVM.addTask(task)
            snackbar.show(
                message = getString(
                    if (result == QueueAddResult.ADDED) R.string.task_added else R.string.task_updated
                ),
                actionLabel = getString(R.string.tasks),
                duration = SnackbarDuration.Long,
                onAction = { showTaskCenter = true },
            )
        }
    }

    private fun runQueuedTaskHere(task: QueuedTask) {
        if (!tasksVM.claimTask(task)) return
        showTaskCenter = false
        val dir = getCurrentDirUri(filesVM.state.value)
        if (dir == null) {
            tasksVM.releaseClaim()
            snackbar.show(getString(R.string.no_folder_for_task))
            return
        }
        runQueuedTask(task, dir)
    }

    private fun chooseTargetForQueuedTask(task: QueuedTask, alwaysInApp: Boolean) {
        if (folderPickerActive) return
        if (!tasksVM.claimTask(task)) return
        showTaskCenter = false
        launchPickTargetDirOrFallback(alwaysInApp) { target ->
            runQueuedTask(task, target)
        }
    }

    private fun runQueuedTask(task: QueuedTask, target: Uri) {
        if (!tasksVM.isClaimed(task)) return
        val sources = task.sources.map { Uri.parse(it) }
        if (sources.any { !hasSourceAccess(it) }) {
            tasksVM.releaseClaim()
            snackbar.show(getString(R.string.source_permission_expired), duration = SnackbarDuration.Long)
            return
        }
        if (task is QueuedTask.Copy && sources.any { parentDirUriOf(it) == target }) {
            tasksVM.releaseClaim()
            snackbar.show(getString(R.string.same_folder_copy_warning), duration = SnackbarDuration.Long)
            return
        }
        confirmShellWrite(target, sources) {
            tasksVM.requestRun(task, target)
        }
    }

    private fun dismissInAppFolderPicker() {
        showInAppFolderPicker.value = false
        clearFolderPicker()
    }

    private fun handleTargetDirPicked(target: Uri) {
        when (val action = pendingAction) {
            is PendingAction.Copy -> {
                pendingAction = PendingAction.None
                confirmShellWrite(target) {
                    tasksVM.enqueueCopy(action.uris, target, overwrite = false)
                }
            }

            is PendingAction.Move -> {
                pendingAction = PendingAction.None
                confirmShellWrite(target) {
                    tasksVM.enqueueMove(action.uris, target, overwrite = false)
                }
            }

            is PendingAction.Extract -> {
                pendingAction = PendingAction.None
                extractWithConfirm(
                    archive = action.archive,
                    password = action.password,
                    includePaths = action.includePaths,
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

            is OpenTarget.EditText -> {
                applicationContext.launchTextEditor(target.uri, target.title)
            }

            OpenTarget.None -> {
                // nein
            }
        }
    }


    private fun returnPickedTree(folderUri: Uri) {
        AppLog.w("MainActivity", "returnPickedTree called on MainActivity; ignoring: $folderUri")
        setResult(RESULT_CANCELED); finish()
    }

    private fun returnCreatedDocument(parentUri: Uri, displayName: String) {
        AppLog.w("MainActivity", "returnCreatedDocument called on MainActivity; ignoring")
        setResult(RESULT_CANCELED); finish()
    }

    private fun returnPickedFile(uri: Uri) {
        AppLog.w("MainActivity", "returnPickedFile called on MainActivity; ignoring: $uri")
        setResult(RESULT_CANCELED); finish()
    }

    private fun checkStoragePermissions() {
        if (!storageAccessPolicy.hasStoragePermission()) {
            requestStoragePermission()
        }
    }

    private fun requestStoragePermission() {
        if (storageAccessPolicy.shouldUseManageStorageFlow()) {
            requestManageStoragePermission()
        } else {
            requestRegularStoragePermissions()
        }
    }

    private fun requestRegularStoragePermissions() {
        val permissions = storageAccessPolicy.missingRegularPermissions()
        if (permissions.isNotEmpty()) {
            storagePermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun requestManageStoragePermission() {
        val intent = storageAccessPolicy.createManageStorageIntent()
        if (intent != null) {
            manageStoragePermissionLauncher.launch(intent)
        } else {
            requestRegularStoragePermissions()
        }
    }

    private fun confirmShellWrite(
        target: Uri,
        sources: List<Uri> = emptyList(),
        proceed: () -> Unit,
    ) {
        lifecycleScope.launch {
            val s = settingsRepository.settingsFlow.first()
            val schemes = (listOf(target) + sources).mapNotNull { it.scheme }.toSet()
            val needsWarn = s.warnBeforeShellWrites &&
                schemes.any { it == "root" || it == "shizuku" }
            if (needsWarn) {
                overwriteMessage.value =
                    "You're about to use elevated (root/shizuku) access. This can modify system files. Continue?"
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
        withContext(Dispatchers.IO) {
            uris.map { u ->
                if (u.scheme == "file") return@map u
                if (u.scheme == "root" || u.scheme == "shizuku") return@map u

                if (u.scheme == "content") {
                    val name = sanitizeName(io.queryDisplayName(u))
                    val out = cacheManager.tempFile(
                        CacheManager.Area.Stage, "incoming_", "_$name"
                    )
                    runCatching {
                        io.openIn(u).use { input ->
                            out.outputStream().use { input.copyTo(it) }
                        }
                        return@map Uri.fromFile(out)
                    }.getOrElse {
                        runCatching { out.delete() }
                        return@map u
                    }
                }

                u
            }
        }

    private fun handleSharedUris(uris: List<Uri>) {
        lifecycleScope.launch {
            val staged = stageSharedIfNeeded(uris)
            pendingAction = PendingAction.Copy(staged)
            val s = settingsRepository.settingsFlow.first()
            launchPickTargetDirOrFallback(s.alwaysUseInAppFolderPicker)
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
    val showProgressBar = when (wi.state) {
        WorkInfo.State.SUCCEEDED -> false
        WorkInfo.State.FAILED -> false
        WorkInfo.State.CANCELLED -> true
        else -> true
    }

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
                    HintIconButton(
                        hint = stringResource(R.string.details),
                        icon = Icons.Default.Info,
                        onClick = onOpenTasks,
                    )
                }
                if (showProgressBar) {
                    if (progress != null) {
                        LinearWavyProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                        )
                    } else {
                        LinearWavyProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskCenterSheet(
    workInfos: List<WorkInfo>,
    queuedTasks: List<QueuedTask>,
    currentDir: Uri?,
    onRunHere: (QueuedTask) -> Unit,
    onChooseFolder: (QueuedTask) -> Unit,
    onDeleteTask: (QueuedTask) -> Unit,
    onClearQueue: () -> Unit,
    onCancel: (UUID) -> Unit,
    modifier: Modifier = Modifier
) {
    val active = workInfos.filter { !it.state.isFinished }
    val finished = workInfos.filter { it.state.isFinished }.takeLast(10)

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            stringResource(R.string.task_center),
            style = MaterialTheme.typography.titleMedium
        )

        if (queuedTasks.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.queued, queuedTasks.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                HintIconButton(
                    hint = stringResource(R.string.clear_queue),
                    icon = Icons.Default.ClearAll,
                    onClick = onClearQueue,
                )
            }
            queuedTasks.forEach { task ->
                QueuedTaskRow(
                    task = task,
                    currentDir = currentDir,
                    onRunHere = onRunHere,
                    onChooseFolder = onChooseFolder,
                    onDeleteTask = onDeleteTask,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        if (queuedTasks.isEmpty() && active.isEmpty() && finished.isEmpty()) {
            Text(
                stringResource(R.string.no_tasks),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (active.isNotEmpty()) {
            Text(
                stringResource(R.string.in_progress),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            active.forEach { wi -> TaskRowCompact(wi, onCancel = onCancel) }
        }

        if (finished.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.recent),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            finished.asReversed().forEach { wi -> TaskRowCompact(wi, onCancel = null) }
        }
    }
}

@Composable
private fun HintIconButton(
    hint: String,
    icon: ImageVector,
    onClick: () -> Unit,
    tint: Color = LocalContentColor.current,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            TooltipAnchorPosition.Above
        ),
        tooltip = { PlainTooltip { Text(hint) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = hint, tint = tint)
        }
    }
}

@Composable
private fun QueuedTaskTitle(task: QueuedTask): String {
    val label = when (task) {
        is QueuedTask.Copy -> R.string.queued_copy
        is QueuedTask.Move -> R.string.queued_move
        is QueuedTask.CreateZip -> R.string.queued_create_zip
        is QueuedTask.Create7z -> R.string.queued_create_7z
    }
    return stringResource(label, task.sources.size)
}

private fun sourceDisplayName(source: String): String {
    val uri = Uri.parse(source)
    var name = uri.lastPathSegment ?: source
    if (uri.scheme == "content") {
        name = name.substringAfterLast(':').substringAfterLast('/')
    }
    return name.ifBlank { source }
}

private fun QueuedTask.runIcon(): ImageVector = when (this) {
    is QueuedTask.Copy, is QueuedTask.Move -> Icons.Default.ContentPaste
    is QueuedTask.CreateZip, is QueuedTask.Create7z -> Icons.Default.Archive
}

private fun QueuedTask.detailLine(): String {
    val names = sources.map(::sourceDisplayName)
    val shown = names.take(3).joinToString(", ")
    val more = (names.size - 3).let { if (it > 0) " +$it" else "" }
    val items = shown + more
    return when (this) {
        is QueuedTask.Copy, is QueuedTask.Move -> items
        is QueuedTask.CreateZip -> "$outName: $items"
        is QueuedTask.Create7z -> "$outName: $items"
    }
}

@Composable
private fun QueuedTaskRow(
    task: QueuedTask,
    currentDir: Uri?,
    onRunHere: (QueuedTask) -> Unit,
    onChooseFolder: (QueuedTask) -> Unit,
    onDeleteTask: (QueuedTask) -> Unit,
) {
    val runHint = stringResource(
        when (task) {
            is QueuedTask.Copy, is QueuedTask.Move -> R.string.paste_here
            is QueuedTask.CreateZip, is QueuedTask.Create7z -> R.string.create_here
        }
    )
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(QueuedTaskTitle(task), style = MaterialTheme.typography.bodyLarge)
            Text(
                task.detailLine(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.task_destination,
                    currentDir?.path ?: stringResource(R.string.no_folder_for_task)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                HintIconButton(
                    hint = runHint,
                    icon = task.runIcon(),
                    onClick = { onRunHere(task) },
                )
                HintIconButton(
                    hint = stringResource(R.string.choose_folder),
                    icon = Icons.Default.FolderOpen,
                    onClick = { onChooseFolder(task) },
                )
                HintIconButton(
                    hint = stringResource(R.string.delete),
                    icon = Icons.Default.Delete,
                    onClick = { onDeleteTask(task) },
                )
            }
        }
    }
}

private fun QueuedTask.needsPassword(): Boolean = when (this) {
    is QueuedTask.Copy, is QueuedTask.Move -> false
    is QueuedTask.CreateZip -> hasPassword
    is QueuedTask.Create7z -> hasPassword
}

@Composable
private fun RunTaskDialog(
    task: QueuedTask,
    target: Uri,
    onDismiss: () -> Unit,
    onRun: (String?, Boolean) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var overwrite by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.run_task)) },
        text = {
            Column {
                Text(stringResource(R.string.task_destination, target.path ?: target.toString()))
                if (task.needsPassword()) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.password_required)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = overwrite, onCheckedChange = { overwrite = it })
                    Text(stringResource(R.string.replace_existing))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRun(password.ifBlank { null }, overwrite) }) {
                Text(stringResource(R.string.run_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun TaskRowCompact(
    wi: WorkInfo,
    onCancel: ((UUID) -> Unit)?
) {
    val title = remember(wi.tags) { friendlyTitle(wi) }
    val progress = wi.progress.getFloat("progress", -1f).takeIf { it in 0f..1f }
    val isRunning = wi.state == WorkInfo.State.RUNNING
    val showProgressBar = when (wi.state) {
        WorkInfo.State.SUCCEEDED -> false
        WorkInfo.State.FAILED -> false
        WorkInfo.State.CANCELLED -> true
        else -> true
    }

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
                    HintIconButton(
                        hint = stringResource(R.string.cancel_task),
                        icon = Icons.Default.Stop,
                        onClick = { onCancel(wi.id) },
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (showProgressBar) {
                if (progress != null) {
                    LinearWavyProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                } else {
                    LinearWavyProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
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
