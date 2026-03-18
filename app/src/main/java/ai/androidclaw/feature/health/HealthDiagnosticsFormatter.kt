package ai.androidclaw.feature.health

import java.time.Instant
import java.time.format.DateTimeFormatter

data class HealthDiagnosticsExportPayload(
    val fileName: String,
    val mimeType: String,
    val content: String,
)

fun buildDiagnosticsExportPayload(
    state: HealthUiState,
    exportedAt: Instant = Instant.now(),
): HealthDiagnosticsExportPayload {
    val timestamp = exportedAt.toString()
        .replace(':', '-')
        .replace(Regex("[^A-Za-z0-9._-]"), "-")
        .trim('-')
    return HealthDiagnosticsExportPayload(
        fileName = "androidclaw-diagnostics_$timestamp.txt",
        mimeType = "text/plain",
        content = buildDiagnosticsReport(state),
    )
}

fun buildDiagnosticsReport(state: HealthUiState): String {
    return buildString {
        appendLine("AndroidClaw diagnostics")
        appendLine("Provider: ${state.providerId}")
        appendLine("Network: ${state.networkSummary}")
        appendLine("Provider status: ${state.providerStatus}")
        state.lastProviderIssue?.let { appendLine("Last provider issue: $it") }
        appendLine("Scheduler kinds: ${state.supportedKinds.joinToString()}")
        appendLine("Exact alarms supported: ${state.schedulerDiagnostics.supportsExactAlarms}")
        appendLine("Exact alarm granted: ${state.schedulerDiagnostics.exactAlarmGranted}")
        appendLine("Standby bucket: ${state.schedulerDiagnostics.standbyBucket?.label ?: "Unavailable"}")
        appendLine("Last scheduler wake: ${state.lastSchedulerWake?.let(DateTimeFormatter.ISO_INSTANT::format) ?: "None"}")
        appendLine("Last automation result: ${state.lastAutomationResult ?: "None"}")
        appendLine("Last worker stop reason: ${state.lastWorkerStopReason ?: "None"}")
        state.lastCrashSummary?.let {
            appendLine("Last crash: $it")
        }
        appendLine("Bug report instructions: ${state.bugReportInstructions}")
        if (state.recentEvents.isNotEmpty()) {
            appendLine("Recent events:")
            state.recentEvents.forEach { event ->
                append("- ${event.timestamp}: ${event.category}/${event.level} ${event.message}")
                event.details?.takeIf { details -> details.isNotBlank() }?.let { details ->
                    append(" | ").append(details)
                }
                appendLine()
            }
        }
    }.trim()
}
