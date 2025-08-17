package app.fluffy

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import app.fluffy.data.repository.AppSettings
import app.fluffy.helper.DeviceUtils
import app.fluffy.ui.screens.ArchiveViewerScreen
import app.fluffy.ui.screens.FileBrowserScreen
import app.fluffy.ui.screens.SettingsScreen
import app.fluffy.ui.screens.TasksScreen
import app.fluffy.ui.screens.TvMainScreen
import app.fluffy.ui.theme.FluffyTheme
import app.fluffy.viewmodel.FileBrowserViewModel
import app.fluffy.viewmodel.SettingsViewModel
import app.fluffy.viewmodel.TasksViewModel
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private inline fun <reified T : ViewModel> vm(crossinline create: () -> T) =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
        }

    private val filesVM: FileBrowserViewModel by viewModels {
        vm { FileBrowserViewModel(AppGraph.io, AppGraph.archive, AppGraph.settings) }
    }
    private val tasksVM: TasksViewModel by viewModels {
        vm { TasksViewModel(this@MainActivity, AppGraph.archiveJobs) }
    }
    // Tiny wrapper: provide SettingsViewModel to SettingsScreen
    private val settingsVM: SettingsViewModel by viewModels {
        vm { SettingsViewModel(AppGraph.settings) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(applicationContext)

        // FIX: use the contract constructor: OpenDocumentTree()
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

        // FIX: same here — call the constructor
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

            val dark = when (settings.themeMode) {
                0 -> isSystemInDarkTheme()
                1 -> false
                else -> true
            }
            val isTV = DeviceUtils.isTV(this)

            FluffyTheme(darkTheme = dark) {
                Surface {
                    val nav = rememberNavController()
                    val currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route

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
                                            filesVM.state.value.currentDir?.let { filesVM.openDir(it) }
                                        }
                                    },
                                    onRenameOne = { uri, newName ->
                                        lifecycleScope.launch {
                                            if (newName.isNotBlank()) AppGraph.io.rename(uri, newName)
                                            filesVM.state.value.currentDir?.let { filesVM.openDir(it) }
                                        }
                                    },
                                    onCreate7z = { sources, outName, password, targetDir ->
                                        tasksVM.enqueueCreate7z(sources, targetDir, outName, password)
                                        nav.navigate("tasks")
                                    }
                                )
                            }
                            composable(
                                route = "archive?uri={uri}",
                                arguments = listOf(navArgument("uri") { type = NavType.StringType; nullable = false })
                            ) { backStack ->
                                val encoded = backStack.arguments?.getString("uri") ?: ""
                                val uri = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()).toUri()
                                ArchiveViewerScreen(
                                    archiveUri = uri,
                                    onBack = { nav.popBackStack() },
                                    onExtractTo = { arch, pwd ->
                                        pendingExtractArchive = arch
                                        pendingExtractPassword = pwd
                                        pendingExtractPaths = null
                                        pickTargetDir.launch(null)
                                    },
                                    onExtractSelected = { arch, paths, pwd ->
                                        pendingExtractArchive = arch
                                        pendingExtractPassword = pwd
                                        pendingExtractPaths = paths
                                        pickTargetDir.launch(null)
                                    },
                                    onOpenAsFolder = { dirUri ->
                                        filesVM.openDir(dirUri)
                                        nav.popBackStack()
                                        nav.navigate("files")
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
            }
        }
    }
}