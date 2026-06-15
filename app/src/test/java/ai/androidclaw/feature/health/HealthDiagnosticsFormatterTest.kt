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
        assertTrue(report.contains("Last crash stack trace:"))
        assertTrue(report.contains("java.lang.IllegalStateException: boom"))
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

    @Test
    fun `diagnostics report bounds arbitrary state text lists and events`() {
        val longField = "field-" + "f".repeat(HEALTH_DIAGNOSTIC_FIELD_MAX_CHARS + 100)
        val longDetails = "details-" + "d".repeat(HEALTH_DIAGNOSTIC_DETAILS_MAX_CHARS + 100)
        val longStackTrace = "stack-" + "s".repeat(HEALTH_DIAGNOSTIC_STACKTRACE_MAX_CHARS + 100)
        val state =
            testHealthState().copy(
                providerId = longField,
                networkSummary = longField,
                providerStatus = longField,
                lastProviderIssue = longField,
                lastCrashSummary = longField,
                lastCrashStackTrace = longStackTrace,
                bugReportInstructions = longField,
                supportedKinds =
                    (0..HEALTH_DIAGNOSTIC_LIST_MAX_ITEMS).map { index ->
                        "kind-$index-" + "k".repeat(HEALTH_DIAGNOSTIC_LIST_ITEM_MAX_CHARS + 20)
                    },
                tools =
                    (0..HEALTH_DIAGNOSTIC_LIST_MAX_ITEMS).map { index ->
                        "tool-$index-" + "t".repeat(HEALTH_DIAGNOSTIC_LIST_ITEM_MAX_CHARS + 20)
                    },
                lastAutomationResult = longDetails,
                lastWorkerStopReason = longDetails,
                recentEvents =
                    (0..HEALTH_DIAGNOSTIC_EVENT_MAX_COUNT).map { index ->
                        EventLogEntry(
                            id = "event-$index",
                            category = EventCategory.Provider,
                            level = EventLevel.Error,
                            message = longField,
                            details = longDetails,
                            timestamp = Instant.parse("2026-03-18T11:01:00Z"),
                        )
                    },
            )

        val report = buildDiagnosticsReport(state)

        assertTrue(report.contains("… [truncated]"))
        assertTrue(report.contains("(+1 omitted)"))
        assertTrue(report.contains("- 1 additional events omitted."))
        assertTrue(report.contains("Last crash stack trace:"))
        assertTrue(report.substringAfter("Last crash stack trace:").contains("… [truncated]"))
        assertTrue(report.length < 140_000)
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
