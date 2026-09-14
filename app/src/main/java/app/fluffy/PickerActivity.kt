package app.fluffy

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import app.fluffy.data.repository.AppSettings
import app.fluffy.data.repository.SettingsRepository
import app.fluffy.helper.DeviceUtils
import app.fluffy.helper.exportForOpenWith
import app.fluffy.io.FileSystemAccess
import app.fluffy.io.SafIo
import app.fluffy.platform.StorageAccessPolicy
import app.fluffy.provider.LocalDocumentsProvider
import app.fluffy.ui.components.snackbar.LauncherSnackbarHost
import app.fluffy.ui.components.snackbar.SnackbarManager
import app.fluffy.ui.screens.FileBrowserScreen
import app.fluffy.ui.theme.FluffyTheme
import app.fluffy.util.AppLog
import app.fluffy.viewmodel.FileBrowserViewModel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.io.File

class PickerActivity : ComponentActivity() {

    private lateinit var filesVM: FileBrowserViewModel

    private val io: SafIo by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val storageAccessPolicy: StorageAccessPolicy by inject()
    private val snackbar: SnackbarManager by inject()

    private var pickerAction: String? = null
    private var pickerMimeType: String? = null
    private var createDocumentInitialName: String? = null

    private val isTreePickMode get() = pickerAction == Intent.ACTION_OPEN_DOCUMENT_TREE
    private val isCreateDocumentMode get() = pickerAction == Intent.ACTION_CREATE_DOCUMENT
    private val isFilePickMode
        get() = pickerAction == Intent.ACTION_GET_CONTENT ||
            pickerAction == Intent.ACTION_OPEN_DOCUMENT

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filesVM = ViewModelProvider(this, FileBrowserViewModelFactory)[FileBrowserViewModel::class.java]
        readPickerIntent(intent)
        if (pickerAction == null) {
            AppLog.w("PickerActivity", "launched without a picker action; finishing canceled")
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        filesVM.setPickerMode(true, pickerMimeType)
        openInitialUri(intent)

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
                val browserState by filesVM.state.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }

                Scaffold(
                    snackbarHost = {
                        LauncherSnackbarHost(
                            hostState = snackbarHostState,
                            manager = snackbar
                        )
                    }
                ) {
                    FileBrowserScreen(
                        state = browserState,
                        isPickerMode = isFilePickMode,
                        onPickFile = { uri -> returnPickedFile(uri) },
                        onPickRoot = { /* picker hosts have no internal root flow */ },
                        isTreePickMode = isTreePickMode || isCreateDocumentMode,
                        isCreateDocumentMode = isCreateDocumentMode,
                        createDocumentInitialName = createDocumentInitialName,
                        onPickTreeFolder = { folderUri -> returnPickedTree(folderUri) },
                        onCancelTreePick = { setResult(RESULT_CANCELED); finish() },
                        onCreateDocumentConfirmed = { parentUri, name ->
                            returnCreatedDocument(parentUri, name)
                        },
                        onOpenDir = { filesVM.openDir(it) },
                        onBack = {
                            if (!filesVM.goUp()) {
                                setResult(RESULT_CANCELED); finish()
                            }
                        },
                        onExtractArchive = { _, _ -> snackbar.show("Not available in picker mode") },
                        onCreateZip = { _, _, _, _ -> snackbar.show("Not available in picker mode") },
                        onCreate7z = { _, _, _, _, _ -> snackbar.show("Not available in picker mode") },
                        onOpenSettings = { /* hidden in picker mode */ },
                        onOpenTasks = { /* hidden in picker mode */ },
                        onOpenArchive = { snackbar.show("Open the archive first, then pick") },
                        onCopySelected = { },
                        onMoveSelected = { },
                        onDeleteSelected = { },
                        onShareSelected = { },
                        onPasteClipboard = { },
                        onRenameOne = { _, _, _ -> },
                        onOpenFile = { file -> filesVM.openFile(file) },
                        onQuickAccessClick = { item -> filesVM.openQuickAccessItem(item) },
                        onBookmarkClick = { bookmark -> filesVM.navigateToBookmark(bookmark) },
                        onRemoveBookmark = { },
                        onOpenContent = { _, _ -> },
                        onOpenWith = { _, _ -> },
                        onRequestPermission = { requestStoragePermission() },
                        onShowQuickAccess = { filesVM.showQuickAccess() },
                        onCreateFolder = { name ->
                            filesVM.createNewFolder(name)
                        },
                        onCreateFile = { name -> filesVM.createNewFile(name) },
                        showFileCount = false,
                        showStorageInfo = false,
                        viewMode = s.viewMode,
                        showThumbnails = s.showThumbnails,
                        onViewModeChange = { mode ->
                            lifecycleScope.launch {
                                settingsRepository.updateSettings { it.copy(viewMode = mode) }
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readPickerIntent(intent)
        filesVM.setPickerMode(true, pickerMimeType)
        openInitialUri(intent)
    }

    private fun readPickerIntent(intent: Intent?) {
        val action = intent?.action
        pickerAction = when (action) {
            Intent.ACTION_GET_CONTENT,
            Intent.ACTION_OPEN_DOCUMENT,
            Intent.ACTION_OPEN_DOCUMENT_TREE,
            Intent.ACTION_CREATE_DOCUMENT -> action
            else -> null
        }
        pickerMimeType = intent?.type
            ?: intent?.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.firstOrNull()
        createDocumentInitialName = intent?.getStringExtra(Intent.EXTRA_TITLE)
        AppLog.d(
            "PickerActivity",
            "action=$pickerAction mime=$pickerMimeType " +
                "initialName=$createDocumentInitialName tv=${DeviceUtils.isTV(this)}"
        )
    }

    private fun openInitialUri(intent: Intent?) {
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
                    AppLog.w("PickerActivity", "openDir failed: $uri", e)
                }
            }
        }
    }


