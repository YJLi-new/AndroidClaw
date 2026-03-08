package ai.androidclaw.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AndroidClawApplication : Application() {
    lateinit var container: AppContainer
        private set
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Use an application-scoped coroutine for one-shot startup hydration without leaking work to GlobalScope.
        applicationScope.launch {
            container.ensureMainSession()
        }
    }
}
