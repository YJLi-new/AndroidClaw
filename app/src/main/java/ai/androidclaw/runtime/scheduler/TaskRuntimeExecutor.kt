package ai.androidclaw.runtime.scheduler

import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.model.Task
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.runtime.orchestrator.AgentRunner
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
) {
    suspend fun execute(task: Task, taskRunId: String): TaskRuntimeExecution {
        return when (task.executionMode) {
            TaskExecutionMode.MainSession -> executeInMainSession(task, taskRunId)
            TaskExecutionMode.IsolatedSession -> executeInIsolatedSession(task, taskRunId)
        }
    }

    private suspend fun executeInMainSession(task: Task, taskRunId: String): TaskRuntimeExecution {
        val sessionId = resolveTargetSessionId(task)
        messageRepository.addMessage(
            sessionId = sessionId,
            role = MessageRole.User,
            content = task.prompt,
            taskRunId = taskRunId,
        )
        val result = agentRunner.runScheduledTurn(
            sessionId = sessionId,
            userMessage = task.prompt,
        )
        val assistantText = result.toPersistedAssistantText()
        val assistantMessage = messageRepository.addMessage(
            sessionId = sessionId,
            role = MessageRole.Assistant,
            content = assistantText,
            providerMeta = result.providerRequestId,
            taskRunId = taskRunId,
        )
        return if (result.directToolResult?.success == false) {
            TaskRuntimeExecution(
                success = false,
                summary = assistantText,
                outputMessageId = assistantMessage.id,
                errorCode = result.directToolResult.errorCode ?: "TOOL_EXECUTION_FAILED",
                errorMessage = result.assistantMessage,
            )
        } else {
            TaskRuntimeExecution(
                success = true,
                summary = assistantText,
                outputMessageId = assistantMessage.id,
            )
        }
    }

    private suspend fun executeInIsolatedSession(task: Task, taskRunId: String): TaskRuntimeExecution {
        val deliverySessionId = resolveTargetSessionId(task)
        val isolatedSession = sessionRepository.createSession(
            title = buildIsolatedSessionTitle(task),
        )
        messageRepository.addMessage(
            sessionId = isolatedSession.id,
            role = MessageRole.User,
            content = task.prompt,
            taskRunId = taskRunId,
        )
        val result = agentRunner.runScheduledTurn(
            sessionId = isolatedSession.id,
            userMessage = task.prompt,
        )
        val isolatedAssistantText = result.toPersistedAssistantText()
        messageRepository.addMessage(
            sessionId = isolatedSession.id,
            role = MessageRole.Assistant,
            content = isolatedAssistantText,
            providerMeta = result.providerRequestId,
            taskRunId = taskRunId,
        )
        val deliveryText = buildString {
            append("Task ")
            append(task.name)
            append(" completed in isolated session \"")
            append(isolatedSession.title)
            append("\".")
            append("\n\n")
            append(isolatedAssistantText)
        }
        val deliveredMessage = messageRepository.addMessage(
            sessionId = deliverySessionId,
            role = MessageRole.Assistant,
            content = deliveryText,
            providerMeta = result.providerRequestId,
            taskRunId = taskRunId,
        )
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

private fun ai.androidclaw.runtime.orchestrator.AgentTurnResult.toPersistedAssistantText(): String {
    return buildString {
        append(assistantMessage)
        if (selectedSkills.isNotEmpty()) {
            append("\n\nActive skills: ")
            append(selectedSkills.joinToString { it.displayName })
        }
    }
}
