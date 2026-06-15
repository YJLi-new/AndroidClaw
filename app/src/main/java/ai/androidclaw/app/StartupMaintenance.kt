package ai.androidclaw.app

import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.TaskRepository
import java.time.Clock
import java.time.Duration

data class StartupMaintenanceResult(
    val trimmedTaskRuns: Int,
    val trimmedEventLogs: Int,
    val repairedCompactionBoundaries: Int,
)

class StartupMaintenance(
    private val clock: Clock,
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
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
        val repairedCompactionBoundaries = repairInvalidCompactionBoundaries()
        rescheduleAll()
        return StartupMaintenanceResult(
            trimmedTaskRuns = trimmedTaskRuns,
            trimmedEventLogs = trimmedEventLogs,
            repairedCompactionBoundaries = repairedCompactionBoundaries,
        )
    }

    private suspend fun repairInvalidCompactionBoundaries(): Int {
        val compactedSessions = sessionRepository.getSessionsWithCompactionBoundary()
        if (compactedSessions.isEmpty()) {
            return 0
        }
        val boundaryIds = compactedSessions.mapNotNull { it.compactedUntilMessageId }
        val messagesById = messageRepository.getMessagesByIds(boundaryIds)
        var repairedCount = 0
        compactedSessions.forEach { session ->
            val boundaryId = session.compactedUntilMessageId ?: return@forEach
            val boundaryMessage = messagesById[boundaryId]
            if (boundaryMessage?.sessionId != session.id) {
                sessionRepository.clearCompactionBoundary(session.id)
                repairedCount += 1
            }
        }
        return repairedCount
    }

    companion object {
        val TASK_RUN_RETENTION: Duration = Duration.ofDays(30)
        val EVENT_LOG_RETENTION: Duration = Duration.ofDays(14)
    }
}
