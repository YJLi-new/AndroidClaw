package ai.androidclaw.feature.health

import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.EventLevel
import ai.androidclaw.data.model.EventLogEntry
import ai.androidclaw.runtime.scheduler.NotificationVisibilityDiagnostics
import ai.androidclaw.runtime.scheduler.SchedulerDiagnostics
import ai.androidclaw.runtime.scheduler.StandbyBucketInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HealthDiagnosticsFormatterTest {
    @Test
    fun `diagnostics report includes crash details and recent events`() {
        val state = testHealthState()

        val report = buildDiagnosticsReport(state)

        assertTrue(report.contains("Provider: anthropic"))
        assertTrue(report.contains("Last crash: 2026-03-18T10:00:00Z"))
        assertTrue(report.contains("Recent events:"))
        assertTrue(report.contains("Provider/Error Request failed"))
        assertTrue(report.contains("Bug report instructions:"))
    }

    @Test
    fun `diagnostics export payload creates deterministic text file name`() {
        val payload =
            buildDiagnosticsExportPayload(
                state = testHealthState(),
                exportedAt = Instant.parse("2026-03-18T12:34:56Z"),
            )

        assertEquals("androidclaw-diagnostics_2026-03-18T12-34-56Z.txt", payload.fileName)
        assertEquals("text/plain", payload.mimeType)
        assertTrue(payload.content.contains("AndroidClaw diagnostics"))
    }
}

private fun testHealthState(): HealthUiState =
    HealthUiState(
        providerId = "anthropic",
        networkSummary = "Connected",
        providerStatus = "Remote provider is ready for interactive use.",
        lastProviderIssue = "Request failed (HTTP 500)",
        lastCrashSummary = "2026-03-18T10:00:00Z · IllegalStateException · boom · thread=main",
        lastCrashStackTrace = "java.lang.IllegalStateException: boom",
        bugReportInstructions = "Copy diagnostics before filing a bug.",
        schedulerDiagnostics =
            SchedulerDiagnostics(
                supportsExactAlarms = true,
                exactAlarmGranted = false,
                standbyBucket =
                    StandbyBucketInfo(
                        value = 20,
                        label = "working_set",
                    ),
                notificationVisibility =
                    NotificationVisibilityDiagnostics(
                        appNotificationsEnabled = true,
                        runtimePermissionRequired = true,
                        runtimePermissionGranted = true,
                    ),
            ),
        supportedKinds = listOf("once", "interval", "cron"),
        tools = listOf("health.status"),
        lastSchedulerWake = Instant.parse("2026-03-18T11:00:00Z"),
        lastAutomationResult = "Task completed.",
        lastWorkerStopReason = "quota",
        recentEvents =
            listOf(
                EventLogEntry(
                    id = "event-1",
                    category = EventCategory.Provider,
                    level = EventLevel.Error,
                    message = "Request failed",
                    details = "HTTP 500",
                    timestamp = Instant.parse("2026-03-18T11:01:00Z"),
                ),
            ),
    )
