package app.fluffy.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.taskQueueDataStore by preferencesDataStore(name = "task_queue")

@Serializable
sealed interface QueuedTask {
    val id: String
    val sources: List<String>
    val createdAt: Long

    @Serializable
    data class Copy(
        override val id: String = UUID.randomUUID().toString(),
        override val sources: List<String>,
        override val createdAt: Long = java.lang.System.currentTimeMillis(),
    ) : QueuedTask

    @Serializable
    data class Move(
        override val id: String = UUID.randomUUID().toString(),
        override val sources: List<String>,
        override val createdAt: Long = java.lang.System.currentTimeMillis(),
    ) : QueuedTask

    @Serializable
    data class CreateZip(
        override val id: String = UUID.randomUUID().toString(),
        override val sources: List<String>,
        val outName: String,
        val encryption: String,
        val comment: String?,
        val splitBytes: Long?,
        val hasPassword: Boolean,
        override val createdAt: Long = java.lang.System.currentTimeMillis(),
    ) : QueuedTask

    @Serializable
    data class Create7z(
        override val id: String = UUID.randomUUID().toString(),
        override val sources: List<String>,
        val outName: String,
        val hasPassword: Boolean,
        override val createdAt: Long = java.lang.System.currentTimeMillis(),
    ) : QueuedTask
}

@Serializable
private data class StoredQueue(
    val version: Int = 1,
    val tasks: List<QueuedTask> = emptyList(),
)

enum class QueueAddResult { ADDED, UPDATED }

class TaskQueueRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val tasksKey = stringPreferencesKey("queued_tasks")
    private val backupKey = stringPreferencesKey("queued_tasks_corrupt_backup")

    val tasks: Flow<List<QueuedTask>> = context.taskQueueDataStore.data.map { prefs ->
        decodePreserving(prefs[tasksKey]) ?: emptyList()
    }

    private fun decodePreserving(raw: String?): List<QueuedTask>? {
        if (raw == null) return emptyList()
        return runCatching { json.decodeFromString<StoredQueue>(raw).tasks }.getOrNull()
    }

    suspend fun add(task: QueuedTask): QueueAddResult {
        var result = QueueAddResult.ADDED
        context.taskQueueDataStore.edit { prefs ->
            val raw = prefs[tasksKey]
            val current = decodePreserving(raw) ?: run {
                raw?.let { prefs[backupKey] = it }
                emptyList()
            }
            val fingerprint = task.fingerprint()
            val existing = current.firstOrNull { it.fingerprint() == fingerprint }
            val updated = if (existing == null) {
                current + task
            } else {
                result = QueueAddResult.UPDATED
                current.map {
                    if (it.id == existing.id) task.withIdentity(existing.id, existing.createdAt) else it
                }
            }
            prefs[tasksKey] = json.encodeToString(StoredQueue(tasks = updated.takeLast(MAX_TASKS)))
        }
        return result
    }

    suspend fun remove(id: String) {
        context.taskQueueDataStore.edit { prefs ->
            val current = decodePreserving(prefs[tasksKey]) ?: return@edit
            prefs[tasksKey] = json.encodeToString(
                StoredQueue(tasks = current.filterNot { it.id == id })
            )
        }
    }

    suspend fun clear() {
        context.taskQueueDataStore.edit { prefs ->
            prefs[tasksKey] = json.encodeToString(StoredQueue())
        }
    }

    private fun QueuedTask.fingerprint(): String {
        val orderedSources = sources.sorted().joinToString(separator = "|")
        return when (this) {
            is QueuedTask.Copy -> "copy|$orderedSources"
            is QueuedTask.Move -> "move|$orderedSources"
            is QueuedTask.CreateZip ->
                "zip|$orderedSources|$outName|$encryption|$comment|$splitBytes|$hasPassword"
            is QueuedTask.Create7z -> "7z|$orderedSources|$outName|$hasPassword"
        }
    }

    private fun QueuedTask.withIdentity(id: String, createdAt: Long): QueuedTask = when (this) {
        is QueuedTask.Copy -> copy(id = id, createdAt = createdAt)
        is QueuedTask.Move -> copy(id = id, createdAt = createdAt)
        is QueuedTask.CreateZip -> copy(id = id, createdAt = createdAt)
        is QueuedTask.Create7z -> copy(id = id, createdAt = createdAt)
    }

    private companion object {
        const val MAX_TASKS = 100
    }
}
