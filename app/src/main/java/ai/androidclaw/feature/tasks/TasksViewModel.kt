package ai.androidclaw.feature.tasks

import ai.androidclaw.app.TasksDependencies
import ai.androidclaw.data.model.Task
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.scheduler.CronExpression
import ai.androidclaw.runtime.scheduler.NextRunCalculator
import ai.androidclaw.runtime.scheduler.SchedulerCapabilities
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TasksUiState(
    val tasks: List<Task> = emptyList(),
    val capabilities: SchedulerCapabilities = SchedulerCapabilities(
        minimumBackgroundInterval = Duration.ofMinutes(15),
        supportsExactAlarms = false,
        supportedKinds = emptyList(),
    ),
    val nextDailyPreview: Instant? = null,
    val nextWeekdayPreview: Instant? = null,
)

class TasksViewModel(
    private val taskRepository: TaskRepository,
    private val schedulerCoordinator: SchedulerCoordinator,
) : ViewModel() {
    private val capabilities = schedulerCoordinator.capabilities()

    val state: StateFlow<TasksUiState> = taskRepository.observeTasks()
        .map { tasks ->
            TasksUiState(
                tasks = tasks,
                capabilities = capabilities,
                nextDailyPreview = schedulerCoordinator.nextRunPreview("@daily"),
                nextWeekdayPreview = NextRunCalculator.computeNextRun(
                    schedule = TaskSchedule.Cron(
                        expression = CronExpression.parse("0 9 * * 1-5"),
                        zoneId = ZoneId.systemDefault(),
                    ),
                    after = Instant.now(),
                ),
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = TasksUiState(capabilities = capabilities),
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
            taskRepository.createTask(
                name = name,
                prompt = prompt,
                schedule = schedule,
                executionMode = executionMode,
                targetSessionId = targetSessionId,
                precise = precise,
                maxRetries = maxRetries,
            )
        }
    }

    fun toggleEnabled(taskId: String) {
        val task = state.value.tasks.firstOrNull { it.id == taskId } ?: return
        viewModelScope.launch {
            taskRepository.updateTask(
                task.copy(
                    enabled = !task.enabled,
                    updatedAt = Instant.now(),
                ),
            )
        }
    }

    fun runNow(taskId: String) {
        taskId.hashCode()
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch {
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
