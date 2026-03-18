package ai.androidclaw.feature.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.androidclaw.ui.components.ScreenHeader
import java.time.format.DateTimeFormatter

@Composable
fun HealthScreen(viewModel: HealthViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    var diagnosticsNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshDiagnostics()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(
            title = "Health",
            subtitle = "Inspect provider, scheduler, and recent runtime diagnostics in one place.",
            titleTestTag = "healthHeading",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::refreshDiagnostics) {
                Text("Refresh diagnostics")
            }
            Button(
                onClick = {
                    clipboardManager.setText(AnnotatedString(buildDiagnosticsReport(state)))
                    diagnosticsNotice = "Diagnostics copied to clipboard."
                },
            ) {
                Text("Copy diagnostics")
            }
        }
        diagnosticsNotice?.let { notice ->
            HealthCard(
                title = "Diagnostics",
                body = notice,
            )
        }
        HealthCard(
            title = "Provider",
            body = buildString {
                append("Active provider: ").append(state.providerId)
                append("\nNetwork: ").append(state.networkSummary)
                append("\nStatus: ").append(state.providerStatus)
                state.lastProviderIssue?.let { issue ->
                    append("\nLast issue: ").append(issue)
                }
            },
        )
        HealthCard(
            title = "Scheduler",
            body = buildString {
                append("Kinds: ").append(state.supportedKinds.joinToString())
                append("\nExact alarms supported: ").append(state.schedulerDiagnostics.supportsExactAlarms)
                append("\nExact alarm granted: ").append(state.schedulerDiagnostics.exactAlarmGranted)
                append(
                    "\nNotification permission: ",
                ).append(
                    if (state.schedulerDiagnostics.notificationVisibility.runtimePermissionRequired) {
                        if (state.schedulerDiagnostics.notificationVisibility.runtimePermissionGranted) {
                            "granted"
                        } else {
                            "denied"
                        }
                    } else {
                        "not required"
                    },
                )
                append(
                    "\nApp notifications enabled: ",
                ).append(state.schedulerDiagnostics.notificationVisibility.appNotificationsEnabled)
                append(
                    "\nStandby bucket: ${
                        state.schedulerDiagnostics.standbyBucket?.label ?: "Unavailable"
                    }",
                )
                if (state.schedulerDiagnostics.isRestrictedBucket) {
                    append("\nApp is in restricted bucket; background work may be throttled.")
                }
                state.schedulerDiagnostics.preciseReminderVisibilityWarning?.let { warning ->
                    append("\n").append(warning)
                }
            },
        )
        HealthCard(
            title = "Automation diagnostics",
            body = buildString {
                append(
                    "Last scheduler wake: ${
                        state.lastSchedulerWake?.let(DateTimeFormatter.ISO_INSTANT::format) ?: "None"
                    }",
                )
                append("\nLast automation result: ").append(state.lastAutomationResult ?: "None")
                append("\nLast worker stop reason: ").append(state.lastWorkerStopReason ?: "None")
            },
        )
        HealthCard(
            title = "Tool registry",
            body = state.tools.joinToString(),
        )
        state.lastCrashSummary?.let { crashSummary ->
            HealthCard(
                title = "Last crash",
                body = buildString {
                    append(crashSummary)
                    state.lastCrashStackTrace?.takeIf { it.isNotBlank() }?.let { stackTrace ->
                        append("\n\n").append(stackTrace)
                    }
                },
            )
            Button(onClick = viewModel::clearCrashNotice) {
                Text("Clear crash notice")
            }
        }
        HealthCard(
            title = "Bug reports",
            body = state.bugReportInstructions,
        )
        if (state.recentEvents.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.recentEvents, key = { it.id }) { event ->
                    HealthCard(
                        title = "${event.category} ${event.level}",
                        body = buildString {
                            append(event.message)
                            event.details?.takeIf { it.isNotBlank() }?.let { details ->
                                append("\n").append(details)
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun buildDiagnosticsReport(state: HealthUiState): String {
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

@Composable
private fun HealthCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
