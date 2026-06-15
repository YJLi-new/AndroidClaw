package ai.androidclaw.feature.tasks

import ai.androidclaw.R
import ai.androidclaw.data.model.Task
import ai.androidclaw.data.model.TaskRun
import ai.androidclaw.data.repository.TASK_NAME_MAX_CHARS
import ai.androidclaw.data.repository.TASK_PROMPT_MAX_CHARS
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedulingDecision
import ai.androidclaw.runtime.scheduler.TaskSchedulingPath
import ai.androidclaw.runtime.scheduler.preciseSchedulingWarnings
import ai.androidclaw.runtime.scheduler.schedulingDecision
import ai.androidclaw.runtime.scheduler.userVisiblePreciseWarnings
import ai.androidclaw.ui.components.ClawActionPill
import ai.androidclaw.ui.components.ClawCard
import ai.androidclaw.ui.components.ClawChoicePill
import ai.androidclaw.ui.components.ClawFactRow
import ai.androidclaw.ui.components.ClawGreenMuted
import ai.androidclaw.ui.components.ClawIconBadge
import ai.androidclaw.ui.components.ClawInfoCard
import ai.androidclaw.ui.components.ClawInk
import ai.androidclaw.ui.components.ClawInkMuted
import ai.androidclaw.ui.components.ClawPage
import ai.androidclaw.ui.components.ClawPrimaryButton
import ai.androidclaw.ui.components.ClawScreenHeader
import ai.androidclaw.ui.components.ClawStatusDot
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    val updateBoundedFormInput: (String, Int, (String) -> Unit) -> Unit = { value, maxChars, updateValue ->
        val boundedInput = boundTaskFormInput(value, maxChars)
        updateValue(boundedInput.value)
        formMessage =
            if (boundedInput.wasTruncated) {
                TASK_FORM_INPUT_TRUNCATED_MESSAGE
            } else {
                formMessage.clearTaskFormTruncationMessage()
            }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshDiagnostics()
    }

    val createTaskFromForm = createTask@{
        val minimumMinutes = state.capabilities.minimumBackgroundInterval.toMinutes()
        val now = Instant.now()
        val schedule =
            runCatching {
                parseTaskScheduleFromForm(
                    scheduleKind = scheduleKind,
                    onceAt = onceAt,
                    intervalMinutes = intervalMinutes,
                    cronExpression = cronExpression,
                    minimumIntervalMinutes = minimumMinutes,
                    now = now,
                    zoneId = ZoneId.systemDefault(),
                )
            }.getOrElse { error ->
                formMessage = error.message ?: "Invalid task schedule."
                return@createTask
            }

        if (name.trim().isBlank() || prompt.trim().isBlank()) {
            formMessage = "Task name and prompt are required."
            return@createTask
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
    }

    ClawPage(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .testTag("tasksScreen"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                ClawScreenHeader(
                    iconRes = R.drawable.ic_nav_tasks,
                    title = "Create Task",
                    subtitle = "Schedule a one-off, interval, or cron automation.",
                    titleTestTag = "tasksHeading",
                    iconBackground = ClawGreenMuted,
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
                ClawCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("Task name", style = MaterialTheme.typography.titleSmall, color = ClawInk)
                        OutlinedTextField(
                            value = name,
                            onValueChange = { value ->
                                updateBoundedFormInput(value, TASK_NAME_MAX_CHARS) { name = it }
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("taskNameField"),
                            placeholder = { Text("Task name") },
                            singleLine = true,
                        )
                        Text("Prompt", style = MaterialTheme.typography.titleSmall, color = ClawInk)
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { value ->
                                updateBoundedFormInput(value, TASK_PROMPT_MAX_CHARS) { prompt = it }
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("taskPromptField"),
                            minLines = 4,
                            placeholder = { Text("Prompt") },
                        )
                        Text("Schedule kind", style = MaterialTheme.typography.titleSmall, color = ClawInk)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            TaskScheduleKindUi.entries.forEach { kind ->
                                ClawChoicePill(
                                    text = kind.name,
                                    selected = scheduleKind == kind,
                                    onClick = { scheduleKind = kind },
                                    modifier = Modifier.widthIn(min = 104.dp),
                                )
                            }
                        }
                        when (scheduleKind) {
                            TaskScheduleKindUi.Once -> {
                                OutlinedTextField(
                                    value = onceAt,
                                    onValueChange = { value ->
                                        updateBoundedFormInput(value, TASK_FORM_ONCE_AT_MAX_CHARS) { onceAt = it }
                                    },
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .testTag("taskOnceAtField"),
                                    label = { Text("Run at (ISO-8601 UTC)") },
                                    singleLine = true,
                                )
                            }

                            TaskScheduleKindUi.Interval -> {
                                OutlinedTextField(
                                    value = intervalMinutes,
                                    onValueChange = { value ->
                                        updateBoundedFormInput(value, TASK_FORM_INTERVAL_MINUTES_MAX_CHARS) {
                                            intervalMinutes = it
                                        }
                                    },
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .testTag("taskIntervalMinutesField"),
                                    label = { Text("Repeat every minutes") },
                                    singleLine = true,
                                )
                            }

                            TaskScheduleKindUi.Cron -> {
                                OutlinedTextField(
                                    value = cronExpression,
                                    onValueChange = { value ->
                                        updateBoundedFormInput(value, TASK_FORM_CRON_EXPRESSION_MAX_CHARS) {
                                            cronExpression = it
                                        }
                                    },
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .testTag("taskCronExpressionField"),
                                    label = { Text("Cron expression") },
                                    singleLine = true,
                                )
                            }
                        }
                    }
                }
            }
            item {
                ClawCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("Execution mode", style = MaterialTheme.typography.titleSmall, color = ClawInk)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            TaskExecutionMode.values().forEach { mode ->
                                ClawChoicePill(
                                    text = mode.name,
                                    selected = executionMode == mode,
                                    onClick = { executionMode = mode },
                                )
                            }
                        }
                        Text("Target session", style = MaterialTheme.typography.titleSmall, color = ClawInk)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ClawChoicePill(
                                text = "Main session",
                                selected = selectedSessionId.isBlank(),
                                onClick = { selectedSessionId = "" },
                            )
                            state.sessions.forEach { session ->
                                ClawChoicePill(
                                    text = session.title,
                                    selected = selectedSessionId == session.id,
                                    onClick = { selectedSessionId = session.id },
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (precise) "Precise exact-alarm eligible" else "Approximate WorkManager",
                                style = MaterialTheme.typography.titleSmall,
                                color = ClawInk,
                            )
                            Switch(
                                modifier =
                                    Modifier.semantics {
                                        stateDescription =
                                            if (precise) {
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
                                    if (state.diagnostics.supportsExactAlarms &&
                                        !state.diagnostics.exactAlarmGranted ||
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
                        ClawPrimaryButton(
                            text = "Create task",
                            onClick = createTaskFromForm,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("createTaskButton"),
                        )
                    }
                }
            }
            if (state.tasks.isEmpty()) {
                item {
                    ClawCard {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ClawIconBadge(
                                iconRes = R.drawable.ic_nav_tasks,
                                background = Color(0xFFF0F2F6),
                                iconColor = ClawInk,
                            )
                            Column {
                                Text("Saved tasks", style = MaterialTheme.typography.titleMedium, color = ClawInk)
                                Text("No tasks yet.", style = MaterialTheme.typography.bodyMedium, color = ClawInkMuted)
                            }
                        }
                    }
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
                        runUsageSummaryByRunId = state.runUsageSummaryByRunId,
                        onToggleEnabled = { viewModel.toggleEnabled(task.id) },
                        onRunNow = { viewModel.runNow(task.id) },
                        onDelete = { viewModel.deleteTask(task.id) },
                        context = context,
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ClawActionPill(
                        text = "Refresh diagnostics",
                        onClick = viewModel::refreshDiagnostics,
                        selected = true,
                        modifier = Modifier.weight(1f),
                    )
                    ClawActionPill(
                        text = "Clear status",
                        onClick = viewModel::clearActionMessage,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                ClawInfoCard(
                    title = "Next @daily preview",
                    body = state.nextDailyPreview?.let(DateTimeFormatter.ISO_INSTANT::format) ?: "Unavailable",
                    iconRes = R.drawable.ic_nav_tasks,
                    badge = if (state.nextDailyPreview != null) "Live" else null,
                )
            }
            item {
                ClawInfoCard(
                    title = "Supported kinds",
                    body = state.capabilities.supportedKinds.joinToString(),
                    iconRes = R.drawable.ic_plus_circle,
                )
            }
            item {
                ClawInfoCard(
                    title = "Minimum background interval",
                    body = "${state.capabilities.minimumBackgroundInterval.toMinutes()} minutes",
                    iconRes = R.drawable.ic_nav_health,
                )
            }
            item {
                SchedulerCard(
                    title = "Exact alarm status",
                    body =
                        buildString {
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
                            append("\nApp notifications enabled: ").append(state.diagnostics.notificationVisibility.appNotificationsEnabled)
                            append("\nStandby bucket: ").append(state.diagnostics.standbyBucket?.label ?: "Unavailable")
                            if (state.diagnostics.isRestrictedBucket) {
                                append("\nApp is in restricted bucket; background work may be delayed.")
                            }
                            state.diagnostics.preciseReminderVisibilityWarning?.let { warning ->
                                append("\n").append(warning)
                            }
                        },
                    iconRes = R.drawable.ic_nav_health,
                )
            }
            item {
                ClawInfoCard(
                    title = "Task notifications",
                    body = "Scheduled task results and failures use separate Android notification channels so success noise can be muted without hiding failures.",
                    iconRes = R.drawable.ic_nav_tasks,
                    actionLabel = "Open notification settings",
                    onAction = { context.startActivity(buildNotificationSettingsIntent(context)) },
                )
            }
            item {
                ClawInfoCard(
                    title = "Next 9am weekday cron preview",
                    body = state.nextWeekdayPreview?.let(DateTimeFormatter.ISO_INSTANT::format) ?: "Unavailable",
                    iconRes = R.drawable.ic_nav_tasks,
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
    runUsageSummaryByRunId: Map<String, String>,
    onToggleEnabled: () -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit,
    context: Context,
) {
    val latestRun = recentRuns.firstOrNull()
    val latestRunUsageSummary = latestRun?.let { runUsageSummaryByRunId[it.id] }
    ClawCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("taskCard-${task.id}"),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(task.name, style = MaterialTheme.typography.titleMedium, color = ClawInk)
                    Text(task.prompt, style = MaterialTheme.typography.bodyMedium, color = ClawInkMuted)
                }
                ClawStatusDot(active = task.enabled)
            }
            TaskFactRow(
                label = "Execution",
                value =
                    when (task.executionMode) {
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
                value =
                    when (decision.path) {
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
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                when (run.status) {
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
                        latestRunUsageSummary?.let { usageSummary ->
                            TaskFactRow(label = "Provider usage", value = usageSummary)
                        }
                    }
                }
            }
            if (decision.degradedReason != null || preciseWarnings.isNotEmpty() || restrictedBucket) {
                Card(
                    colors =
                        CardDefaults.cardColors(
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
                ClawActionPill(
                    text = if (task.enabled) "Disable" else "Enable",
                    onClick = onToggleEnabled,
                    modifier = Modifier.weight(1f),
                )
                ClawActionPill(
                    text = "Run now",
                    onClick = onRunNow,
                    modifier =
                        Modifier
                            .weight(1f)
                            .testTag("runTaskNow-${task.id}"),
                    selected = true,
                )
                ClawActionPill(
                    text = "Delete",
                    onClick = onDelete,
                    modifier =
                        Modifier
                            .weight(1f)
                            .testTag("deleteTask-${task.id}"),
                )
            }
            if (recentRuns.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Recent runs", style = MaterialTheme.typography.labelMedium)
                    recentRuns.drop(1).forEach { run ->
                        Text(
                            text =
                                buildString {
                                    append(run.status.name)
                                    append(" at ")
                                    append(DateTimeFormatter.ISO_INSTANT.format(run.scheduledAt))
                                    run.errorCode?.let { code ->
                                        append(" (").append(code).append(')')
                                    }
                                    run.resultSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                                        append("\n").append(summary)
                                    }
                                    runUsageSummaryByRunId[run.id]?.let { usageSummary ->
                                        append("\nUsage: ").append(usageSummary)
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
                    diagnostics.supportsExactAlarms &&
                        !diagnostics.exactAlarmGranted ||
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
): String =
    when {
        latestRun == null -> "No runs yet"
        task.failureCount <= 0 -> "Healthy"
        task.failureCount >= task.maxRetries -> "Retries exhausted (${task.failureCount}/${task.maxRetries})"
        else -> {
            val nextWake = task.nextRunAt?.let(DateTimeFormatter.ISO_INSTANT::format) ?: "unscheduled"
            "Retry budget used ${task.failureCount}/${task.maxRetries}; next wake $nextWake"
        }
    }

@Composable
private fun TaskFactRow(
    label: String,
    value: String,
) {
    ClawFactRow(label = label, value = value)
}

@Composable
private fun SchedulerCard(
    title: String,
    body: String,
    iconRes: Int? = null,
) {
    ClawInfoCard(
        title = title,
        body = body,
        iconRes = iconRes,
    )
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
