package app.fluffy

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.fluffy.ui.screens.FileBrowserScreen
import app.fluffy.ui.screens.SettingsScreen
import app.fluffy.ui.screens.TasksScreen
import app.fluffy.viewmodel.FileBrowserViewModel
import app.fluffy.viewmodel.TasksViewModel

class MainActivity : ComponentActivity() {

    private inline fun <reified T : ViewModel> vm(crossinline create: () -> T) =
        object : ViewModelProvider.Factory {
            override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
        }

    private val filesVM: FileBrowserViewModel by viewModels {
        vm { FileBrowserViewModel(AppGraph.io, AppGraph.archive) }
    }

    private val tasksVM: TasksViewModel by viewModels {
        vm { TasksViewModel(this@MainActivity, AppGraph.archiveJobs) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(applicationContext)

        val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree) { uri ->
            uri?.let {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                filesVM.openRoot(it)
            }
        }

        setContent {
            val dark = isSystemInDarkTheme()
            Surface(color = MaterialTheme.colorScheme.background) {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "files") {
                    composable("files") {
                        FileBrowserScreen(
                            state = filesVM.state.collectAsState().value,
                            onPickRoot = { pickTree.launch(null) },
                            onOpenDir = { filesVM.openDir(it) },
                            onBack = { filesVM.goUp() },
                            onExtractArchive = { uri, target ->
                                tasksVM.enqueueExtract(uri, target, password = null)
                                nav.navigate("tasks")
                            },
                            onCreateZip = { sources, targetName ->
                                tasksVM.enqueueCreateZip(sources, targetName)
                                nav.navigate("tasks")
                            },
                            onOpenSettings = { nav.navigate("settings") },
                            onOpenTasks = { nav.navigate("tasks") }
                        )
                    }
                    composable("tasks") {
                        TasksScreen(
                            workInfos = tasksVM.workInfos.collectAsState().value,
                            onBack = { nav.popBackStack() }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            settings = AppGraph.settings,
                            onBack = { nav.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}