package ai.androidclaw.feature.tasks

import ai.androidclaw.app.TasksDependencies
import ai.androidclaw.data.model.Task
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TasksUiState(
    val tasks: List<Task> = emptyList(),
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

class TasksViewModel(
    private val taskRepository: TaskRepository,
    private val schedulerCoordinator: SchedulerCoordinator,
) : ViewModel() {
    private val capabilities = schedulerCoordinator.capabilities()
    private val actionMessage = MutableStateFlow<String?>(null)

    val state: StateFlow<TasksUiState> = combine(
        taskRepository.observeTasks(),
        actionMessage,
    ) { tasks, actionMessageValue ->
            val diagnostics = schedulerCoordinator.diagnostics()
            TasksUiState(
                tasks = tasks,
                capabilities = capabilities,
                diagnostics = diagnostics,
                nextDailyPreview = schedulerCoordinator.nextRunPreview("@daily"),
                nextWeekdayPreview = schedulerCoordinator.nextRunPreview(
                    expression = "0 9 * * 1-5",
                    zoneId = ZoneId.systemDefault(),
                ),
                actionMessage = actionMessageValue,
            )
        }
        .stateIn(
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
            } else {
                schedulerCoordinator.cancelTask(updatedTask.id)
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
        viewModelScope.launch {
            schedulerCoordinator.cancelTask(taskId)
            taskRepository.deleteTask(taskId)
        }
    }

    companion object {
        fun factory(dependencies: TasksDependencies): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TasksViewModel(
                        taskRepository = dependencies.taskRepository,
                        schedulerCoordinator = dependencies.schedulerCoordinator,
                    ) as T
                }
            }
        }
    }
}
