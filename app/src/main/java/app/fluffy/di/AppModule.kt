package app.fluffy.di

import app.fluffy.AppGraph
import app.fluffy.archive.ArchiveEngine
import app.fluffy.ui.components.snackbar.SnackbarManager
import app.fluffy.viewmodel.FileBrowserViewModel
import app.fluffy.viewmodel.SettingsViewModel
import app.fluffy.viewmodel.TasksViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {


    single { AppGraph.settings }
    single { AppGraph.io }
    single { AppGraph.fileSystemAccess }
    single<ArchiveEngine> { AppGraph.archive }
    single { AppGraph.archiveJobs }

    single { SnackbarManager() }


    viewModel { FileBrowserViewModel(get(), get(), get(), get()) }
    viewModel { TasksViewModel(androidContext(), get()) }
    viewModel { SettingsViewModel(get()) }
}