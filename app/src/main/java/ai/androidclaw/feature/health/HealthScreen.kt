package ai.androidclaw.feature.health

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.FileProvider
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.androidclaw.ui.components.ScreenHeader
import java.io.File
import java.time.format.DateTimeFormatter

@Composable
fun HealthScreen(viewModel: HealthViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var diagnosticsNotice by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<HealthDiagnosticsExportPayload?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val payload = pendingExport
        pendingExport = null
        if (payload == null) return@rememberLauncherForActivityResult
        if (result.resultCode != Activity.RESULT_OK) {
            diagnosticsNotice = "Diagnostics export cancelled."
            return@rememberLauncherForActivityResult
        }
        val uri = result.data?.data
        if (uri == null) {
            diagnosticsNotice = "Failed to export diagnostics: no file destination selected."
            return@rememberLauncherForActivityResult
        }
        runCatching {
            writeDiagnosticsPayload(context, uri, payload)
        }.onSuccess {
            diagnosticsNotice = "Saved ${payload.fileName}."
        }.onFailure { error ->
            diagnosticsNotice = "Failed to export diagnostics: ${error.message ?: "unknown error"}."
        }
    }

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
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Button(onClick = viewModel::refreshDiagnostics) {
                    Text("Refresh diagnostics")
                }
            }
            item {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(buildDiagnosticsReport(state)))
                        diagnosticsNotice = "Diagnostics copied to clipboard."
                    },
                ) {
                    Text("Copy diagnostics")
                }
            }
            item {
                Button(
                    onClick = {
                        val payload = buildDiagnosticsExportPayload(state)
                        pendingExport = payload
                        exportLauncher.launch(createDiagnosticsExportIntent(payload))
                    },
                ) {
                    Text("Export diagnostics")
                }
            }
            item {
                Button(
                    onClick = {
                        val payload = buildDiagnosticsExportPayload(state)
                        runCatching {
                            val uri = writeDiagnosticsShareFile(context, payload)
                            launchDiagnosticsShareFile(context, payload, uri)
                        }.onSuccess {
                            diagnosticsNotice = "Opening share sheet."
                        }.onFailure { error ->
                            diagnosticsNotice = "Failed to share diagnostics: ${error.message ?: "unknown error"}."
                        }
                    },
                ) {
                    Text("Share diagnostics")
                }
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

private fun createDiagnosticsExportIntent(payload: HealthDiagnosticsExportPayload): Intent {
    return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = payload.mimeType
        putExtra(Intent.EXTRA_TITLE, payload.fileName)
    }
}

private fun writeDiagnosticsPayload(
    context: Context,
    uri: Uri,
    payload: HealthDiagnosticsExportPayload,
) {
    context.contentResolver.openOutputStream(uri)?.bufferedWriter().use { writer ->
        requireNotNull(writer) { "Unable to open destination for ${payload.fileName}" }
        writer.write(payload.content)
    }
}

private fun writeDiagnosticsShareFile(
    context: Context,
    payload: HealthDiagnosticsExportPayload,
): Uri {
    val exportDirectory = File(context.cacheDir, "chat-exports").apply { mkdirs() }
    val exportFile = File(exportDirectory, payload.fileName)
    exportFile.writeText(payload.content)
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.chat-export-provider",
        exportFile,
    )
}

private fun launchDiagnosticsShareFile(
    context: Context,
    payload: HealthDiagnosticsExportPayload,
    uri: Uri,
) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = payload.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, payload.fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share diagnostics file"))
}
