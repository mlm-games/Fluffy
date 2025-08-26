package app.fluffy.operations

import android.content.Context
import android.net.Uri
import androidx.work.*
import app.fluffy.work.Create7zWorker
import app.fluffy.work.CreateArchiveWorker
import app.fluffy.work.ExtractArchiveWorker
import app.fluffy.work.FileOpsWorker
import java.util.concurrent.TimeUnit

class ArchiveJobManager(
    private val context: Context,
    @Suppress("unused") private val engine: app.fluffy.archive.ArchiveEngine
) {
    fun enqueueExtract(
        archive: Uri,
        targetDir: Uri,
        password: String? = null,
        includePaths: List<String>? = null
    ): String {
        val data = workDataOf(
            ExtractArchiveWorker.KEY_ARCHIVE to archive.toString(),
            ExtractArchiveWorker.KEY_TARGET_DIR to targetDir.toString(),
            ExtractArchiveWorker.KEY_PASSWORD to (password ?: ""),
            ExtractArchiveWorker.KEY_INCLUDE_PATHS to (includePaths?.toTypedArray() ?: emptyArray())
        )
        val req = OneTimeWorkRequestBuilder<ExtractArchiveWorker>()
            .addTag(TAG_ALL).addTag(TAG_EXTRACT)
            .setInputData(data)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(req)
        return req.id.toString()
    }

    fun enqueueCreateZip(
        sources: List<Uri>,
        targetDir: Uri,
        outName: String,
        overwrite: Boolean = false,
        password: String? = null
    ): String {
        val req = OneTimeWorkRequestBuilder<CreateArchiveWorker>()
            .addTag(TAG_ALL).addTag(TAG_CREATE_ZIP)
            .setInputData(
                workDataOf(
                    CreateArchiveWorker.KEY_SOURCES to sources.map { it.toString() }.toTypedArray(),
                    CreateArchiveWorker.KEY_TARGET_DIR to targetDir.toString(),
                    CreateArchiveWorker.KEY_OUT_NAME to outName,
                    CreateArchiveWorker.KEY_PASSWORD to (password ?: ""),
                    CreateArchiveWorker.KEY_OVERWRITE to overwrite
                )
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(req)
        return req.id.toString()
    }

    fun enqueueCreate7z(
        sources: List<Uri>,
        targetDir: Uri,
        outName: String,
        password: String? = null,
        overwrite: Boolean = false
    ): String {
        val req = OneTimeWorkRequestBuilder<Create7zWorker>()
            .addTag(TAG_ALL).addTag(TAG_CREATE_7Z)
            .setInputData(
                workDataOf(
                    Create7zWorker.KEY_SOURCES to sources.map { it.toString() }.toTypedArray(),
                    Create7zWorker.KEY_TARGET_DIR to targetDir.toString(),
                    Create7zWorker.KEY_OUT_NAME to outName,
                    Create7zWorker.KEY_PASSWORD to (password ?: ""),
                    Create7zWorker.KEY_OVERWRITE to overwrite
                )
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(req)
        return req.id.toString()
    }

    fun enqueueCopy(sources: List<Uri>, targetDir: Uri, overwrite: Boolean = false): String {
        val req = OneTimeWorkRequestBuilder<FileOpsWorker>()
            .addTag(TAG_ALL).addTag(TAG_COPY)
            .setInputData(
                workDataOf(
                    FileOpsWorker.KEY_SOURCES to sources.map { it.toString() }.toTypedArray(),
                    FileOpsWorker.KEY_TARGET_DIR to targetDir.toString(),
                    FileOpsWorker.KEY_OP to FileOpsWorker.OP_COPY,
                    FileOpsWorker.KEY_OVERWRITE to overwrite
                )
            )
            .build()
        WorkManager.getInstance(context).enqueue(req)
        return req.id.toString()
    }

    fun enqueueMove(sources: List<Uri>, targetDir: Uri, overwrite: Boolean = false): String {
        val req = OneTimeWorkRequestBuilder<FileOpsWorker>()
            .addTag(TAG_ALL).addTag(TAG_MOVE)
            .setInputData(
                workDataOf(
                    FileOpsWorker.KEY_SOURCES to sources.map { it.toString() }.toTypedArray(),
                    FileOpsWorker.KEY_TARGET_DIR to targetDir.toString(),
                    FileOpsWorker.KEY_OP to FileOpsWorker.OP_MOVE,
                    FileOpsWorker.KEY_OVERWRITE to overwrite
                )
            )
            .build()
        WorkManager.getInstance(context).enqueue(req)
        return req.id.toString()
    }

    companion object {
        const val TAG_ALL = "fluffy"
        const val TAG_EXTRACT = "extract"
        const val TAG_CREATE_ZIP = "create_zip"
        const val TAG_CREATE_7Z = "create_7z"
        const val TAG_COPY = "copy"
        const val TAG_MOVE = "move"
    }
}

