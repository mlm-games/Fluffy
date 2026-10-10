package app.fluffy.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.fluffy.cache.CacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

class CacheCleanupWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params), KoinComponent {

    private val cacheManager: CacheManager by inject()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        cacheManager.enforceBudget()
        Result.success()
    }

    companion object {
        private const val WORK_NAME = "cache_cleanup"

        fun schedule(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<CacheCleanupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
