package ai.androidclaw.app

import android.app.Application
import ai.androidclaw.data.AndroidProviderSecretStore
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.SkillRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.orchestrator.AgentRunner
import ai.androidclaw.runtime.providers.FakeProvider
import ai.androidclaw.runtime.providers.OpenAiCompatibleProvider
import ai.androidclaw.runtime.providers.ProviderRegistry
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.scheduler.TaskRuntimeExecutor
import ai.androidclaw.runtime.skills.BundledSkillLoader
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.skills.SkillParser
import ai.androidclaw.runtime.tools.ToolRegistry
import ai.androidclaw.runtime.tools.createBuiltInToolRegistry
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.time.Clock

class AppContainer(application: Application) {
    private val clock: Clock = Clock.systemDefaultZone()
    private lateinit var skillManagerRef: SkillManager
    private val json = Json {
        ignoreUnknownKeys = true
    }
    val database = AndroidClawDatabase.build(application)
    val settingsDataStore = SettingsDataStore(application)
    val providerSecretStore = AndroidProviderSecretStore(application)
    val sessionRepository = SessionRepository(database.sessionDao())
    val messageRepository = MessageRepository(database.messageDao())
    val taskRepository = TaskRepository(database.taskDao(), database.taskRunDao())
    val skillRepository = SkillRepository(database.skillRecordDao())
    val eventLogRepository = EventLogRepository(database.eventLogDao())

    val toolRegistry = createBuiltInToolRegistry(
        application = application,
        settingsDataStore = settingsDataStore,
        sessionRepository = sessionRepository,
        taskRepository = taskRepository,
        bundledSkillsProvider = { skillManagerRef.refreshBundledSkills() },
    )

    private val bundledSkillLoader = BundledSkillLoader(
        assetManager = application.assets,
        rootPath = "skills",
        parser = SkillParser(),
    )

    val skillManager = SkillManager(
        bundledSkillLoader = bundledSkillLoader,
        toolDescriptor = toolRegistry::findDescriptor,
    ).also { skillManagerRef = it }

    val providerRegistry = ProviderRegistry(
        providers = listOf(
            ProviderRegistry.RegisteredProviderEntry(
                type = ProviderType.Fake,
                displayName = ProviderType.Fake.displayName,
                provider = FakeProvider(clock = clock),
            ),
            ProviderRegistry.RegisteredProviderEntry(
                type = ProviderType.OpenAiCompatible,
                displayName = ProviderType.OpenAiCompatible.displayName,
                provider = OpenAiCompatibleProvider(
                    settingsDataStore = settingsDataStore,
                    providerSecretStore = providerSecretStore,
                    baseHttpClient = OkHttpClient(),
                    json = json,
                ),
            ),
        ),
    )

    val agentRunner = AgentRunner(
        providerRegistry = providerRegistry,
        settingsDataStore = settingsDataStore,
        messageRepository = messageRepository,
        skillManager = skillManager,
        toolRegistry = toolRegistry,
    )

    val taskRuntimeExecutor = TaskRuntimeExecutor(
        sessionRepository = sessionRepository,
        messageRepository = messageRepository,
        agentRunner = agentRunner,
    )

    val schedulerCoordinator = SchedulerCoordinator(
        application = application,
        clock = clock,
        taskRepository = taskRepository,
        eventLogRepository = eventLogRepository,
    )

    val chatDependencies: ChatDependencies
        get() = ChatDependencies(
            sessionRepository = sessionRepository,
            messageRepository = messageRepository,
            eventLogRepository = eventLogRepository,
            agentRunner = agentRunner,
            skillManager = skillManager,
        )

    val tasksDependencies: TasksDependencies
        get() = TasksDependencies(
            taskRepository = taskRepository,
            schedulerCoordinator = schedulerCoordinator,
        )

    val skillsDependencies: SkillsDependencies
        get() = SkillsDependencies(
            skillManager = skillManager,
            skillRepository = skillRepository,
        )

    val settingsDependencies: SettingsDependencies
        get() = SettingsDependencies(
            providerRegistry = providerRegistry,
            settingsDataStore = settingsDataStore,
            providerSecretStore = providerSecretStore,
        )

    val healthDependencies: HealthDependencies
        get() = HealthDependencies(
            schedulerCoordinator = schedulerCoordinator,
            toolRegistry = toolRegistry,
            providerRegistry = providerRegistry,
            settingsDataStore = settingsDataStore,
            eventLogRepository = eventLogRepository,
        )

    suspend fun ensureMainSession() {
        val mainSession = sessionRepository.getOrCreateMainSession()
        if (messageRepository.getRecentMessages(mainSession.id, limit = 1).isEmpty()) {
            messageRepository.addMessage(
                sessionId = mainSession.id,
                role = MessageRole.System,
                content = "AndroidClaw is ready.",
            )
        }
    }
}
