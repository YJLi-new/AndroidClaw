package ai.androidclaw.feature.tasks

import ai.androidclaw.R
import ai.androidclaw.data.repository.TASK_NAME_MAX_CHARS
import ai.androidclaw.data.repository.TASK_PROMPT_MAX_CHARS
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedule
import ai.androidclaw.ui.components.ClawConfirmDialog
import ai.androidclaw.ui.components.ClawIcon
import ai.androidclaw.ui.components.ClawNotice
import ai.androidclaw.ui.components.ClawSectionHeader
import ai.androidclaw.ui.components.ClawTopBar
import ai.androidclaw.ui.components.NoticeTone
import ai.androidclaw.ui.format.rememberDateFormatter
import ai.androidclaw.ui.format.rememberDateTimeFormatter
import ai.androidclaw.ui.format.rememberTimeFormatter
import ai.androidclaw.ui.text.UiText
import ai.androidclaw.ui.text.asString
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

private const val CRON_PREVIEW_COUNT = 3

/** Editor state kept across configuration changes as plain saveable values. */
private class TaskEditorFields(
    initialDate: LocalDate,
    initialTime: LocalTime,
) {
    var name by mutableStateOf("")
    var prompt by mutableStateOf("")
    var kind by mutableStateOf(TaskScheduleKindUi.Once)
    var onceDateEpochDay by mutableLongStateOf(initialDate.toEpochDay())
    var onceMinuteOfDay by mutableIntStateOf(initialTime.toSecondOfDay() / 60)
    var intervalPreset by mutableStateOf(IntervalPreset.Hourly)
    var customMinutes by mutableStateOf("90")
    var cron by mutableStateOf("0 9 * * 1-5")
    var mode by mutableStateOf(TaskExecutionMode.MainSession)
    var targetSessionId by mutableStateOf<String?>(null)
    var allowApproximate by mutableStateOf(true)
    var loaded by mutableStateOf(false)

    val onceDate: LocalDate get() = LocalDate.ofEpochDay(onceDateEpochDay)
    val onceTime: LocalTime get() = LocalTime.ofSecondOfDay(onceMinuteOfDay * 60L)

    fun intervalMinutesText(): String = intervalPreset.minutes?.toString() ?: customMinutes

    fun scheduleDraft(): TaskScheduleDraft =
        TaskScheduleDraft(
            kind = kind,
            onceDate = onceDate,
            onceTime = onceTime,
            intervalMinutes = intervalMinutesText(),
            cronExpression = cron,
        )

    fun loadFrom(
        task: ai.androidclaw.data.model.Task,
        zoneId: ZoneId,
    ) {
        name = task.name
        prompt = task.prompt
        when (val schedule = task.schedule) {
            is TaskSchedule.Once -> {
                kind = TaskScheduleKindUi.Once
                val local = schedule.at.atZone(zoneId)
                onceDateEpochDay = local.toLocalDate().toEpochDay()
                onceMinuteOfDay = local.toLocalTime().toSecondOfDay() / 60
            }
            is TaskSchedule.Interval -> {
                kind = TaskScheduleKindUi.Interval
                val minutes = schedule.repeatEvery.toMinutes()
                intervalPreset = IntervalPreset.forMinutes(minutes)
                customMinutes = minutes.toString()
            }
            is TaskSchedule.Cron -> {
                kind = TaskScheduleKindUi.Cron
                cron = schedule.expression.toEditableText()
            }
        }
        mode = task.executionMode
        targetSessionId = task.targetSessionId
        allowApproximate = !task.precise
        loaded = true
    }
}

private val TaskEditorFieldsSaver: Saver<TaskEditorFields, Any> =
    listSaver(
        save = { fields ->
            listOf(
                fields.name,
                fields.prompt,
                fields.kind.name,
                fields.onceDateEpochDay,
                fields.onceMinuteOfDay,
                fields.intervalPreset.name,
                fields.customMinutes,
                fields.cron,
                fields.mode.name,
                fields.targetSessionId.orEmpty(),
                fields.allowApproximate,
                fields.loaded,
            )
        },
        restore = { values ->
            TaskEditorFields(LocalDate.ofEpochDay(values[3] as Long), LocalTime.MIDNIGHT).apply {
                name = values[0] as String
                prompt = values[1] as String
                kind = TaskScheduleKindUi.valueOf(values[2] as String)
                onceMinuteOfDay = values[4] as Int
                intervalPreset = IntervalPreset.valueOf(values[5] as String)
                customMinutes = values[6] as String
                cron = values[7] as String
                mode = TaskExecutionMode.valueOf(values[8] as String)
                targetSessionId = (values[9] as String).takeIf(String::isNotEmpty)
                allowApproximate = values[10] as Boolean
                loaded = values[11] as Boolean
            }
        },
    )

