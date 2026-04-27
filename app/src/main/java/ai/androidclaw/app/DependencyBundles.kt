package ai.androidclaw.app

import ai.androidclaw.data.OnboardingDataStore
import ai.androidclaw.data.ProviderSecretStore
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.orchestrator.AgentRunner
import ai.androidclaw.runtime.providers.NetworkStatusProvider
import ai.androidclaw.runtime.providers.OpenAiCodexOAuthClient
import ai.androidclaw.runtime.providers.ProviderRegistry
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.tools.ToolRegistry

data class ChatDependencies(
    val sessionRepository: SessionRepository,
    val messageRepository: MessageRepository,
    val eventLogRepository: EventLogRepository,
    val agentRunner: AgentRunner,
    val skillManager: SkillManager,
    val settingsDataStore: SettingsDataStore,
)

data class TasksDependencies(
    val taskRepository: TaskRepository,
    val schedulerCoordinator: SchedulerCoordinator,
    val sessionRepository: SessionRepository,
    val messageRepository: MessageRepository,
)

data class SkillsDependencies(
    val skillManager: SkillManager,
)

data class SettingsDependencies(
    val providerRegistry: ProviderRegistry,
    val settingsDataStore: SettingsDataStore,
    val providerSecretStore: ProviderSecretStore,
    val openAiCodexOAuthClient: OpenAiCodexOAuthClient,
    val networkStatusProvider: NetworkStatusProvider,
)

data class OnboardingDependencies(
    val onboardingDataStore: OnboardingDataStore,
    val settingsDataStore: SettingsDataStore,
)

data class HealthDependencies(
    val schedulerCoordinator: SchedulerCoordinator,
    val toolRegistry: ToolRegistry,
    val providerRegistry: ProviderRegistry,
    val settingsDataStore: SettingsDataStore,
    val eventLogRepository: EventLogRepository,
    val networkStatusProvider: NetworkStatusProvider,
    val crashMarkerStore: CrashMarkerStore,
)
