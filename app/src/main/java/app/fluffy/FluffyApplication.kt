package app.fluffy

import android.app.Application
import app.fluffy.di.appModule
import app.fluffy.util.AppLog
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class FluffyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.debug = BuildConfig.DEBUG

        startKoin {
            androidContext(this@FluffyApplication)
            modules(appModule)
        }
    }
}
