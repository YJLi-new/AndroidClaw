package ai.androidclaw.feature.health

import ai.androidclaw.R
import ai.androidclaw.data.model.EventLevel
import ai.androidclaw.ui.components.ClawCard
import ai.androidclaw.ui.components.ClawFactRow
import ai.androidclaw.ui.components.ClawIconButton
import ai.androidclaw.ui.components.ClawSectionHeader
import ai.androidclaw.ui.components.ClawStatusBadge
import ai.androidclaw.ui.components.ClawTopBar
import ai.androidclaw.ui.components.NoticeTone
import ai.androidclaw.ui.format.rememberDateTimeFormatter
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.io.File

/** Former Health tab: provider, scheduler and runtime diagnostics (Settings > Diagnostics). */
@Composable
fun DiagnosticsScreen(
    viewModel: HealthViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    // Resources (not context.getString) so a language switch is picked up by recomposition.
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<HealthDiagnosticsExportPayload?>(null) }
    val formatter = rememberDateTimeFormatter()
    val notify: (Int, String?) -> Unit = { resource, arg ->
        scope.launch {
            snackbarHostState.showSnackbar(
                if (arg == null) resources.getString(resource) else resources.getString(resource, arg),
            )
        }
    }
    val exportLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.StartActivityForResult()) { result ->
            val payload = pendingExport
            pendingExport = null
            val uri = result.data?.data
            if (payload == null) return@rememberLauncherForActivityResult
            if (result.resultCode != Activity.RESULT_OK || uri == null) {
                notify(R.string.diag_notice_export_cancelled, null)
                return@rememberLauncherForActivityResult
            }
            runCatching { writeDiagnosticsPayload(context, uri, payload) }
                .onSuccess { notify(R.string.diag_notice_saved, payload.fileName) }
                .onFailure { error -> notify(R.string.diag_error_export, error.message ?: payload.fileName) }
        }

    LaunchedEffect(Unit) {
        viewModel.refreshDiagnostics()
    }

    Scaffold(
        modifier = Modifier.testTag("diagnosticsScreen"),
        contentWindowInsets = WindowInsets.navigationBars,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ClawTopBar(
                title = stringResource(R.string.diag_title),
                titleTestTag = "diagnosticsHeading",
                onBack = onBack,
                actions = {
                    ClawIconButton(
                        iconRes = R.drawable.ic_refresh,
                        contentDescription = stringResource(R.string.diag_action_refresh),
                        onClick = viewModel::refreshDiagnostics,
                    )
                    ClawIconButton(
                        iconRes = R.drawable.ic_copy,
                        contentDescription = stringResource(R.string.diag_action_copy),
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("AndroidClaw diagnostics", buildDiagnosticsReport(state))),
                                )
                                notify(R.string.diag_notice_copied, null)
                            }
                        },
                        modifier = Modifier.testTag("diagnosticsCopyButton"),
                    )
                    Box {
                        ClawIconButton(
                            iconRes = R.drawable.ic_more_vert,
                            contentDescription = stringResource(R.string.action_more_options),
                            onClick = { menuOpen = true },
                        )
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.diag_action_export)) },
                                onClick = {
                                    menuOpen = false
                                    val payload = buildDiagnosticsExportPayload(state)
                                    pendingExport = payload
                                    exportLauncher.launch(createDiagnosticsExportIntent(payload))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.diag_action_share)) },
                                onClick = {
                                    menuOpen = false
                                    val payload = buildDiagnosticsExportPayload(state)
                                    runCatching {
                                        val uri = writeDiagnosticsShareFile(context, payload)
                                        launchDiagnosticsShareFile(context, payload, uri)
                                    }.onFailure { error -> notify(R.string.diag_error_share, error.message ?: payload.fileName) }
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "provider") {
                ClawSectionHeader(stringResource(R.string.diag_section_provider))
                DiagnosticsCard {
                    ClawFactRow(stringResource(R.string.diag_active_provider), state.providerId)
                    ClawFactRow(stringResource(R.string.diag_network), networkLabel(state))
                    ClawFactRow(stringResource(R.string.diag_status), providerStatusLabel(state.providerStatusKind))
                    state.lastProviderIssue?.let { ClawFactRow(stringResource(R.string.diag_last_issue), it) }
                }
            }
            item(key = "scheduler") {
                val scheduler = state.schedulerDiagnostics
                ClawSectionHeader(stringResource(R.string.diag_section_scheduler))
                DiagnosticsCard {
                    ClawFactRow(stringResource(R.string.diag_schedule_kinds), state.supportedKinds.joinToString())
                    ClawFactRow(stringResource(R.string.diag_exact_supported), yesNo(scheduler.supportsExactAlarms))
                    ClawFactRow(stringResource(R.string.diag_exact_granted), yesNo(scheduler.exactAlarmGranted))
                    ClawFactRow(
                        stringResource(R.string.diag_notification_permission),
                        when {
                            !scheduler.notificationVisibility.runtimePermissionRequired -> stringResource(R.string.diag_not_required)
                            scheduler.notificationVisibility.runtimePermissionGranted -> stringResource(R.string.diag_granted)
                            else -> stringResource(R.string.diag_denied)
                        },
                    )
                    ClawFactRow(
                        stringResource(R.string.diag_notifications_enabled),
                        yesNo(scheduler.notificationVisibility.appNotificationsEnabled),
                    )
                    ClawFactRow(
                        stringResource(R.string.diag_standby_bucket),
                        scheduler.standbyBucket?.label ?: stringResource(R.string.diag_unavailable),
                    )
                    if (scheduler.isRestrictedBucket) {
                        Text(
                            text = stringResource(R.string.diag_restricted_bucket),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            item(key = "automation") {
                ClawSectionHeader(stringResource(R.string.diag_section_automation))
                DiagnosticsCard {
                    ClawFactRow(
                        stringResource(R.string.diag_last_wake),
                        state.lastSchedulerWake?.let(formatter::format) ?: stringResource(R.string.diag_none),
                    )
                    ClawFactRow(
                        stringResource(R.string.diag_last_result),
                        state.lastAutomationResult ?: stringResource(R.string.diag_none),
                    )
                    ClawFactRow(
                        stringResource(R.string.diag_last_stop),
                        state.lastWorkerStopReason ?: stringResource(R.string.diag_none),
                    )
                }
            }
            item(key = "voice") {
                ClawSectionHeader(stringResource(R.string.diag_section_voice))
                DiagnosticsCard { VoiceDiagnosticsRows() }
            }
            item(key = "tools") {
                ClawSectionHeader(stringResource(R.string.diag_section_tools, state.tools.size))
                DiagnosticsCard {
                    Text(
                        text = state.tools.joinToString(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            state.lastCrashSummary?.let { crashSummary ->
                item(key = "crash") {
                    ClawSectionHeader(stringResource(R.string.diag_section_crash))
                    DiagnosticsCard {
                        Text(crashSummary, style = MaterialTheme.typography.bodyMedium)
                        state.lastCrashStackTrace?.takeIf { it.isNotBlank() }?.let { trace ->
                            Text(
                                text = trace,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 12,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = viewModel::clearCrashNotice) {
                            Text(stringResource(R.string.diag_action_clear_crash))
                        }
                    }
                }
            }
            item(key = "events-header") {
                ClawSectionHeader(stringResource(R.string.diag_section_events))
                if (state.recentEvents.isEmpty()) {
                    Text(
                        text = stringResource(R.string.diag_no_events),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.recentEvents, key = { it.id }) { event ->
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ClawStatusBadge(
                            text = event.level.name,
                            tone =
                                when (event.level) {
                                    EventLevel.Error -> NoticeTone.Error
                                    EventLevel.Warn -> NoticeTone.Warning
                                    else -> NoticeTone.Info
                                },
                        )
                        Text(
                            text = "${event.category.name} · ${formatter.format(event.timestamp)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(event.message, style = MaterialTheme.typography.bodyMedium)
                    event.details?.takeIf { it.isNotBlank() }?.let { details ->
                        Text(
                            text = details,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            item(key = "bug-report") {
                Text(
                    text = stringResource(R.string.diag_bug_report_help),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(content: @Composable () -> Unit) {
    ClawCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun yesNo(value: Boolean): String = stringResource(if (value) R.string.diag_yes else R.string.diag_no)

@Composable
private fun providerStatusLabel(kind: ProviderStatusKind): String =
    stringResource(
        when (kind) {
            ProviderStatusKind.Fake -> R.string.diag_provider_fake
            ProviderStatusKind.Offline -> R.string.diag_provider_offline
            ProviderStatusKind.Unvalidated -> R.string.diag_provider_unvalidated
            ProviderStatusKind.LastIssue -> R.string.diag_provider_last_issue
            ProviderStatusKind.Metered -> R.string.diag_provider_metered
            ProviderStatusKind.Ready -> R.string.diag_provider_ready
        },
    )

private fun createDiagnosticsExportIntent(payload: HealthDiagnosticsExportPayload): Intent =
    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = payload.mimeType
        putExtra(Intent.EXTRA_TITLE, payload.fileName)
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
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = payload.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, payload.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.diag_share_chooser)))
}

/** Localized network state; falls back to the runtime summary before the first snapshot. */
@Composable
private fun networkLabel(state: HealthUiState): String {
    val network = state.network ?: return state.networkSummary
    return when {
        !network.isConnected -> stringResource(R.string.settings_network_offline)
        !network.isValidated -> stringResource(R.string.settings_network_unvalidated)
        network.isMetered ->
            stringResource(R.string.settings_network_online) + " · " + stringResource(R.string.settings_network_metered)
        else -> stringResource(R.string.settings_network_online)
    }
}
