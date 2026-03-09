package ai.androidclaw.app

import android.content.Context
import ai.androidclaw.runtime.scheduler.TaskExecutionWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

class AppWorkerFactory(
    private val containerProvider: () -> AppContainer,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        val container = containerProvider()
        return when (workerClassName) {
            TaskExecutionWorker::class.qualifiedName -> TaskExecutionWorker(
                appContext = appContext,
                workerParams = workerParameters,
                taskRepository = container.taskRepository,
                eventLogRepository = container.eventLogRepository,
                schedulerCoordinator = container.schedulerCoordinator,
                taskRuntimeExecutor = container.taskRuntimeExecutor,
            )

            else -> null
        }
    }
}
