package app.fluffy.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.fluffy.operations.ArchiveJobManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class TasksViewModel(
    private val context: Context,
    private val jobs: ArchiveJobManager
) : ViewModel() {

    private val _workInfos = MutableStateFlow<List<WorkInfo>>(emptyList())
    val workInfos: StateFlow<List<WorkInfo>> = _workInfos

    private val liveData = WorkManager.getInstance(context).getWorkInfosByTagLiveData(ArchiveJobManager.TAG_ALL)
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

    fun enqueueCreateZip(sources: List<Uri>, targetDir: Uri, outName: String) {
        jobs.enqueueCreateZip(sources, targetDir, outName, password = null)
    }

    fun enqueueCreate7z(sources: List<Uri>, targetDir: Uri, outName: String, password: String?) {
        jobs.enqueueCreate7z(sources, targetDir, outName, password)
    }

    fun enqueueCopy(sources: List<Uri>, targetDir: Uri) {
        jobs.enqueueCopy(sources, targetDir)
    }

    fun enqueueMove(sources: List<Uri>, targetDir: Uri) {
        jobs.enqueueMove(sources, targetDir)
    }

    fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWorkByTag(ArchiveJobManager.TAG_ALL)
    }

    fun cancel(id: UUID) {
        WorkManager.getInstance(context).cancelWorkById(id)
    }
}