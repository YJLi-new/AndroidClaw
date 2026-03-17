package ai.androidclaw.feature.tasks

import android.content.Context
import ai.androidclaw.data.model.Task
import ai.androidclaw.data.model.TaskRun
import ai.androidclaw.runtime.scheduler.CronExpression
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedule
import ai.androidclaw.runtime.scheduler.TaskSchedulingDecision
import ai.androidclaw.runtime.scheduler.TaskSchedulingPath
import ai.androidclaw.runtime.scheduler.preciseSchedulingWarnings
import ai.androidclaw.runtime.scheduler.schedulingDecision
import ai.androidclaw.runtime.scheduler.userVisiblePreciseWarnings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class TaskScheduleKindUi {
    Once,
    Interval,
    Cron,
}

@Composable
fun TasksScreen(viewModel: TasksViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var scheduleKind by rememberSaveable { mutableStateOf(TaskScheduleKindUi.Once) }
    var onceAt by rememberSaveable { mutableStateOf(Instant.now().plusSeconds(300).toString()) }
    var intervalMinutes by rememberSaveable { mutableStateOf("60") }
    var cronExpression by rememberSaveable { mutableStateOf("0 9 * * 1-5") }
    var precise by rememberSaveable { mutableStateOf(false) }
    var executionMode by rememberSaveable { mutableStateOf(TaskExecutionMode.MainSession) }
    var selectedSessionId by rememberSaveable { mutableStateOf("") }
    var formMessage by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshDiagnostics()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("tasksScreen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Tasks",
                modifier = Modifier
                    .semantics { heading() }
                    .testTag("tasksHeading"),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::refreshDiagnostics) {
                    Text("Refresh diagnostics")
                }
                Button(onClick = viewModel::clearActionMessage) {
                    Text("Clear status")
                }
            }
        }
        item {
            SchedulerCard(
                title = "Supported kinds",
                body = state.capabilities.supportedKinds.joinToString(),
            )
        }
        item {
            SchedulerCard(
                title = "Minimum background interval",
                body = "${state.capabilities.minimumBackgroundInterval.toMinutes()} minutes",
            )
        }
        item {
            SchedulerCard(
                title = "Exact alarm status",
                body = buildString {
                    append("Supported: ").append(state.diagnostics.supportsExactAlarms)
                    append("\nGranted: ").append(state.diagnostics.exactAlarmGranted)
                    append("\nNotification permission: ").append(
                        if (state.diagnostics.notificationVisibility.runtimePermissionRequired) {
                            if (state.diagnostics.notificationVisibility.runtimePermissionGranted) {
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
                    ).append(state.diagnostics.notificationVisibility.appNotificationsEnabled)
                    append("\nStandby bucket: ").append(state.diagnostics.standbyBucket?.label ?: "Unavailable")
                    if (state.diagnostics.isRestrictedBucket) {
                        append("\nApp is in restricted bucket; background work may be delayed.")
                    }
                    state.diagnostics.preciseReminderVisibilityWarning?.let { warning ->
                        append("\n").append(warning)
                    }
                },
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Task notifications", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Scheduled task results and failures use separate Android notification channels so success noise can be muted without hiding failures.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = { context.startActivity(buildNotificationSettingsIntent(context)) },
                    ) {
                        Text("Open notification settings")
                    }
                }
            }
        }
        item {
            SchedulerCard(
                title = "Next @daily preview",
                body = state.nextDailyPreview?.let(DateTimeFormatter.ISO_INSTANT::format) ?: "Unavailable",
            )
        }
        item {
            SchedulerCard(
                title = "Next 9am weekday cron preview",
                body = state.nextWeekdayPreview?.let(DateTimeFormatter.ISO_INSTANT::format) ?: "Unavailable",
            )
        }
        if (state.actionMessage != null) {
            item {
                SchedulerCard(
                    title = "Task action",
                    body = state.actionMessage.orEmpty(),
                )
            }
        }
        if (formMessage != null) {
            item {
                SchedulerCard(
                    title = "Create task",
                    body = formMessage.orEmpty(),
                )
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Create Task", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Task name") },
                    )
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        label = { Text("Prompt") },
                    )
                    Text("Schedule kind", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(
                            items = TaskScheduleKindUi.entries.toList(),
                            key = { it.name },
                        ) { kind ->
                            FilterChip(
                                selected = scheduleKind == kind,
                                onClick = { scheduleKind = kind },
                                label = { Text(kind.name) },
                            )
                        }
                    }
                    when (scheduleKind) {
                        TaskScheduleKindUi.Once -> {
                            OutlinedTextField(
                                value = onceAt,
                                onValueChange = { onceAt = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Run at (ISO-8601 UTC)") },
                            )
                        }
                        TaskScheduleKindUi.Interval -> {
                            OutlinedTextField(
                                value = intervalMinutes,
                                onValueChange = { intervalMinutes = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Repeat every minutes") },
                            )
                        }
                        TaskScheduleKindUi.Cron -> {
                            OutlinedTextField(
                                value = cronExpression,
                                onValueChange = { cronExpression = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Cron expression") },
                            )
                        }
                    }
                    Text("Execution mode", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(
                            items = TaskExecutionMode.values().toList(),
                            key = { it.name },
                        ) { mode ->
                            FilterChip(
                                selected = executionMode == mode,
                                onClick = { executionMode = mode },
                                label = { Text(mode.name) },
                            )
                        }
                    }
                    Text("Target session", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = selectedSessionId.isBlank(),
                                onClick = { selectedSessionId = "" },
                                label = { Text("Main session") },
                            )
                        }
                        items(state.sessions, key = { it.id }) { session ->
                            FilterChip(
                                selected = selectedSessionId == session.id,
                                onClick = { selectedSessionId = session.id },
                                label = { Text(session.title) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (precise) "Precise exact-alarm eligible" else "Approximate WorkManager",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(
                            modifier = Modifier.semantics {
                                stateDescription = if (precise) {
                                    "Precise scheduling enabled"
                                } else {
                                    "Approximate scheduling enabled"
                                }
                            },
                            checked = precise,
                            onCheckedChange = { precise = it },
                        )
                    }
                    if (precise) {
                        val creationWarnings = state.diagnostics.preciseSchedulingWarnings()
                        if (creationWarnings.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SchedulerCard(
                                    title = "Precise reminder warning",
                                    body = creationWarnings.joinToString("\n"),
                                )
                                if (state.diagnostics.supportsExactAlarms && !state.diagnostics.exactAlarmGranted ||
                                    state.diagnostics.preciseReminderVisibilityWarning != null
                                ) {
                                    ExactAlarmActionRow(
                                        diagnostics = state.diagnostics,
                                        context = context,
                                    )
                                }
                            }
                        }
                    }
                    Button(
                        onClick = {
                            val minimumMinutes = state.capabilities.minimumBackgroundInterval.toMinutes()
                            val schedule = runCatching {
                                when (scheduleKind) {
                                    TaskScheduleKindUi.Once -> {
                                        val scheduledAt = Instant.parse(onceAt.trim())
                                        require(scheduledAt.isAfter(Instant.now())) {
                                            "Once tasks must be scheduled in the future."
                                        }
                                        TaskSchedule.Once(scheduledAt)
                                    }
                                    TaskScheduleKindUi.Interval -> {
                                        val minutes = intervalMinutes.trim().toLong()
                                        require(minutes >= minimumMinutes) {
                                            "Intervals must be at least $minimumMinutes minutes."
                                        }
                                        TaskSchedule.Interval(
                                            anchorAt = Instant.now(),
                                            repeatEvery = Duration.ofMinutes(minutes),
                                        )
                                    }
                                    TaskScheduleKindUi.Cron -> TaskSchedule.Cron(
                                        expression = CronExpression.parse(cronExpression.trim()),
                                        zoneId = ZoneId.systemDefault(),
                                    )
                                }
                            }.getOrElse { error ->
                                formMessage = error.message ?: "Invalid task schedule."
                                return@Button
                            }

                            if (name.trim().isBlank() || prompt.trim().isBlank()) {
                                formMessage = "Task name and prompt are required."
                                return@Button
                            }

                            viewModel.createTask(
                                name = name.trim(),
                                prompt = prompt.trim(),
                                schedule = schedule,
                                executionMode = executionMode,
                                targetSessionId = selectedSessionId.takeIf { it.isNotBlank() },
                                precise = precise,
                            )
                            name = ""
                            prompt = ""
                            formMessage = null
                        },
                    ) {
                        Text("Create task")
                    }
                }
            }
        }
        if (state.tasks.isEmpty()) {
            item {
                SchedulerCard(
                    title = "Saved tasks",
                    body = "No tasks yet.",
                )
            }
        } else {
            items(state.tasks, key = { it.id }) { task ->
                val decision = task.schedulingDecision(state.diagnostics)
                TaskCard(
                    task = task,
                    decision = decision,
                    preciseWarnings = task.userVisiblePreciseWarnings(state.diagnostics),
                    restrictedBucket = state.diagnostics.isRestrictedBucket,
                    diagnostics = state.diagnostics,
                    recentRuns = state.recentRunsByTaskId[task.id].orEmpty(),
                    onToggleEnabled = { viewModel.toggleEnabled(task.id) },
                    onRunNow = { viewModel.runNow(task.id) },
                    onDelete = { viewModel.deleteTask(task.id) },
                    context = context,
                )
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: Task,
    decision: TaskSchedulingDecision,
    preciseWarnings: List<String>,
    restrictedBucket: Boolean,
    diagnostics: ai.androidclaw.runtime.scheduler.SchedulerDiagnostics,
    recentRuns: List<TaskRun>,
    onToggleEnabled: () -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit,
    context: Context,
) {
    val latestRun = recentRuns.firstOrNull()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(task.name, style = MaterialTheme.typography.titleMedium)
            Text(task.prompt, style = MaterialTheme.typography.bodyMedium)
            TaskFactRow(
                label = "Execution",
                value = when (task.executionMode) {
                    TaskExecutionMode.MainSession -> "Main session"
                    TaskExecutionMode.IsolatedSession -> "Isolated session"
                },
            )
            TaskFactRow(
                label = "Target",
                value = task.targetSessionId ?: "Main session",
            )
            TaskFactRow(
                label = "Delivery path",
                value = when (decision.path) {
                    TaskSchedulingPath.ExactAlarm -> "Precise exact alarm"
                    TaskSchedulingPath.WorkManagerApproximate -> {
                        if (task.precise) {
                            "Approximate WorkManager fallback"
                        } else {
                            "Approximate WorkManager"
                        }
                    }
                },
            )
            TaskFactRow(
                label = "Next wake",
                value = task.nextRunAt?.let(DateTimeFormatter.ISO_INSTANT::format) ?: "Unscheduled",
            )
            TaskFactRow(
                label = "Retry state",
                value = retryStateText(task, latestRun),
            )
            latestRun?.let { run ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = when (run.status) {
                            ai.androidclaw.data.model.TaskRunStatus.Success -> MaterialTheme.colorScheme.secondaryContainer
                            ai.androidclaw.data.model.TaskRunStatus.Failure -> MaterialTheme.colorScheme.errorContainer
                            ai.androidclaw.data.model.TaskRunStatus.Skipped -> MaterialTheme.colorScheme.tertiaryContainer
                            ai.androidclaw.data.model.TaskRunStatus.Pending,
                            ai.androidclaw.data.model.TaskRunStatus.Running,
                                -> MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Latest run", style = MaterialTheme.typography.labelLarge)
                        TaskFactRow(label = "Status", value = run.status.name)
                        TaskFactRow(
                            label = "Scheduled",
                            value = DateTimeFormatter.ISO_INSTANT.format(run.scheduledAt),
                        )
                        TaskFactRow(
                            label = "Finished",
                            value = run.finishedAt?.let(DateTimeFormatter.ISO_INSTANT::format) ?: "Still running",
                        )
                        run.errorCode?.let { code ->
                            TaskFactRow(label = "Error code", value = code)
                        }
                        run.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        run.resultSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            if (decision.degradedReason != null || preciseWarnings.isNotEmpty() || restrictedBucket) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Scheduling notes", style = MaterialTheme.typography.labelLarge)
                        decision.degradedReason?.let { reason ->
                            Text(reason, style = MaterialTheme.typography.bodySmall)
                        }
                        preciseWarnings
                            .filterNot { it == decision.degradedReason }
                            .forEach { warning ->
                                Text(warning, style = MaterialTheme.typography.bodySmall)
                            }
                        if (restrictedBucket) {
                            Text(
                                "App standby bucket is restricted; background work may be delayed.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            Text(
                text = if (task.enabled) "Task is enabled." else "Task is disabled.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onToggleEnabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (task.enabled) "Disable" else "Enable")
                }
                Button(
                    onClick = onRunNow,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Run now")
                }
                Button(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Delete")
                }
            }
            if (recentRuns.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Recent runs", style = MaterialTheme.typography.labelMedium)
                    recentRuns.drop(1).forEach { run ->
                        Text(
                            text = buildString {
                                append(run.status.name)
                                append(" at ")
                                append(DateTimeFormatter.ISO_INSTANT.format(run.scheduledAt))
                                run.errorCode?.let { code ->
                                    append(" (").append(code).append(')')
                                }
                                run.resultSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                                    append("\n").append(summary)
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (
                task.precise &&
                preciseWarnings.isNotEmpty() &&
                (
                    diagnostics.supportsExactAlarms && !diagnostics.exactAlarmGranted ||
                        diagnostics.preciseReminderVisibilityWarning != null
                    )
            ) {
                ExactAlarmActionRow(
                    diagnostics = diagnostics,
                    context = context,
                )
            }
        }
    }
}

internal fun retryStateText(
    task: Task,
    latestRun: TaskRun?,
): String {
    return when {
        latestRun == null -> "No runs yet"
        task.failureCount <= 0 -> "Healthy"
        task.failureCount >= task.maxRetries -> "Retries exhausted (${task.failureCount}/${task.maxRetries})"
        else -> {
            val nextWake = task.nextRunAt?.let(DateTimeFormatter.ISO_INSTANT::format) ?: "unscheduled"
            "Retry budget used ${task.failureCount}/${task.maxRetries}; next wake $nextWake"
        }
    }
}

@Composable
private fun TaskFactRow(
    label: String,
    value: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SchedulerCard(title: String, body: String) {
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

@Composable
private fun ExactAlarmActionRow(
    diagnostics: ai.androidclaw.runtime.scheduler.SchedulerDiagnostics,
    context: Context,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (diagnostics.supportsExactAlarms && !diagnostics.exactAlarmGranted) {
            Button(
                onClick = { context.startActivity(buildExactAlarmSettingsIntent(context)) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Open exact alarm access")
            }
        }
        if (diagnostics.preciseReminderVisibilityWarning != null) {
            Button(
                onClick = { context.startActivity(buildNotificationSettingsIntent(context)) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Open notification settings")
            }
        }
    }
}
