package ai.androidclaw.app

import android.app.Application
import android.os.Process
import androidx.work.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

class AndroidClawApplication :
    Application(),
    Configuration.Provider {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(this)
    }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val startupMaintenanceStarted = AtomicBoolean(false)

    override val workManagerConfiguration: Configuration
        get() =
            container.let { appContainer ->
                ensureStartupMaintenanceStarted(appContainer)
                Configuration
                    .Builder()
                    .setWorkerFactory(appContainer.workerFactory)
                    .build()
            }

    override fun onCreate() {
        super.onCreate()
        installCrashMarkerHandler(CrashMarkerStore(this))
    }

    fun ensureStartupMaintenanceStarted() {
        ensureStartupMaintenanceStarted(container)
    }

    private fun ensureStartupMaintenanceStarted(appContainer: AppContainer) {
        if (!startupMaintenanceStarted.compareAndSet(false, true)) {
            return
        }
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
