package ai.androidclaw.feature.tasks

import ai.androidclaw.R
import ai.androidclaw.app.TasksDependencies
import ai.androidclaw.app.viewModelFactory
import ai.androidclaw.data.model.Task
import ai.androidclaw.data.model.TaskRun
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.providers.ProviderMessageMeta
import ai.androidclaw.runtime.providers.parseProviderMessageMeta
import ai.androidclaw.runtime.scheduler.SchedulerCapabilities
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.scheduler.SchedulerDiagnostics
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedule
import ai.androidclaw.ui.text.UiText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

data class TaskSessionUi(
    val id: String,
    val title: String,
    val isMain: Boolean,
)

data class TasksUiState(
    val loaded: Boolean = false,
    val tasks: List<Task> = emptyList(),
    val sessions: List<TaskSessionUi> = emptyList(),
    val recentRunsByTaskId: Map<String, List<TaskRun>> = emptyMap(),
    val runUsageSummaryByRunId: Map<String, UiText> = emptyMap(),
    val capabilities: SchedulerCapabilities =
        SchedulerCapabilities(
            minimumBackgroundInterval = Duration.ofMinutes(15),
            supportsExactAlarms = false,
            supportedKinds = emptyList(),
        ),
    val diagnostics: SchedulerDiagnostics = SchedulerDiagnostics(),
    val actionMessage: UiText? = null,
) {
    val minimumIntervalMinutes: Long
        get() = capabilities.minimumBackgroundInterval.toMinutes().coerceAtLeast(1)

    /** True when at least one precise task cannot get an exact alarm right now. */
    val needsExactAlarmPermission: Boolean
        get() = diagnostics.supportsExactAlarms && !diagnostics.exactAlarmGranted && tasks.any { it.precise && it.enabled }

    val notificationsBlocked: Boolean
        get() =
            !diagnostics.notificationVisibility.appNotificationsEnabled ||
                (
                    diagnostics.notificationVisibility.runtimePermissionRequired &&
                        !diagnostics.notificationVisibility.runtimePermissionGranted
                )
}

/** What the editor submits. Schedule parsing happens in the ViewModel. */
data class TaskForm(
    val name: String,
    val prompt: String,
    val schedule: TaskScheduleDraft,
    val executionMode: TaskExecutionMode,
    val targetSessionId: String?,
    val precise: Boolean,
)

