package app.fluffy

import android.app.Application
import android.content.Context
import androidx.work.WorkManager
import app.fluffy.cache.CacheManager
import app.fluffy.di.appModule
import app.fluffy.image.GeneratedThumbnail
import app.fluffy.image.GeneratedThumbnailFetcherFactory
import app.fluffy.image.GeneratedThumbnailKeyer
import app.fluffy.util.AppLog
import app.fluffy.work.CacheCleanupWorker
import coil3.ImageLoader
import coil3.SingletonImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class FluffyApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        AppLog.debug = BuildConfig.DEBUG

        val koin = startKoin {
            androidContext(this@FluffyApplication)
            modules(appModule)
        }.koin

        CacheCleanupWorker.schedule(WorkManager.getInstance(this))

        CoroutineScope(Dispatchers.IO).launch { koin.get<CacheManager>().enforceBudget() }
    }

    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(GeneratedThumbnailKeyer(), GeneratedThumbnail::class)
                add(GeneratedThumbnailFetcherFactory(context), GeneratedThumbnail::class)
            }
            .build()
}
