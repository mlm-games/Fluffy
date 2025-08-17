package app.fluffy.operations

import android.content.Context
import android.net.Uri
import androidx.work.*
import app.fluffy.archive.ArchiveEngine
import app.fluffy.work.CreateArchiveWorker
import app.fluffy.work.ExtractArchiveWorker
import java.util.concurrent.TimeUnit

class ArchiveJobManager(
    private val context: Context,
    private val engine: ArchiveEngine
) {
    fun enqueueExtract(archive: Uri, targetDir: Uri, password: String? = null): String {
        val req = OneTimeWorkRequestBuilder<ExtractArchiveWorker>()
            .setInputData(
                workDataOf(
                    ExtractArchiveWorker.KEY_ARCHIVE to archive.toString(),
                    ExtractArchiveWorker.KEY_TARGET_DIR to targetDir.toString(),
                    ExtractArchiveWorker.KEY_PASSWORD to (password ?: "")
                )
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(req)
        return req.id.toString()
    }

    fun enqueueCreateZip(sources: List<Uri>, targetDir: Uri, outName: String, password: String? = null): String {
        val req = OneTimeWorkRequestBuilder<CreateArchiveWorker>()
            .setInputData(
                workDataOf(
                    CreateArchiveWorker.KEY_SOURCES to sources.map { it.toString() }.toTypedArray(),
                    CreateArchiveWorker.KEY_TARGET_DIR to targetDir.toString(),
                    CreateArchiveWorker.KEY_OUT_NAME to outName,
                    CreateArchiveWorker.KEY_PASSWORD to (password ?: "")
                )
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(req)
        return req.id.toString()
    }
}