internal const val TASK_ACTION_MESSAGE_MAX_CHARS = 1_000
private const val RECENT_RUNS_PER_TASK = 5

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModel(
    private val taskRepository: TaskRepository,
    private val schedulerCoordinator: SchedulerCoordinator,
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val zoneIdProvider: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Instant = Instant::now,
) : ViewModel() {
    private val uiSharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000)
    private val capabilities = schedulerCoordinator.capabilities()
    private val actionMessage = MutableStateFlow<UiText?>(null)
    private val diagnosticsRefreshes = MutableStateFlow(0)
    private val tasksFlow = taskRepository.observeTasks()
    private val sessionsFlow =
        sessionRepository
            .observeSessions()
            .map { sessions ->
                sessions.map { session ->
                    TaskSessionUi(id = session.id, title = session.title, isMain = session.isMain)
                }
            }
    private val recentRunsFlow =
        tasksFlow
            .map { tasks -> tasks.map(Task::id) }
            .flatMapLatest { taskIds ->
                if (taskIds.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(
                        taskIds.map { taskId ->
                            taskRepository.observeRuns(taskId).map { runs -> taskId to runs.take(RECENT_RUNS_PER_TASK) }
                        },
                    ) { taskRuns -> taskRuns.toMap() }
                }
            }
    private val runUsageSummaryFlow =
        recentRunsFlow.flatMapLatest { recentRuns ->
            flow {
                val runs = recentRuns.values.flatten()
                val outputMessageIds = runs.mapNotNull(TaskRun::outputMessageId).distinct()
                if (outputMessageIds.isEmpty()) {
                    emit(emptyMap())
                    return@flow
                }
                val messagesById = messageRepository.getMessagesByIds(outputMessageIds)
                emit(
                    runs
                        .mapNotNull { run ->
                            val outputMessageId = run.outputMessageId ?: return@mapNotNull null
                            val providerMeta =
                                parseProviderMessageMeta(messagesById[outputMessageId]?.providerMeta)
                                    ?: return@mapNotNull null
                            buildRunUsageSummary(providerMeta)?.let { run.id to it }
                        }.toMap(),
                )
            }
        }

    val state: StateFlow<TasksUiState> =
        combine(
            combine(tasksFlow, sessionsFlow, recentRunsFlow, runUsageSummaryFlow, ::TaskData),
            actionMessage,
            diagnosticsRefreshes,
        ) { data, message, _ ->
            TasksUiState(
                loaded = true,
                tasks = data.tasks,
                sessions = data.sessions,
                recentRunsByTaskId = data.recentRuns,
                runUsageSummaryByRunId = data.usage,
                capabilities = capabilities,
                diagnostics = schedulerCoordinator.diagnostics(),
                actionMessage = message,
            )
        }.stateIn(
            scope = viewModelScope,
            started = uiSharingStarted,
            initialValue =
                TasksUiState(
                    capabilities = capabilities,
                    diagnostics = schedulerCoordinator.diagnostics(),
                ),
        )

    suspend fun findTask(taskId: String): Task? = state.value.tasks.firstOrNull { it.id == taskId } ?: taskRepository.getTask(taskId)

    fun createTask(
        name: String,
        prompt: String,
        schedule: TaskSchedule,
        executionMode: TaskExecutionMode,
        targetSessionId: String?,
        precise: Boolean = false,
        maxRetries: Int = 3,
        onSaved: () -> Unit = {},
    ) {
        viewModelScope.launch {
            val createdTask =
                taskRepository.createTask(
                    name = name,
                    prompt = prompt,
                    schedule = schedule,
                    executionMode = executionMode,
                    targetSessionId = targetSessionId,
                    precise = precise,
                    maxRetries = maxRetries,
                )
            schedulerCoordinator.scheduleTask(createdTask.id)
            actionMessage.value = UiText.res(R.string.tasks_notice_created, createdTask.name)
            onSaved()
        }
    }

    /**
     * Validates and saves the editor form. Returns a validation error for the
     * editor to show, or null when the save was started ([onSaved] runs after it
     * is persisted and rescheduled).
     */
    fun saveTask(
        form: TaskForm,
        existingTaskId: String?,
        onSaved: () -> Unit,
    ): UiText? {
        val name = form.name.trim()
        val prompt = form.prompt.trim()
        if (name.isBlank()) return UiText.res(R.string.tasks_error_name_required)
        if (prompt.isBlank()) return UiText.res(R.string.tasks_error_prompt_required)
        val now = clock()
        val existing = existingTaskId?.let { id -> state.value.tasks.firstOrNull { it.id == id } }
        val parsed =
            parseTaskSchedule(
                draft = form.schedule,
                minimumIntervalMinutes = state.value.minimumIntervalMinutes,
                now = now,
                zoneId = zoneIdProvider(),
                existing = existing?.schedule,
            )
        val schedule =
            when (parsed) {
                is TaskScheduleParseResult.Invalid -> return parsed.toUiText(state.value.minimumIntervalMinutes)
                is TaskScheduleParseResult.Valid -> parsed.schedule
            }
        val targetSessionId = form.targetSessionId.takeIf { form.executionMode == TaskExecutionMode.MainSession }
        if (existingTaskId == null) {
            createTask(
                name = name,
                prompt = prompt,
                schedule = schedule,
                executionMode = form.executionMode,
                targetSessionId = targetSessionId,
                precise = form.precise,
                onSaved = onSaved,
            )
            return null
        }
        viewModelScope.launch {
            val current = taskRepository.getTask(existingTaskId)
            if (current == null) {
                actionMessage.value = UiText.res(R.string.tasks_error_not_found)
                onSaved()
                return@launch
            }
            val scheduleChanged = current.schedule != schedule
            val updated =
                current.copy(
                    name = name,
                    prompt = prompt,
                    schedule = schedule,
                    executionMode = form.executionMode,
                    targetSessionId = targetSessionId,
                    precise = form.precise,
                    nextRunAt = if (scheduleChanged) initialNextRun(schedule, now) else current.nextRunAt,
                    failureCount = if (scheduleChanged) 0 else current.failureCount,
                    updatedAt = now,
                )
            taskRepository.updateTask(updated)
            schedulerCoordinator.cancelTask(updated.id)
            schedulerCoordinator.scheduleTask(updated.id)
            actionMessage.value = UiText.res(R.string.tasks_notice_updated, updated.name)
            onSaved()
        }
        return null
    }

    fun toggleEnabled(taskId: String) {
        val task = state.value.tasks.firstOrNull { it.id == taskId } ?: return
        setEnabled(taskId, !task.enabled)
    }

    fun setEnabled(
        taskId: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            val task = taskRepository.getTask(taskId) ?: return@launch
            if (task.enabled == enabled) return@launch
            val now = clock()
            val updatedTask =
                task.copy(
                    enabled = enabled,
                    // A re-enabled task whose stored wake time already passed gets a fresh one.
                    nextRunAt =
                        if (enabled && task.nextRunAt?.isAfter(now) != true) {
                            initialNextRun(task.schedule, now)?.takeIf { it.isAfter(now) }
                        } else {
                            task.nextRunAt
                        },
                    updatedAt = now,
                )
            taskRepository.updateTask(updatedTask)
            if (enabled) {
                schedulerCoordinator.scheduleTask(updatedTask.id)
                actionMessage.value = UiText.res(R.string.tasks_notice_enabled, updatedTask.name)
            } else {
                schedulerCoordinator.cancelTask(updatedTask.id)
                actionMessage.value = UiText.res(R.string.tasks_notice_disabled, updatedTask.name)
            }
        }
    }

    fun runNow(taskId: String) {
        val task = state.value.tasks.firstOrNull { it.id == taskId }
        if (task == null) {
            actionMessage.value = UiText.res(R.string.tasks_error_not_found)
            return
        }
        viewModelScope.launch {
            schedulerCoordinator.runNow(taskId)
            actionMessage.value = UiText.res(R.string.tasks_notice_run_now, task.name)
        }
    }

    fun clearActionMessage() {
        actionMessage.value = null
    }

    fun deleteTask(taskId: String) {
        val taskName =
            state.value.tasks
                .firstOrNull { it.id == taskId }
                ?.name
        viewModelScope.launch {
            schedulerCoordinator.cancelTask(taskId)
            taskRepository.deleteTask(taskId)
            actionMessage.value =
                if (taskName != null) {
                    UiText.res(R.string.tasks_notice_deleted, taskName)
                } else {
                    UiText.res(R.string.tasks_notice_deleted_generic)
                }
        }
    }

    fun refreshDiagnostics() {
        diagnosticsRefreshes.update { it + 1 }
    }

    companion object {
        fun factory(dependencies: TasksDependencies) =
            viewModelFactory {
                TasksViewModel(
                    taskRepository = dependencies.taskRepository,
                    schedulerCoordinator = dependencies.schedulerCoordinator,
                    sessionRepository = dependencies.sessionRepository,
                    messageRepository = dependencies.messageRepository,
                )
            }
    }
}