    private fun authorityForResult(): String {
        val runtimeAuth = "${packageName}.documents"
        val info = packageManager.resolveContentProvider(runtimeAuth, 0)
        return if (info != null) runtimeAuth else LocalDocumentsProvider.AUTHORITY
    }

    /** Document IDs must be relative (no leading '/'): buildDocumentUri rejects empty segments. */
    private fun qualifyDocId(raw: String?): String? {
        val trimmed = raw?.trim()?.trimStart('/')?.trimEnd('/') ?: return null
        return trimmed.ifBlank { null }
    }

    private fun uriToDocumentId(uri: Uri): String? = when (uri.scheme) {
        "file" -> qualifyDocId(uri.path)
        "content" -> qualifyDocId(
            runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                ?: runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                ?: uri.path
        )
        else -> if (uri.scheme == "root" || uri.scheme == "shizuku") {
            null
        } else {
            uri.path ?: uri.toString()
        }
    }

    private fun resultGrantFlags() =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    private fun directGrantFlags() =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    private fun bestEffortDirectGrant(pkg: String?, uri: Uri) {
        if (pkg == null) return
        try {
            grantUriPermission(pkg, uri, directGrantFlags())
        } catch (e: Exception) {
            AppLog.d("PickerActivity", "grantUriPermission failed: $pkg $uri", e)
        }
    }

    private fun clipForUri(label: String, uri: Uri): ClipData = runCatching {
        ClipData.newUri(contentResolver, label, uri)
    }.getOrElse {
        AppLog.d("PickerActivity", "clip mime lookup failed, using plain item: $uri", it)
        ClipData(label, arrayOf("text/uri-list"), ClipData.Item(uri))
    }

    private fun returnPickedTree(folderUri: Uri) {
        if (folderUri.scheme == "root" || folderUri.scheme == "shizuku") {
            AppLog.w("PickerActivity", "refusing to return shell uri as tree: $folderUri")
            snackbar.show("That location can't be shared with other apps")
            return
        }
        val docId = uriToDocumentId(folderUri) ?: run {
            setResult(RESULT_CANCELED); finish(); return
        }
        val file = File("/$docId")
        if (!file.exists() || !file.isDirectory) {
            if (folderUri.scheme == "content") {
                val result = Intent().apply {
                    data = folderUri
                    addFlags(resultGrantFlags())
                    clipData = clipForUri("tree", folderUri)
                }
                bestEffortDirectGrant(callingPackage, folderUri)
                setResult(RESULT_OK, result)
                finish()
                return
            }
            setResult(RESULT_CANCELED); finish(); return
        }
        // Must be under the provider's storage roots, otherwise the caller's
        // follow-up queryChildDocuments/openDocument hits "Outside storage roots".
        // (Can't self-query here: MANAGE_DOCUMENTS blocks our own client access.)
        val servable = runCatching {
            val roots = LocalDocumentsProvider.rootsForCheck(this@PickerActivity)
            val canon = File("/$docId").canonicalPath
            roots.any { r -> canon == r || canon.startsWith("$r/") }
        }.getOrDefault(false)
        if (!servable) {
            AppLog.w("PickerActivity", "tree outside provider roots: $docId")
            snackbar.show("That folder can't be shared with other apps")
            return
        }
        val treeUri = LocalDocumentsProvider.treeUri(docId, authorityForResult())
        val result = Intent().apply {
            data = treeUri
            addFlags(resultGrantFlags())
            clipData = clipForUri("tree", treeUri)
        }
        bestEffortDirectGrant(callingPackage, treeUri)
        setResult(RESULT_OK, result)
        finish()
    }

