package app.fluffy.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.fluffy.operations.ArchiveJobManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TasksViewModel(
    private val context: Context,
    private val jobs: ArchiveJobManager
) : ViewModel() {

    private val _workInfos = MutableStateFlow<List<WorkInfo>>(emptyList())
    val workInfos: StateFlow<List<WorkInfo>> = _workInfos

    init {
        viewModelScope.launch {
            WorkManager.getInstance(context).getWorkInfosByTagLiveData("fluffy").observeForever { list ->
                _workInfos.value = list ?: emptyList()
            }
        }
    }

    fun enqueueExtract(archive: Uri, targetDir: Uri, password: String?) {
        jobs.enqueueExtract(archive, targetDir, password)
    }

    fun enqueueCreateZip(sources: List<Uri>, outName: String) {
        val target = workDefaultTargetDir() ?: return
        jobs.enqueueCreateZip(sources, target, outName, password = null)
    }

    private fun workDefaultTargetDir(): Uri? = null // hook for future: remember last opened dir
}