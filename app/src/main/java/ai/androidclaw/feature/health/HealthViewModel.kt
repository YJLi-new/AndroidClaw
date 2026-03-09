package ai.androidclaw.feature.health

import ai.androidclaw.app.HealthDependencies
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.EventLogEntry
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.runtime.providers.ProviderRegistry
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.scheduler.SchedulerDiagnostics
import ai.androidclaw.runtime.tools.ToolRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class HealthUiState(
    val providerId: String = "",
    val schedulerDiagnostics: SchedulerDiagnostics = SchedulerDiagnostics(),
    val supportedKinds: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val lastSchedulerWake: Instant? = null,
    val lastAutomationResult: String? = null,
    val lastWorkerStopReason: String? = null,
    val recentEvents: List<EventLogEntry> = emptyList(),
)

class HealthViewModel(
    schedulerCoordinator: SchedulerCoordinator,
    toolRegistry: ToolRegistry,
    providerRegistry: ProviderRegistry,
    settingsDataStore: SettingsDataStore,
    eventLogRepository: EventLogRepository,
) : ViewModel() {
    private val capabilities = schedulerCoordinator.capabilities()
    private val staticTools = toolRegistry.descriptors().map { it.name }
    private val initialDiagnostics = schedulerCoordinator.diagnostics()
    private val diagnosticsRefreshes = MutableStateFlow(0)

    val state: StateFlow<HealthUiState> = combine(
        settingsDataStore.settings,
        eventLogRepository.observeRecent(limit = 10),
        diagnosticsRefreshes,
    ) { settings, events, _ ->
            val schedulerEvents = events.filter { it.category == EventCategory.Scheduler }
            val diagnostics = schedulerCoordinator.diagnostics()
            HealthUiState(
                providerId = providerRegistry.require(settings.providerType).id,
                schedulerDiagnostics = diagnostics,
                supportedKinds = capabilities.supportedKinds,
                tools = staticTools,
                lastSchedulerWake = schedulerEvents.firstOrNull { it.message.contains("started", ignoreCase = true) }?.timestamp,
                lastAutomationResult = schedulerEvents.firstOrNull { event ->
                    event.message.contains("completed", ignoreCase = true) ||
                        event.message.contains("failed", ignoreCase = true) ||
                        event.message.contains("skipped", ignoreCase = true)
                }?.let { event ->
                    buildString {
                        append(event.message)
                        event.details?.takeIf { it.isNotBlank() }?.let { details ->
                            append(" (").append(details).append(")")
                        }
                    }
                },
                lastWorkerStopReason = schedulerEvents.firstOrNull {
                    it.message.contains("stopped", ignoreCase = true)
                }?.details,
                recentEvents = events,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = HealthUiState(
                providerId = providerRegistry.defaultProvider.id,
                schedulerDiagnostics = initialDiagnostics,
                supportedKinds = capabilities.supportedKinds,
                tools = staticTools,
            ),
        )

    fun refreshDiagnostics() {
        diagnosticsRefreshes.update { it + 1 }
    }

    companion object {
        fun factory(dependencies: HealthDependencies): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HealthViewModel(
                        schedulerCoordinator = dependencies.schedulerCoordinator,
                        toolRegistry = dependencies.toolRegistry,
                        providerRegistry = dependencies.providerRegistry,
                        settingsDataStore = dependencies.settingsDataStore,
                        eventLogRepository = dependencies.eventLogRepository,
                    ) as T
                }
            }
        }
    }
}
