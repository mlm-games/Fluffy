package app.fluffy

import android.content.Context
import app.fluffy.archive.ArchiveEngine
import archive.DefaultArchiveEngine
import app.fluffy.data.repository.SettingsRepository
import app.fluffy.io.SafIo
import app.fluffy.operations.ArchiveJobManager

object AppGraph {
    @Volatile private var instance: Instance? = null
    private val lock = Any()

    private class Instance(ctx: Context) {
        val appContext = ctx.applicationContext
        val settings = SettingsRepository(appContext)
        val io = SafIo(appContext)
        val archive: ArchiveEngine = DefaultArchiveEngine(appContext, io)
        val archiveJobs = ArchiveJobManager(appContext, archive)
    }

    fun init(context: Context) {
        if (instance == null) {
            synchronized(lock) {
                if (instance == null) instance = Instance(context)
            }
        }
    }

    private fun get(): Instance = instance ?: error("AppGraph not initialized")

    val settings get() = get().settings
    val io get() = get().io
    val archive get() = get().archive
    val archiveJobs get() = get().archiveJobs
}