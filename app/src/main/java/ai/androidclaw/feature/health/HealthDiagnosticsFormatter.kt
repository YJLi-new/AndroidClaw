package ai.androidclaw.feature.health

import java.time.Instant
import java.time.format.DateTimeFormatter

data class HealthDiagnosticsExportPayload(
    val fileName: String,
    val mimeType: String,
    val content: String,
)

internal const val HEALTH_DIAGNOSTIC_FIELD_MAX_CHARS = 1_000
internal const val HEALTH_DIAGNOSTIC_DETAILS_MAX_CHARS = 4_000
internal const val HEALTH_DIAGNOSTIC_STACKTRACE_MAX_CHARS = 8_000
internal const val HEALTH_DIAGNOSTIC_LIST_ITEM_MAX_CHARS = 160
internal const val HEALTH_DIAGNOSTIC_LIST_MAX_ITEMS = 50
internal const val HEALTH_DIAGNOSTIC_EVENT_MAX_COUNT = 10

fun buildDiagnosticsExportPayload(
    state: HealthUiState,
    exportedAt: Instant = Instant.now(),
): HealthDiagnosticsExportPayload {
    val timestamp =
        exportedAt
            .toString()
            .replace(':', '-')
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
            .trim('-')
    return HealthDiagnosticsExportPayload(
        fileName = "androidclaw-diagnostics_$timestamp.txt",
        mimeType = "text/plain",
        content = buildDiagnosticsReport(state),
    )
}

fun buildDiagnosticsReport(state: HealthUiState): String =
    buildString {
        appendLine("AndroidClaw diagnostics")
        appendLine("Provider: ${state.providerId.toDiagnosticField()}")
        appendLine("Network: ${state.networkSummary.toDiagnosticField()}")
        appendLine("Provider status: ${state.providerStatus.toDiagnosticField()}")
        state.lastProviderIssue.toDiagnosticFieldOrNull()?.let { appendLine("Last provider issue: $it") }
        appendLine("Scheduler kinds: ${state.supportedKinds.toDiagnosticListText()}")
        appendLine("Tools: ${state.tools.toDiagnosticListText()}")
        appendLine("Exact alarms supported: ${state.schedulerDiagnostics.supportsExactAlarms}")
        appendLine("Exact alarm granted: ${state.schedulerDiagnostics.exactAlarmGranted}")
        val standbyBucket =
            state.schedulerDiagnostics.standbyBucket
                ?.label
                .toDiagnosticFieldOrNull()
                ?: "Unavailable"
        appendLine("Standby bucket: $standbyBucket")
        val lastSchedulerWake =
            state.lastSchedulerWake?.let(DateTimeFormatter.ISO_INSTANT::format) ?: "None"
        appendLine("Last scheduler wake: $lastSchedulerWake")
        appendLine("Last automation result: ${state.lastAutomationResult.toDiagnosticDetailsOrNull() ?: "None"}")
        appendLine("Last worker stop reason: ${state.lastWorkerStopReason.toDiagnosticDetailsOrNull() ?: "None"}")
        state.lastCrashSummary.toDiagnosticFieldOrNull()?.let {
            appendLine("Last crash: $it")
        }
        state.lastCrashStackTrace.toBoundedDiagnosticText(HEALTH_DIAGNOSTIC_STACKTRACE_MAX_CHARS)?.let {
            appendLine("Last crash stack trace:")
            appendLine(it)
        }
        appendLine("Bug report instructions: ${state.bugReportInstructions.toDiagnosticField()}")
        if (state.recentEvents.isNotEmpty()) {
            appendLine("Recent events:")
            state.recentEvents.take(HEALTH_DIAGNOSTIC_EVENT_MAX_COUNT).forEach { event ->
                append("- ${event.timestamp}: ${event.category}/${event.level} ${event.message.toDiagnosticField()}")
                event.details.toDiagnosticDetailsOrNull()?.let { details ->
                    append(" | ").append(details)
                }
                appendLine()
            }
            val omittedEvents = state.recentEvents.size - HEALTH_DIAGNOSTIC_EVENT_MAX_COUNT
            if (omittedEvents > 0) {
                appendLine("- $omittedEvents additional events omitted.")
            }
        }
    }.trim()

private fun String.toDiagnosticField(): String = toBoundedDiagnosticText(HEALTH_DIAGNOSTIC_FIELD_MAX_CHARS) ?: "Unavailable"

private fun String?.toDiagnosticFieldOrNull(): String? = toBoundedDiagnosticText(HEALTH_DIAGNOSTIC_FIELD_MAX_CHARS)

private fun String?.toDiagnosticDetailsOrNull(): String? = toBoundedDiagnosticText(HEALTH_DIAGNOSTIC_DETAILS_MAX_CHARS)

private fun List<String>.toDiagnosticListText(): String {
    if (isEmpty()) {
        return "None"
    }
    val visible =
        take(HEALTH_DIAGNOSTIC_LIST_MAX_ITEMS)
            .map { item ->
                item.toBoundedDiagnosticText(HEALTH_DIAGNOSTIC_LIST_ITEM_MAX_CHARS)
                    ?: "Unavailable"
            }
    val omitted = size - visible.size
    return buildString {
        append(visible.joinToString())
        if (omitted > 0) {
            append(" (+").append(omitted).append(" omitted)")
        }
    }
}

private fun String?.toBoundedDiagnosticText(maxChars: Int): String? {
    val normalized = this?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (normalized.length <= maxChars) {
        return normalized
    }
    val suffix = "… [truncated]"
    val prefixLength = (maxChars - suffix.length).coerceAtLeast(0)
    return normalized.take(prefixLength).trimEnd() + suffix
}
