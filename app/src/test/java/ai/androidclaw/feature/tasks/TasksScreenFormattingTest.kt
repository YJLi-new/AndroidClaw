package ai.androidclaw.feature.tasks

import ai.androidclaw.data.model.Task
import ai.androidclaw.data.model.TaskRun
import ai.androidclaw.data.model.TaskRunStatus
import ai.androidclaw.runtime.providers.ProviderMessageMeta
import ai.androidclaw.runtime.providers.ProviderUsagePayload
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedule
import ai.androidclaw.testutil.text
import ai.androidclaw.testutil.textOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class TasksScreenFormattingTest {
    @Test
    fun `retry state reports no runs when task has never executed`() {
        assertEquals(
            "No runs yet",
            retryStateText(testTask(failureCount = 0, nextRunAt = null), latestRun = null, formatInstant = Instant::toString).text(),
        )
    }

    @Test
    fun `retry state reports healthy after successful run`() {
        assertEquals(
            "Healthy",
            retryStateText(
                task = testTask(failureCount = 0, nextRunAt = Instant.parse("2026-03-19T00:00:00Z")),
                latestRun = testRun(TaskRunStatus.Success),
                formatInstant = Instant::toString,
            ).text(),
        )
    }

    @Test
    fun `retry state reports retry budget and next wake when failures remain`() {
        assertEquals(
            "Retry budget used 1/3; next wake 2026-03-19T01:00:00Z",
            retryStateText(
                task = testTask(failureCount = 1, nextRunAt = Instant.parse("2026-03-19T01:00:00Z")),
                latestRun = testRun(TaskRunStatus.Failure),
                formatInstant = Instant::toString,
            ).text(),
        )
    }

    @Test
    fun `run usage summary prefers provider model and total tokens`() {
        assertEquals(
            "anthropic · claude-3-7-sonnet · total 321 tokens",
            buildRunUsageSummary(
                ProviderMessageMeta(
                    providerId = "anthropic",
                    modelId = "claude-3-7-sonnet",
                    usage =
                        ProviderUsagePayload(
                            inputTokens = 200,
                            outputTokens = 121,
                            totalTokens = 321,
                        ),
                ),
            ).textOrNull(),
        )
    }

    @Test
    fun `run usage summary falls back to input and output token counts`() {
        assertEquals(
            "openai-compatible · in 90 / out 45 tokens",
            buildRunUsageSummary(
                ProviderMessageMeta(
                    providerId = "openai-compatible",
                    usage =
                        ProviderUsagePayload(
                            inputTokens = 90,
                            outputTokens = 45,
                            totalTokens = null,
                        ),
                ),
            ).textOrNull(),
        )
    }
}

private fun testTask(
    failureCount: Int,
    nextRunAt: Instant?,
): Task {
    val now = Instant.parse("2026-03-18T00:00:00Z")
    return Task(
        id = "task-1",
        name = "Daily check",
        prompt = "Check status",
        schedule = TaskSchedule.Once(now.plusSeconds(60)),
        executionMode = TaskExecutionMode.MainSession,
        targetSessionId = null,
        enabled = true,
        precise = false,
        nextRunAt = nextRunAt,
        lastRunAt = null,
        failureCount = failureCount,
        maxRetries = 3,
        createdAt = now,
        updatedAt = now,
    )
}

private fun testRun(status: TaskRunStatus): TaskRun {
    val now = Instant.parse("2026-03-18T00:00:00Z")
    return TaskRun(
        id = "run-1",
        taskId = "task-1",
        status = status,
        scheduledAt = now,
        startedAt = now,
        finishedAt = now.plusSeconds(5),
        errorCode = null,
        errorMessage = null,
        resultSummary = null,
        outputMessageId = null,
    )
}
