package ai.androidclaw.app

import android.app.Application
import android.os.Process
import androidx.work.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

class AndroidClawApplication : Application(), Configuration.Provider {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(this)
    }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(container.workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        val appContainer = container
        installCrashMarkerHandler(appContainer.crashMarkerStore)
        // Use an application-scoped coroutine for one-shot startup hydration without leaking work to GlobalScope.
        applicationScope.launch {
            appContainer.startupMaintenance.run()
        }
    }

    private fun installCrashMarkerHandler(crashMarkerStore: CrashMarkerStore) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                crashMarkerStore.record(
                    threadName = thread.name,
                    throwable = throwable,
                )
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}
