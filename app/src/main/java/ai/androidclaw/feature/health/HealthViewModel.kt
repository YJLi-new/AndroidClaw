package ai.androidclaw.feature.health

import ai.androidclaw.app.HealthDependencies
import ai.androidclaw.data.model.EventLogEntry
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.runtime.providers.ProviderRegistry
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.tools.ToolRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class HealthUiState(
    val providerId: String = "",
    val supportsExactAlarms: Boolean = false,
    val supportedKinds: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val recentEvents: List<EventLogEntry> = emptyList(),
)

class HealthViewModel(
    schedulerCoordinator: SchedulerCoordinator,
    toolRegistry: ToolRegistry,
    providerRegistry: ProviderRegistry,
    eventLogRepository: EventLogRepository,
) : ViewModel() {
    private val capabilities = schedulerCoordinator.capabilities()
    private val staticState = HealthUiState(
        providerId = providerRegistry.defaultProvider.id,
        supportsExactAlarms = capabilities.supportsExactAlarms,
        supportedKinds = capabilities.supportedKinds,
        tools = toolRegistry.descriptors().map { it.name },
    )

    val state: StateFlow<HealthUiState> = eventLogRepository.observeRecent(limit = 10)
        .map { events ->
            staticState.copy(recentEvents = events)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = staticState,
        )

    companion object {
        fun factory(dependencies: HealthDependencies): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HealthViewModel(
                        schedulerCoordinator = dependencies.schedulerCoordinator,
                        toolRegistry = dependencies.toolRegistry,
                        providerRegistry = dependencies.providerRegistry,
                        eventLogRepository = dependencies.eventLogRepository,
                    ) as T
                }
            }
        }
    }
}
