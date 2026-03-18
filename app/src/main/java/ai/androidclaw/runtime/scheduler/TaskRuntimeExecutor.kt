package ai.androidclaw.runtime.scheduler

import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.model.Task
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.runtime.orchestrator.AgentRunner
import ai.androidclaw.runtime.orchestrator.SessionLaneCoordinator
import java.time.Instant
import java.time.format.DateTimeFormatter

data class TaskRuntimeExecution(
    val success: Boolean,
    val summary: String,
    val outputMessageId: String?,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val retryable: Boolean = false,
)

class TaskRuntimeExecutor(
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val agentRunner: AgentRunner,
    private val sessionLaneCoordinator: SessionLaneCoordinator,
) {
    suspend fun execute(task: Task, taskRunId: String): TaskRuntimeExecution {
        return when (task.executionMode) {
            TaskExecutionMode.MainSession -> executeInMainSession(task, taskRunId)
            TaskExecutionMode.IsolatedSession -> executeInIsolatedSession(task, taskRunId)
        }
    }

    private suspend fun executeInMainSession(task: Task, taskRunId: String): TaskRuntimeExecution {
        val sessionId = resolveTargetSessionId(task)
        val result = agentRunner.runScheduledTurn(
            sessionId = sessionId,
            userMessage = task.prompt,
            taskRunId = taskRunId,
        )
        return if (result.directToolResult?.success == false) {
            TaskRuntimeExecution(
                success = false,
                summary = result.assistantMessage,
                outputMessageId = result.assistantMessageId,
                errorCode = result.directToolResult.errorCode ?: "TOOL_EXECUTION_FAILED",
                errorMessage = result.assistantMessage,
            )
        } else {
            TaskRuntimeExecution(
                success = true,
                summary = result.assistantMessage,
                outputMessageId = result.assistantMessageId,
            )
        }
    }

    private suspend fun executeInIsolatedSession(task: Task, taskRunId: String): TaskRuntimeExecution {
        val deliverySessionId = resolveTargetSessionId(task)
        val isolatedSession = sessionRepository.createSession(
            title = buildIsolatedSessionTitle(task),
        )
        val result = agentRunner.runScheduledTurn(
            sessionId = isolatedSession.id,
            userMessage = task.prompt,
            taskRunId = taskRunId,
        )
        val deliveryText = buildString {
            append("Task ")
            append(task.name)
            append(" completed in isolated session \"")
            append(isolatedSession.title)
            append("\".")
            append("\n\n")
            append(result.assistantMessage)
        }
        val deliveredMessage = try {
            sessionLaneCoordinator.withLane(deliverySessionId) {
                messageRepository.addMessage(
                    sessionId = deliverySessionId,
                    role = MessageRole.Assistant,
                    content = deliveryText,
                    providerMeta = result.providerMeta,
                    taskRunId = taskRunId,
                )
            }
        } catch (error: Exception) {
            return TaskRuntimeExecution(
                success = false,
                summary = deliveryText,
                outputMessageId = null,
                errorCode = "TASK_DELIVERY_FAILED",
                errorMessage = error.message ?: "Task delivery failed.",
                retryable = false,
            )
        }
        return if (result.directToolResult?.success == false) {
            TaskRuntimeExecution(
                success = false,
                summary = deliveryText,
                outputMessageId = deliveredMessage.id,
                errorCode = result.directToolResult.errorCode ?: "TOOL_EXECUTION_FAILED",
                errorMessage = result.assistantMessage,
            )
        } else {
            TaskRuntimeExecution(
                success = true,
                summary = deliveryText,
                outputMessageId = deliveredMessage.id,
            )
        }
    }

    private suspend fun resolveTargetSessionId(task: Task): String {
        return task.targetSessionId ?: sessionRepository.getOrCreateMainSession().id
    }

    private fun buildIsolatedSessionTitle(task: Task): String {
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        return "Task ${task.name} $timestamp"
    }
}
