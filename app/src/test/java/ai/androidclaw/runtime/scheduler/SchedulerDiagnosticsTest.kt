package ai.androidclaw.runtime.scheduler

import ai.androidclaw.data.model.Task
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerDiagnosticsTest {
    @Test
    fun `approximate tasks stay on WorkManager`() {
        val decision = task(precise = false).schedulingDecision(
            diagnostics = SchedulerDiagnostics(
                supportsExactAlarms = true,
                exactAlarmGranted = true,
            ),
        )

        assertEquals(TaskSchedulingPath.WorkManagerApproximate, decision.path)
        assertEquals(null, decision.degradedReason)
    }

    @Test
    fun `precise tasks use exact alarms when granted`() {
        val decision = task(precise = true).schedulingDecision(
            diagnostics = SchedulerDiagnostics(
                supportsExactAlarms = true,
                exactAlarmGranted = true,
            ),
        )

        assertEquals(TaskSchedulingPath.ExactAlarm, decision.path)
        assertEquals(null, decision.degradedReason)
    }

    @Test
    fun `precise tasks degrade cleanly when permission is denied`() {
        val decision = task(precise = true).schedulingDecision(
            diagnostics = SchedulerDiagnostics(
                supportsExactAlarms = true,
                exactAlarmGranted = false,
            ),
        )

        assertEquals(TaskSchedulingPath.WorkManagerApproximate, decision.path)
        assertTrue(decision.degradedReason?.contains("permission denied", ignoreCase = true) == true)
    }

    @Test
    fun `unknown work stop reasons stay legible`() {
        assertEquals("not_stopped", workStopReasonLabel(0))
        assertEquals("code(999)", workStopReasonLabel(999))
    }
}

private fun task(precise: Boolean): Task {
    return Task(
        id = "task-1",
        name = "Task 1",
        prompt = "Run task",
        schedule = TaskSchedule.Once(Instant.parse("2026-03-09T00:00:00Z")),
        executionMode = TaskExecutionMode.MainSession,
        targetSessionId = "main",
        enabled = true,
        precise = precise,
        nextRunAt = Instant.parse("2026-03-09T00:00:00Z"),
        lastRunAt = null,
        failureCount = 0,
        maxRetries = 3,
        createdAt = Instant.parse("2026-03-08T00:00:00Z"),
        updatedAt = Instant.parse("2026-03-08T00:00:00Z"),
    )
}
