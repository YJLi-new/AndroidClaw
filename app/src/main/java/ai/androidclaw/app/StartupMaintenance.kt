package ai.androidclaw.app

import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.TaskRepository
import java.time.Clock
import java.time.Duration

data class StartupMaintenanceResult(
    val trimmedTaskRuns: Int,
    val trimmedEventLogs: Int,
)

class StartupMaintenance(
    private val clock: Clock,
    private val taskRepository: TaskRepository,
    private val eventLogRepository: EventLogRepository,
    private val ensureMainSession: suspend () -> Unit,
    private val rescheduleAll: suspend () -> Unit,
) {
    suspend fun run(): StartupMaintenanceResult {
        ensureMainSession()
        val now = clock.instant()
        val trimmedTaskRuns = taskRepository.trimRunsOlderThan(now.minus(TASK_RUN_RETENTION))
        val trimmedEventLogs = eventLogRepository.trimOlderThan(now.minus(EVENT_LOG_RETENTION))
        rescheduleAll()
        return StartupMaintenanceResult(
            trimmedTaskRuns = trimmedTaskRuns,
            trimmedEventLogs = trimmedEventLogs,
        )
    }

    companion object {
        val TASK_RUN_RETENTION: Duration = Duration.ofDays(30)
        val EVENT_LOG_RETENTION: Duration = Duration.ofDays(14)
    }
}
