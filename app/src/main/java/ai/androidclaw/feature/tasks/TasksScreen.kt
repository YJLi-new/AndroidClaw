package ai.androidclaw.feature.tasks

import ai.androidclaw.R
import ai.androidclaw.data.model.Task
import ai.androidclaw.data.model.TaskRun
import ai.androidclaw.feature.chat.sessionDisplayTitle
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedulingPath
import ai.androidclaw.runtime.scheduler.schedulingDecision
import ai.androidclaw.ui.components.ClawCard
import ai.androidclaw.ui.components.ClawConfirmDialog
import ai.androidclaw.ui.components.ClawEmptyState
import ai.androidclaw.ui.components.ClawIcon
import ai.androidclaw.ui.components.ClawIconButton
import ai.androidclaw.ui.components.ClawNotice
import ai.androidclaw.ui.components.ClawStatusBadge
import ai.androidclaw.ui.components.ClawTopBar
import ai.androidclaw.ui.components.NoticeTone
import ai.androidclaw.ui.format.rememberDateTimeFormatter
import ai.androidclaw.ui.text.asString
import ai.androidclaw.ui.text.resolve
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.format.DateTimeFormatter

@Composable
fun TasksScreen(
    viewModel: TasksViewModel,
    onCreateTask: () -> Unit,
    onEditTask: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<Task?>(null) }
    val formatter = rememberDateTimeFormatter()

    LaunchedEffect(Unit) {
        viewModel.refreshDiagnostics()
    }
    LaunchedEffect(state.actionMessage) {
        val message = state.actionMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.resolve(context))
        viewModel.clearActionMessage()
    }

    Scaffold(
        modifier = Modifier.testTag("tasksScreen"),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            ClawTopBar(
                title = stringResource(R.string.tasks_title),
                titleTestTag = "tasksHeading",
                actions = {
                    ClawIconButton(
                        iconRes = R.drawable.ic_refresh,
                        contentDescription = stringResource(R.string.tasks_action_refresh),
                        onClick = viewModel::refreshDiagnostics,
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateTask,
                modifier = Modifier.testTag("createTaskFab"),
                icon = { ClawIcon(iconRes = R.drawable.ic_add, contentDescription = null) },
                text = { Text(stringResource(R.string.tasks_action_new)) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .testTag("tasksList"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.needsExactAlarmPermission) {
                item(key = "exact-alarm-hint") {
                    ClawNotice(
                        text = stringResource(R.string.tasks_exact_alarm_hint),
                        tone = NoticeTone.Warning,
                        actionLabel = stringResource(R.string.tasks_action_allow),
                        onAction = { context.startActivity(buildExactAlarmSettingsIntent(context)) },
                    )
                }
            }
            if (state.notificationsBlocked && state.tasks.isNotEmpty()) {
                item(key = "notification-hint") {
                    ClawNotice(
                        text = stringResource(R.string.tasks_notifications_hint),
                        tone = NoticeTone.Info,
                        actionLabel = stringResource(R.string.tasks_action_notification_settings),
                        onAction = { context.startActivity(buildNotificationSettingsIntent(context)) },
                    )
                }
            }
            if (state.loaded && state.tasks.isEmpty()) {
                item(key = "empty") {
                    ClawEmptyState(
                        iconRes = R.drawable.ic_schedule,
                        title = stringResource(R.string.tasks_empty_title),
                        body = stringResource(R.string.tasks_empty_body),
                        modifier = Modifier.testTag("tasksEmptyState"),
                    )
                }
            }
            items(state.tasks, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    state = state,
                    recentRuns = state.recentRunsByTaskId[task.id].orEmpty(),
                    formatter = formatter,
                    onEdit = { onEditTask(task.id) },
                    onToggle = { enabled -> viewModel.setEnabled(task.id, enabled) },
                    onRunNow = { viewModel.runNow(task.id) },
                    onDelete = { pendingDelete = task },
                )
            }
        }
    }

    pendingDelete?.let { task ->
        ClawConfirmDialog(
            title = stringResource(R.string.tasks_delete_title),
            text = stringResource(R.string.tasks_delete_body, task.name),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                viewModel.deleteTask(task.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun TaskCard(
    task: Task,
    state: TasksUiState,
    recentRuns: List<TaskRun>,
    formatter: DateTimeFormatter,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var historyOpen by rememberSaveable(task.id) { mutableStateOf(false) }
    val latestRun = recentRuns.firstOrNull()
    val enabledLabel = stringResource(if (task.enabled) R.string.tasks_state_enabled else R.string.tasks_state_disabled)
    ClawCard(
        modifier = Modifier.testTag("taskCard-${task.id}"),
        onClick = onEdit,
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = scheduleSummary(task.schedule, formatter),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = task.enabled,
                    onCheckedChange = onToggle,
                    modifier =
                        Modifier
                            .testTag("taskEnabledSwitch-${task.id}")
                            .semantics { contentDescription = enabledLabel },
                )
            }
            Text(
                text = task.prompt,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TaskMeta(task = task, state = state, formatter = formatter)
            latestRun?.let { run ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ClawStatusBadge(text = runStatusLabel(run.status), tone = runStatusTone(run.status))
                    Text(
                        text = stringResource(R.string.tasks_last_run, formatter.format(run.finishedAt ?: run.scheduledAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onRunNow,
                    modifier = Modifier.testTag("runTaskNow-${task.id}"),
                ) {
                    ClawIcon(iconRes = R.drawable.ic_play, contentDescription = null)
                    Text(stringResource(R.string.tasks_action_run_now), modifier = Modifier.padding(start = 6.dp))
                }
                if (recentRuns.isNotEmpty()) {
                    TextButton(onClick = { historyOpen = !historyOpen }) {
                        Text(
                            stringResource(
                                if (historyOpen) R.string.tasks_action_hide_history else R.string.tasks_action_show_history,
                                recentRuns.size,
                            ),
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f))
                Box {
                    ClawIconButton(
                        iconRes = R.drawable.ic_more_vert,
                        contentDescription = stringResource(R.string.tasks_action_more, task.name),
                        onClick = { menuOpen = true },
                        modifier = Modifier.testTag("taskMenu-${task.id}"),
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_edit)) },
                            leadingIcon = { ClawIcon(iconRes = R.drawable.ic_edit, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            modifier = Modifier.testTag("deleteTask-${task.id}"),
                            text = { Text(stringResource(R.string.action_delete)) },
                            leadingIcon = { ClawIcon(iconRes = R.drawable.ic_delete, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
            if (historyOpen && recentRuns.isNotEmpty()) {
                RunHistory(task = task, runs = recentRuns, state = state, formatter = formatter)
            }
        }
    }
}

@Composable
private fun TaskMeta(
    task: Task,
    state: TasksUiState,
    formatter: DateTimeFormatter,
) {
    val decision = task.schedulingDecision(state.diagnostics)
    val parts =
        buildList {
            add(executionModeLabel(task.executionMode))
            if (task.executionMode == TaskExecutionMode.MainSession) {
                val target =
                    task.targetSessionId?.let { id -> state.sessions.firstOrNull { it.id == id } }
                        ?: state.sessions.firstOrNull { it.isMain }
                target?.let { add(sessionDisplayTitle(it.title, it.isMain)) }
            }
            add(
                when {
                    !task.precise -> stringResource(R.string.tasks_timing_approximate)
                    decision.path == TaskSchedulingPath.ExactAlarm -> stringResource(R.string.tasks_timing_exact)
                    else -> stringResource(R.string.tasks_timing_exact_unavailable)
                },
            )
        }
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (task.enabled) {
        Text(
            text =
                task.nextRunAt?.let { stringResource(R.string.tasks_next_run, formatter.format(it)) }
                    ?: stringResource(R.string.tasks_next_run_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun RunHistory(
    task: Task,
    runs: List<TaskRun>,
    state: TasksUiState,
    formatter: DateTimeFormatter,
) {
    Column(
        modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HorizontalDivider()
        Text(
            text = retryStateText(task, runs.firstOrNull(), formatter::format).asString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        runs.forEach { run ->
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ClawStatusBadge(text = runStatusLabel(run.status), tone = runStatusTone(run.status))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = formatter.format(run.startedAt ?: run.scheduledAt),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val detail = run.errorMessage?.takeIf { it.isNotBlank() } ?: run.resultSummary?.takeIf { it.isNotBlank() }
                    detail?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    state.runUsageSummaryByRunId[run.id]?.let { usage ->
                        Text(
                            text = usage.asString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