@Composable
private fun rememberTaskEditorFields(): TaskEditorFields =
    rememberSaveable(saver = TaskEditorFieldsSaver) {
        val start =
            Instant
                .now()
                .plus(1, ChronoUnit.HOURS)
                .truncatedTo(ChronoUnit.HOURS)
                .atZone(ZoneId.systemDefault())
        TaskEditorFields(start.toLocalDate(), start.toLocalTime())
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorScreen(
    viewModel: TasksViewModel,
    taskId: String?,
    onClose: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fields = rememberTaskEditorFields()
    val zone = remember { ZoneId.systemDefault() }
    var error by remember { mutableStateOf<UiText?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val isEdit = taskId != null

    LaunchedEffect(taskId) {
        if (taskId != null && !fields.loaded) {
            val task = viewModel.findTask(taskId)
            if (task == null) {
                onClose()
            } else {
                fields.loadFrom(task, zone)
            }
        }
    }

    Scaffold(
        modifier = Modifier.testTag("taskEditorScreen"),
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            ClawTopBar(
                title = stringResource(if (isEdit) R.string.tasks_editor_edit_title else R.string.tasks_editor_new_title),
                titleTestTag = "taskEditorHeading",
                onBack = onClose,
                actions = {
                    TextButton(
                        onClick = {
                            error =
                                viewModel.saveTask(
                                    form =
                                        TaskForm(
                                            name = fields.name,
                                            prompt = fields.prompt,
                                            schedule = fields.scheduleDraft(),
                                            executionMode = fields.mode,
                                            targetSessionId = fields.targetSessionId,
                                            precise = !fields.allowApproximate,
                                        ),
                                    existingTaskId = taskId,
                                    onSaved = onClose,
                                )
                            saving = error == null
                        },
                        enabled = !saving,
                        modifier = Modifier.testTag("saveTaskButton"),
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = fields.name,
                    onValueChange = { fields.name = boundTaskFormInput(it, TASK_NAME_MAX_CHARS).value },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("taskNameField"),
                    label = { Text(stringResource(R.string.tasks_field_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = fields.prompt,
                    onValueChange = { fields.prompt = boundTaskFormInput(it, TASK_PROMPT_MAX_CHARS).value },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 112.dp)
                            .testTag("taskPromptField"),
                    label = { Text(stringResource(R.string.tasks_field_prompt)) },
                    supportingText = { Text(stringResource(R.string.tasks_field_prompt_help)) },
                    minLines = 3,
                )
            }

            ClawSectionHeader(stringResource(R.string.tasks_section_schedule))
            ScheduleSection(fields = fields, state = state, zone = zone, onPickDate = { showDatePicker = true }, onPickTime = {
                showTimePicker = true
            })

            ClawSectionHeader(stringResource(R.string.tasks_section_delivery))
            DeliverySection(fields = fields, sessions = state.sessions)

            ClawSectionHeader(stringResource(R.string.tasks_section_timing))
            TimingSection(fields = fields, state = state, context = context)

            error?.let { message ->
                ClawNotice(
                    text = message.asString(),
                    modifier =
                        Modifier
                            .padding(16.dp)
                            .testTag("taskEditorError"),
                    tone = NoticeTone.Error,
                    onDismiss = { error = null },
                )
            }
            if (isEdit) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier =
                        Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .fillMaxWidth()
                            .testTag("taskEditorDelete"),
                ) {
                    ClawIcon(iconRes = R.drawable.ic_delete, contentDescription = null)
                    Text(stringResource(R.string.tasks_action_delete_task), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }

    if (showDatePicker) {
        OnceDatePickerDialog(
            initial = fields.onceDate,
            onConfirm = { date ->
                fields.onceDateEpochDay = date.toEpochDay()
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }
    if (showTimePicker) {
        OnceTimePickerDialog(
            initial = fields.onceTime,
            onConfirm = { time ->
                fields.onceMinuteOfDay = time.toSecondOfDay() / 60
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
    }
    if (confirmDelete && taskId != null) {
        ClawConfirmDialog(
            title = stringResource(R.string.tasks_delete_title),
            text = stringResource(R.string.tasks_delete_body, fields.name),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                confirmDelete = false
                viewModel.deleteTask(taskId)
                onClose()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScheduleSection(
    fields: TaskEditorFields,
    state: TasksUiState,
    zone: ZoneId,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val kinds = TaskScheduleKindUi.entries
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            kinds.forEachIndexed { index, kind ->
                SegmentedButton(
                    selected = fields.kind == kind,
                    onClick = { fields.kind = kind },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = kinds.size),
                    modifier = Modifier.testTag("taskScheduleKind-${kind.name}"),
                ) {
                    Text(
                        stringResource(
                            when (kind) {
                                TaskScheduleKindUi.Once -> R.string.tasks_kind_once
                                TaskScheduleKindUi.Interval -> R.string.tasks_kind_interval
                                TaskScheduleKindUi.Cron -> R.string.tasks_kind_cron
                            },
                        ),
                    )
                }
            }
        }
        when (fields.kind) {
            TaskScheduleKindUi.Once -> {
                val dateFormatter = rememberDateFormatter()
                val timeFormatter = rememberTimeFormatter()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onPickDate,
                        modifier =
                            Modifier
                                .weight(1f)
                                .testTag("taskOnceDateButton"),
                    ) {
                        ClawIcon(iconRes = R.drawable.ic_calendar, contentDescription = null)
                        Text(dateFormatter.format(fields.onceDate), modifier = Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(
                        onClick = onPickTime,
                        modifier =
                            Modifier
                                .weight(1f)
                                .testTag("taskOnceTimeButton"),
                    ) {
                        ClawIcon(iconRes = R.drawable.ic_schedule, contentDescription = null)
                        Text(timeFormatter.format(fields.onceTime), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                HelpText(stringResource(R.string.tasks_once_zone_help, zone.id))
            }

            TaskScheduleKindUi.Interval -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntervalPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = fields.intervalPreset == preset,
                            onClick = { fields.intervalPreset = preset },
                            modifier = Modifier.testTag("taskIntervalPreset-${preset.name}"),
                            label = {
                                Text(
                                    preset.minutes?.let { intervalLabel(it) }
                                        ?: stringResource(R.string.tasks_interval_custom),
                                )
                            },
                        )
                    }
                }
                if (fields.intervalPreset == IntervalPreset.Custom) {
                    OutlinedTextField(
                        value = fields.customMinutes,
                        onValueChange = {
                            fields.customMinutes =
                                boundTaskFormInput(it.filter(Char::isDigit), TASK_FORM_INTERVAL_MINUTES_MAX_CHARS).value
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("taskIntervalMinutesField"),
                        label = { Text(stringResource(R.string.tasks_field_interval_minutes)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                HelpText(stringResource(R.string.tasks_interval_help, state.minimumIntervalMinutes))
            }

            TaskScheduleKindUi.Cron -> {
                val preview =
                    remember(fields.cron) {
                        parseTaskSchedule(
                            draft = fields.scheduleDraft(),
                            minimumIntervalMinutes = state.minimumIntervalMinutes,
                            now = Instant.now(),
                            zoneId = zone,
                        )
                    }
                OutlinedTextField(
                    value = fields.cron,
                    onValueChange = { fields.cron = boundTaskFormInput(it, TASK_FORM_CRON_EXPRESSION_MAX_CHARS).value },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("taskCronExpressionField"),
                    label = { Text(stringResource(R.string.tasks_field_cron)) },
                    singleLine = true,
                    isError = preview is TaskScheduleParseResult.Invalid,
                    supportingText = {
                        when (preview) {
                            is TaskScheduleParseResult.Invalid -> Text(preview.toUiText(state.minimumIntervalMinutes).asString())
                            is TaskScheduleParseResult.Valid -> Text(stringResource(R.string.tasks_cron_help))
                        }
                    },
                )
                if (preview is TaskScheduleParseResult.Valid) {
                    val formatter = rememberDateTimeFormatter(java.time.format.FormatStyle.FULL)
                    Column(
                        modifier = Modifier.testTag("taskCronPreview"),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.tasks_cron_next_runs),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        nextRuns(preview.schedule, Instant.now(), CRON_PREVIEW_COUNT).forEach { run ->
                            Text(
                                text = formatter.format(run),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeliverySection(
    fields: TaskEditorFields,
    sessions: List<TaskSessionUi>,
) {
    Column(modifier = Modifier.selectableGroup()) {
        ModeOption(
            selected = fields.mode == TaskExecutionMode.MainSession,
            title = stringResource(R.string.tasks_mode_post_to_session),
            body = stringResource(R.string.tasks_mode_post_to_session_help),
            testTag = "taskModeMain",
            onSelect = { fields.mode = TaskExecutionMode.MainSession },
        )
        ModeOption(
            selected = fields.mode == TaskExecutionMode.IsolatedSession,
            title = stringResource(R.string.tasks_mode_isolated),
            body = stringResource(R.string.tasks_mode_isolated_help),
            testTag = "taskModeIsolated",
            onSelect = { fields.mode = TaskExecutionMode.IsolatedSession },
        )
    }
    if (fields.mode == TaskExecutionMode.MainSession) {
        var expanded by remember { mutableStateOf(false) }
        val mainSession = sessions.firstOrNull { it.isMain }
        val otherSessions = sessions.filterNot { it.isMain }
        val selected = fields.targetSessionId?.let { id -> otherSessions.firstOrNull { it.id == id } }
        val mainLabel =
            mainSession?.let { stringResource(R.string.tasks_target_main_named, it.title) }
                ?: stringResource(R.string.tasks_target_main)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = selected?.title ?: mainLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.tasks_field_target_session)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .testTag("taskTargetSessionField"),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(mainLabel) },
                    onClick = {
                        fields.targetSessionId = null
                        expanded = false
                    },
                )
                otherSessions.forEach { session ->
                    DropdownMenuItem(
                        text = { Text(session.title) },
                        onClick = {
                            fields.targetSessionId = session.id
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeOption(
    selected: Boolean,
    title: String,
    body: String,
    testTag: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(testTag),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TimingSection(
    fields: TaskEditorFields,
    state: TasksUiState,
    context: android.content.Context,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.tasks_field_allow_approximate), style = MaterialTheme.typography.bodyLarge)
            HelpText(stringResource(R.string.tasks_field_allow_approximate_help))
        }
        Switch(
            checked = fields.allowApproximate,
            onCheckedChange = { fields.allowApproximate = it },
            modifier = Modifier.testTag("taskAllowApproximateSwitch"),
        )
    }
    if (!fields.allowApproximate) {
        val diagnostics = state.diagnostics
        when {
            !diagnostics.supportsExactAlarms ->
                ClawNotice(
                    text = stringResource(R.string.tasks_exact_unavailable),
                    modifier = Modifier.padding(16.dp),
                    tone = NoticeTone.Warning,
                )
            !diagnostics.exactAlarmGranted ->
                ClawNotice(
                    text = stringResource(R.string.tasks_exact_permission_needed),
                    modifier = Modifier.padding(16.dp),
                    tone = NoticeTone.Warning,
                    actionLabel = stringResource(R.string.tasks_action_allow),
                    onAction = { context.startActivity(buildExactAlarmSettingsIntent(context)) },
                )
        }
        if (state.notificationsBlocked) {
            ClawNotice(
                text = stringResource(R.string.tasks_notifications_hint),
                modifier = Modifier.padding(horizontal = 16.dp),
                tone = NoticeTone.Info,
                actionLabel = stringResource(R.string.tasks_action_notification_settings),
                onAction = { context.startActivity(buildNotificationSettingsIntent(context)) },
            )
        }
    }
}

@Composable
private fun HelpText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnceDatePickerDialog(
    initial: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    val pickerState =
        rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates =
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        !Instant
                            .ofEpochMilli(utcTimeMillis)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                            .isBefore(today)

                    override fun isSelectableYear(year: Int): Boolean = year >= today.year
                },
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = pickerState.selectedDateMillis
                    if (millis != null) {
                        onConfirm(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    } else {
                        onDismiss()
                    }
                },
                modifier = Modifier.testTag("datePickerConfirm"),
            ) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnceTimePickerDialog(
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val is24Hour =
        android.text.format.DateFormat
            .is24HourFormat(LocalContext.current)
    val pickerState =
        rememberTimePickerState(
            initialHour = initial.hour,
            initialMinute = initial.minute,
            is24Hour = is24Hour,
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tasks_pick_time)) },
        text = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimePicker(state = pickerState)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(LocalTime.of(pickerState.hour, pickerState.minute)) },
                modifier = Modifier.testTag("timePickerConfirm"),
            ) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
