package app.fluffy.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.fluffy.operations.ArchiveJobManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class TasksViewModel(
    private val application: Application,
    private val jobs: ArchiveJobManager
) : ViewModel() {

    private val _workInfos = MutableStateFlow<List<WorkInfo>>(emptyList())
    val workInfos: StateFlow<List<WorkInfo>> = _workInfos

    private val liveData = WorkManager.getInstance(application).getWorkInfosByTagLiveData(ArchiveJobManager.TAG_ALL)
    private val observer = Observer<List<WorkInfo>?> { list ->
        _workInfos.value = list ?: emptyList()
    }

    init {
        liveData.observeForever(observer)
    }

    override fun onCleared() {
        liveData.removeObserver(observer)
        super.onCleared()
    }

    fun enqueueExtract(archive: Uri, targetDir: Uri, password: String?, includePaths: List<String>? = null) {
        jobs.enqueueExtract(archive, targetDir, password, includePaths)
    }

    fun enqueueCreateZip(sources: List<Uri>, targetDir: Uri, outName: String, overwrite: Boolean = false) {
        jobs.enqueueCreateZip(sources, targetDir, outName, overwrite, password = null)
    }

    fun enqueueCreate7z(sources: List<Uri>, targetDir: Uri, outName: String, password: String?, overwrite: Boolean = false) {
        jobs.enqueueCreate7z(sources, targetDir, outName, password, overwrite)
    }

    fun enqueueCopy(sources: List<Uri>, targetDir: Uri, overwrite: Boolean = false) {
        jobs.enqueueCopy(sources, targetDir, overwrite)
    }

    fun enqueueMove(sources: List<Uri>, targetDir: Uri, overwrite: Boolean = false) {
        jobs.enqueueMove(sources, targetDir, overwrite)
    }

    fun cancelAll() {
        WorkManager.getInstance(application).cancelAllWorkByTag(ArchiveJobManager.TAG_ALL)
    }

    fun cancel(id: UUID) {
        WorkManager.getInstance(application).cancelWorkById(id)
    }
}