    private fun returnCreatedDocument(parentUri: Uri, displayName: String) {
        if (displayName.isBlank() || displayName == "." || displayName == ".." ||
            '/' in displayName || '\\' in displayName || '\u0000' in displayName
        ) {
            setResult(RESULT_CANCELED); finish(); return
        }
        if (parentUri.scheme == "root" || parentUri.scheme == "shizuku") {
            AppLog.w("PickerActivity", "refusing CREATE_DOCUMENT under shell uri: $parentUri")
            snackbar.show("That location can't be shared with other apps")
            return
        }
        val parentId = uriToDocumentId(parentUri) ?: run {
            setResult(RESULT_CANCELED); finish(); return
        }
        val parent = File("/$parentId")
        if (!parent.isDirectory || !parent.canWrite()) {
            setResult(RESULT_CANCELED); finish(); return
        }
        val canonParent = runCatching { parent.canonicalPath }.getOrNull() ?: run {
            setResult(RESULT_CANCELED); finish(); return
        }
        val target = File(parent, displayName)
        val canonTarget = runCatching { target.canonicalPath }.getOrNull() ?: run {
            setResult(RESULT_CANCELED); finish(); return
        }
        if (canonTarget != canonParent && !canonTarget.startsWith("$canonParent/")) {
            setResult(RESULT_CANCELED); finish(); return
        }
        val created = try {
            synchronized(this) {
                if (!target.exists() && !target.createNewFile()) return@synchronized false
                target.isFile
            }
        } catch (e: Exception) {
            AppLog.w("PickerActivity", "createNewFile failed: ${target.absolutePath}", e)
            false
        }
        if (!created) {
            setResult(RESULT_CANCELED); finish(); return
        }
        val createdDocId = qualifyDocId(target.absolutePath) ?: run {
            setResult(RESULT_CANCELED); finish(); return
        }
        val docUri = LocalDocumentsProvider.docUri(createdDocId, authorityForResult())
        val mime = FileSystemAccess.getMimeType(displayName)
        val result = Intent().apply {
            data = docUri
            type = mime
            addFlags(resultGrantFlags())
            clipData = clipForUri("doc", docUri)
        }
        bestEffortDirectGrant(callingPackage, docUri)
        setResult(RESULT_OK, result)
        finish()
    }

    private fun returnPickedFile(uri: Uri) {
        lifecycleScope.launch {
            val wantWrite = pickerAction == Intent.ACTION_OPEN_DOCUMENT
            val shareable: Uri = when {
                uri.scheme == "root" || uri.scheme == "shizuku" -> {
                    val staged = runCatching {
                        applicationContext.exportForOpenWith(uri, io.queryDisplayName(uri))
                    }.getOrNull()
                    if (staged == null) {
                        setResult(RESULT_CANCELED); finish(); return@launch
                    }
                    staged
                }
                uri.scheme == "content" -> uri
                uri.scheme == "file" -> {
                    val docId = uriToDocumentId(uri)
                    val backing = docId?.let { File("/$it") }
                    if (docId != null && backing != null && backing.exists() && backing.isFile) {
                        LocalDocumentsProvider.docUri(docId, authorityForResult())
                    } else {
                        runCatching {
                            applicationContext.exportForOpenWith(uri, io.queryDisplayName(uri))
                        }.getOrElse { uri }
                    }
                }
                else -> uri
            }

            val mime = runCatching { contentResolver.getType(shareable) }.getOrNull()
                ?: runCatching { FileSystemAccess.getMimeType(io.queryDisplayName(shareable)) }.getOrNull()
                ?: "application/octet-stream"

            var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            if (wantWrite) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

            val resultIntent = Intent().apply {
                data = shareable
                type = mime
                clipData = clipForUri("picked", shareable)
                addFlags(flags)
            }
            bestEffortDirectGrant(callingPackage, shareable)
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun requestStoragePermission() {
        filesVM.onPermissionsChanged()
        if (!storageAccessPolicy.hasStoragePermission()) {
            snackbar.show("Storage permission needed to browse all folders")
        }
    }
}