private data class TaskData(
    val tasks: List<Task>,
    val sessions: List<TaskSessionUi>,
    val recentRuns: Map<String, List<TaskRun>>,
    val usage: Map<String, UiText>,
)

internal fun buildRunUsageSummary(providerMeta: ProviderMessageMeta): UiText? {
    val usage = providerMeta.usage ?: return null
    val providerLabel =
        listOfNotNull(
            providerMeta.providerId.takeIf { it.isNotBlank() },
            providerMeta.modelId?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    val total = usage.totalTokens
    val input = usage.inputTokens
    val output = usage.outputTokens
    return when {
        total != null && providerLabel.isNotBlank() -> UiText.res(R.string.tasks_usage_total_with_provider, providerLabel, total)
        total != null -> UiText.res(R.string.tasks_usage_total, total)
        input != null || output != null ->
            if (providerLabel.isNotBlank()) {
                UiText.res(R.string.tasks_usage_split_with_provider, providerLabel, input ?: 0, output ?: 0)
            } else {
                UiText.res(R.string.tasks_usage_split, input ?: 0, output ?: 0)
            }
        else -> null
    }
}

/** How far through its retry budget a task is, for the run history header. */
internal fun retryStateText(
    task: Task,
    latestRun: TaskRun?,
    formatInstant: (Instant) -> String,
): UiText =
    when {
        latestRun == null -> UiText.res(R.string.tasks_retry_no_runs)
        task.failureCount <= 0 -> UiText.res(R.string.tasks_retry_healthy)
        task.failureCount >= task.maxRetries -> UiText.res(R.string.tasks_retry_exhausted, task.failureCount, task.maxRetries)
        else -> {
            val nextWake = task.nextRunAt?.let(formatInstant)
            if (nextWake == null) {
                UiText.res(R.string.tasks_retry_budget_unscheduled, task.failureCount, task.maxRetries)
            } else {
                UiText.res(R.string.tasks_retry_budget, task.failureCount, task.maxRetries, nextWake)
            }
        }
    }
