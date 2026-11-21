package app.fluffy.viewmodel

import android.net.Uri
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.fluffy.data.repository.QueueAddResult
import app.fluffy.data.repository.QueuedTask
import app.fluffy.data.repository.TaskQueueRepository
import app.fluffy.operations.ArchiveJobManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class PendingTaskRun(val task: QueuedTask, val target: Uri)

class TasksViewModel(
    private val workManager: WorkManager,
    private val jobs: ArchiveJobManager,
    private val queue: TaskQueueRepository,
) : ViewModel() {

    private val _workInfos = MutableStateFlow<List<WorkInfo>>(emptyList())
    val workInfos: StateFlow<List<WorkInfo>> = _workInfos

    val queuedTasks: StateFlow<List<QueuedTask>> = queue.tasks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val _pendingRun = MutableStateFlow<PendingTaskRun?>(null)
    val pendingRun: StateFlow<PendingTaskRun?> = _pendingRun

    private var claimedTaskId: String? = null

    fun claimTask(task: QueuedTask): Boolean {
        if (claimedTaskId != null || _pendingRun.value != null) return false
        claimedTaskId = task.id
        return true
    }

    fun isClaimed(task: QueuedTask): Boolean = claimedTaskId == task.id

    fun releaseClaim() {
        claimedTaskId = null
    }

    fun requestRun(task: QueuedTask, target: Uri) {
        _pendingRun.value = PendingTaskRun(task, target)
    }

    fun dismissRun() {
        _pendingRun.value = null
    }

    private val liveData = workManager.getWorkInfosByTagLiveData(ArchiveJobManager.TAG_ALL)

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

    fun enqueueExtract(
        archive: Uri,
        targetDir: Uri,
        password: String? = null,
        includePaths: List<String>? = null,
    ): String = jobs.enqueueExtract(archive, targetDir, password, includePaths)

    fun enqueueCreateZip(
        sources: List<Uri>,
        targetDir: Uri,
        outName: String,
        password: String?,
        overwrite: Boolean,
        encryption: String = "NONE",
        comment: String? = null,
        splitBytes: Long = -1L,
    ): String = jobs.enqueueCreateZip(
        sources, targetDir, outName, password, overwrite, encryption, comment, splitBytes
    )

    fun enqueueCreate7z(
        sources: List<Uri>,
        targetDir: Uri,
        outName: String,
        password: String?,
        overwrite: Boolean,
    ): String = jobs.enqueueCreate7z(sources, targetDir, outName, password, overwrite)

    fun enqueueCopy(
        sources: List<Uri>,
        targetDir: Uri,
        overwrite: Boolean,
    ): String = jobs.enqueueCopy(sources, targetDir, overwrite)

    fun enqueueMove(
        sources: List<Uri>,
        targetDir: Uri,
        overwrite: Boolean,
    ): String = jobs.enqueueMove(sources, targetDir, overwrite)

    fun cancel(id: UUID) {
        workManager.cancelWorkById(id)
    }

    suspend fun addTask(task: QueuedTask): QueueAddResult = queue.add(task)

    fun removeTask(id: String) {
        viewModelScope.launch { queue.remove(id) }
    }

    fun clearQueue() {
        viewModelScope.launch { queue.clear() }
    }

    fun runTask(task: QueuedTask, targetDir: Uri, password: String?, overwrite: Boolean) {
        val sources = task.sources.map { Uri.parse(it) }
        when (task) {
            is QueuedTask.Copy -> jobs.enqueueCopy(sources, targetDir, overwrite)
            is QueuedTask.Move -> jobs.enqueueMove(sources, targetDir, overwrite)
            is QueuedTask.CreateZip -> jobs.enqueueCreateZip(
                sources = sources,
                targetDir = targetDir,
                outName = task.outName,
                password = password,
                overwrite = overwrite,
                encryption = task.encryption,
                comment = task.comment,
                splitBytes = task.splitBytes ?: -1L,
            )
            is QueuedTask.Create7z -> jobs.enqueueCreate7z(
                sources = sources,
                targetDir = targetDir,
                outName = task.outName,
                password = password,
                overwrite = overwrite,
            )
        }
        if (task !is QueuedTask.Copy) removeTask(task.id)
    }
}
