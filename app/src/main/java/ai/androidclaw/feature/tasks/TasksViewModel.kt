package ai.androidclaw.feature.tasks

import ai.androidclaw.app.TasksDependencies
import ai.androidclaw.data.model.Task
import ai.androidclaw.data.model.TaskRun
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.scheduler.SchedulerCapabilities
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.scheduler.SchedulerDiagnostics
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TaskSessionUi(
    val id: String,
    val title: String,
    val isMain: Boolean,
)

data class TasksUiState(
    val tasks: List<Task> = emptyList(),
    val sessions: List<TaskSessionUi> = emptyList(),
    val recentRunsByTaskId: Map<String, List<TaskRun>> = emptyMap(),
    val capabilities: SchedulerCapabilities = SchedulerCapabilities(
        minimumBackgroundInterval = Duration.ofMinutes(15),
        supportsExactAlarms = false,
        supportedKinds = emptyList(),
    ),
    val diagnostics: SchedulerDiagnostics = SchedulerDiagnostics(),
    val nextDailyPreview: Instant? = null,
    val nextWeekdayPreview: Instant? = null,
    val actionMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModel(
    private val taskRepository: TaskRepository,
    private val schedulerCoordinator: SchedulerCoordinator,
    private val sessionRepository: SessionRepository,
) : ViewModel() {
    private val capabilities = schedulerCoordinator.capabilities()
    private val actionMessage = MutableStateFlow<String?>(null)
    private val diagnosticsRefreshes = MutableStateFlow(0)
    private val tasksFlow = taskRepository.observeTasks()
    private val sessionsFlow = sessionRepository.observeSessions()
        .map { sessions ->
            sessions.map { session ->
                TaskSessionUi(
                    id = session.id,
                    title = session.title,
                    isMain = session.isMain,
                )
            }
        }
    private val recentRunsFlow = tasksFlow.flatMapLatest { tasks ->
        if (tasks.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(
                tasks.map { task ->
                    taskRepository.observeRuns(task.id).map { runs ->
                        task.id to runs.take(3)
                    }
                },
            ) { taskRuns ->
                taskRuns.associate { (taskId, runs) -> taskId to runs }
            }
        }
    }

    val state: StateFlow<TasksUiState> = combine(
        tasksFlow,
        sessionsFlow,
        recentRunsFlow,
        actionMessage,
        diagnosticsRefreshes,
    ) { tasks, sessions, recentRuns, actionMessageValue, _ ->
        val diagnostics = schedulerCoordinator.diagnostics()
        TasksUiState(
            tasks = tasks,
            sessions = sessions,
            recentRunsByTaskId = recentRuns,
            capabilities = capabilities,
            diagnostics = diagnostics,
            nextDailyPreview = schedulerCoordinator.nextRunPreview("@daily"),
            nextWeekdayPreview = schedulerCoordinator.nextRunPreview(
                expression = "0 9 * * 1-5",
                zoneId = ZoneId.systemDefault(),
            ),
            actionMessage = actionMessageValue,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = TasksUiState(
            capabilities = capabilities,
            diagnostics = schedulerCoordinator.diagnostics(),
        ),
    )

    fun createTask(
        name: String,
        prompt: String,
        schedule: TaskSchedule,
        executionMode: TaskExecutionMode,
        targetSessionId: String?,
        precise: Boolean = false,
        maxRetries: Int = 3,
    ) {
        viewModelScope.launch {
            val createdTask = taskRepository.createTask(
                name = name,
                prompt = prompt,
                schedule = schedule,
                executionMode = executionMode,
                targetSessionId = targetSessionId,
                precise = precise,
                maxRetries = maxRetries,
            )
            schedulerCoordinator.scheduleTask(createdTask.id)
            actionMessage.value = "Created task ${createdTask.name}."
        }
    }

    fun toggleEnabled(taskId: String) {
        val task = state.value.tasks.firstOrNull { it.id == taskId } ?: return
        viewModelScope.launch {
            val updatedTask = task.copy(
                enabled = !task.enabled,
                updatedAt = Instant.now(),
            )
            taskRepository.updateTask(updatedTask)
            if (updatedTask.enabled) {
                schedulerCoordinator.scheduleTask(updatedTask.id)
                actionMessage.value = "Enabled ${updatedTask.name}."
            } else {
                schedulerCoordinator.cancelTask(updatedTask.id)
                actionMessage.value = "Disabled ${updatedTask.name}."
            }
        }
    }

    fun runNow(taskId: String) {
        val task = state.value.tasks.firstOrNull { it.id == taskId }
        if (task == null) {
            actionMessage.value = "Run now failed: task not found."
            return
        }
        viewModelScope.launch {
            schedulerCoordinator.runNow(taskId)
            actionMessage.value = "Queued run now for ${task.name}."
        }
    }

    fun clearActionMessage() {
        actionMessage.value = null
    }

    fun deleteTask(taskId: String) {
        val taskName = state.value.tasks.firstOrNull { it.id == taskId }?.name ?: "task"
        viewModelScope.launch {
            schedulerCoordinator.cancelTask(taskId)
            taskRepository.deleteTask(taskId)
            actionMessage.value = "Deleted $taskName."
        }
    }

    fun refreshDiagnostics() {
        diagnosticsRefreshes.update { it + 1 }
    }

    companion object {
        fun factory(dependencies: TasksDependencies): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TasksViewModel(
                        taskRepository = dependencies.taskRepository,
                        schedulerCoordinator = dependencies.schedulerCoordinator,
                        sessionRepository = dependencies.sessionRepository,
                    ) as T
                }
            }
        }
    }
}
