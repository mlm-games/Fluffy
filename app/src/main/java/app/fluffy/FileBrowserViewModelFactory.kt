package app.fluffy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.fluffy.viewmodel.FileBrowserViewModel
import org.koin.core.context.GlobalContext

object FileBrowserViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(FileBrowserViewModel::class.java)) {
            throw IllegalArgumentException("Unknown ViewModel: $modelClass")
        }
        val koin = GlobalContext.getOrNull()
            ?: throw IllegalStateException("Koin not started; PickerActivity requires Application.onCreate first")
        return koin.get<FileBrowserViewModel>() as T
    }
}
