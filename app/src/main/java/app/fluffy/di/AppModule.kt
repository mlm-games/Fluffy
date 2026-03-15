package app.fluffy.di

import androidx.work.WorkManager
import app.fluffy.archive.ArchiveEngine
import app.fluffy.archive.DefaultArchiveEngine
import app.fluffy.data.repository.BookmarksRepository
import app.fluffy.data.repository.SettingsRepository
import app.fluffy.io.FileSystemAccess
import app.fluffy.io.SafIo
import app.fluffy.io.ShellIo
import app.fluffy.operations.ArchiveJobManager
import app.fluffy.platform.StorageAccessPolicy
import app.fluffy.shell.RootAccess
import app.fluffy.shell.RootBackend
import app.fluffy.shell.ShizukuAccess
import app.fluffy.shell.ShizukuBackend
import app.fluffy.ui.components.snackbar.SnackbarManager
import app.fluffy.viewmodel.FileBrowserViewModel
import app.fluffy.viewmodel.SettingsViewModel
import app.fluffy.viewmodel.TasksViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { StorageAccessPolicy(androidContext()) }

    single { SettingsRepository(androidContext()) }
    single { BookmarksRepository(androidContext()) }

    single { RootAccess() }
    single { ShizukuAccess() }
    single { RootBackend(get()) }
    single { ShizukuBackend(get()) }
    single { ShellIo(get(), get()) }

    single { FileSystemAccess(androidContext(), get()) }
    single { SafIo(androidContext(), get(), get()) }

    single<ArchiveEngine> { DefaultArchiveEngine(androidContext(), get()) }

    single { WorkManager.getInstance(androidContext()) }
    single { ArchiveJobManager(get()) }

    single { SnackbarManager() }

    viewModel { FileBrowserViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { TasksViewModel(get(), get()) }
    viewModel { SettingsViewModel(get()) }
}
