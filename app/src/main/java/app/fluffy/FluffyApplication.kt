package app.fluffy

import android.app.Application
import app.fluffy.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class FluffyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@FluffyApplication)
            modules(appModule)
        }
    }
}
