package ai.androidclaw.app

import android.app.Application

class AndroidClawApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

