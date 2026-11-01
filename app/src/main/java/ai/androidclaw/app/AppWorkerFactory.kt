package ai.androidclaw.app

import ai.androidclaw.runtime.media.VideoJobWorker
import ai.androidclaw.runtime.scheduler.TaskExecutionWorker
import android.content.Context
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
            TaskExecutionWorker::class.qualifiedName ->
                TaskExecutionWorker(
                    appContext = appContext,
                    workerParams = workerParameters,
                    taskRepository = container.taskRepository,
                    eventLogRepository = container.eventLogRepository,
                    schedulerCoordinator = container.schedulerCoordinator,
                    taskRuntimeExecutor = container.taskRuntimeExecutor,
                    taskNotifier = container.taskNotifier,
                )

            VideoJobWorker::class.qualifiedName ->
                VideoJobWorker(
                    appContext = appContext,
                    workerParams = workerParameters,
                    policy = container.mediaGenerationPolicy,
                    client = container.openAiMediaClient,
                    mediaStore = container.generatedMediaStore,
                    sessionRepository = container.sessionRepository,
                    messageRepository = container.messageRepository,
                    attachmentRepository = container.attachmentRepository,
                    sessionLaneCoordinator = container.sessionLaneCoordinator,
                    notifier = container.videoNotifier,
                )

            else -> null
        }
    }
}
