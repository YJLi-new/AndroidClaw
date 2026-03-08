package ai.androidclaw.app

import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.SkillRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.orchestrator.AgentRunner
import ai.androidclaw.runtime.providers.ProviderRegistry
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.tools.ToolRegistry

data class ChatDependencies(
    val sessionRepository: SessionRepository,
    val messageRepository: MessageRepository,
    val agentRunner: AgentRunner,
    val skillManager: SkillManager,
)

data class TasksDependencies(
    val taskRepository: TaskRepository,
    val schedulerCoordinator: SchedulerCoordinator,
)

data class SkillsDependencies(
    val skillManager: SkillManager,
    val skillRepository: SkillRepository,
)

data class SettingsDependencies(
    val providerRegistry: ProviderRegistry,
    val settingsDataStore: SettingsDataStore,
)

data class HealthDependencies(
    val schedulerCoordinator: SchedulerCoordinator,
    val toolRegistry: ToolRegistry,
    val providerRegistry: ProviderRegistry,
    val eventLogRepository: EventLogRepository,
)
