package ai.androidclaw.app

import android.app.Application
import ai.androidclaw.data.AndroidProviderSecretStore
import ai.androidclaw.data.AndroidSkillConfigStore
import ai.androidclaw.data.AndroidSkillSecretStore
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
import ai.androidclaw.runtime.orchestrator.PromptAssembler
import ai.androidclaw.runtime.orchestrator.SessionLaneCoordinator
import ai.androidclaw.runtime.providers.AnthropicProvider
import ai.androidclaw.runtime.providers.AndroidNetworkStatusProvider
import ai.androidclaw.runtime.providers.FakeProvider
import ai.androidclaw.runtime.providers.createProviderBaseHttpClient
import ai.androidclaw.runtime.providers.NetworkStatusProvider
import ai.androidclaw.runtime.providers.OpenAiCompatibleProvider
import ai.androidclaw.runtime.providers.ProviderRegistry
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.scheduler.TaskRuntimeExecutor
import ai.androidclaw.runtime.skills.BundledSkillLoader
import ai.androidclaw.runtime.skills.FileSkillLoader
import ai.androidclaw.runtime.skills.LocalSkillImporter
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.skills.SkillParser
import ai.androidclaw.runtime.skills.SkillSourceScanner
import ai.androidclaw.runtime.skills.SkillStorage
import ai.androidclaw.runtime.tools.ToolRegistry
import ai.androidclaw.runtime.tools.createBuiltInToolRegistry
import kotlinx.serialization.json.Json
import java.time.Clock

class AppContainer(application: Application) {
    private val clock: Clock = Clock.systemDefaultZone()
    private lateinit var skillManagerRef: SkillManager
    private val json = Json {
        ignoreUnknownKeys = true
    }
    private val providerHttpClient = createProviderBaseHttpClient()
    val database = AndroidClawDatabase.build(application)
    val settingsDataStore = SettingsDataStore(application)
    val providerSecretStore = AndroidProviderSecretStore(application)
    val skillConfigStore = AndroidSkillConfigStore(application)
    val skillSecretStore = AndroidSkillSecretStore(application)
    val networkStatusProvider: NetworkStatusProvider = AndroidNetworkStatusProvider(application)
    val sessionRepository = SessionRepository(database.sessionDao())
    val messageRepository = MessageRepository(database.messageDao())
    val taskRepository = TaskRepository(database.taskDao(), database.taskRunDao())
    val skillRepository = SkillRepository(database.skillRecordDao())
    val eventLogRepository = EventLogRepository(database.eventLogDao())
    val sessionLaneCoordinator = SessionLaneCoordinator()
    val promptAssembler = PromptAssembler()
    private val skillParser = SkillParser()
    private val skillStorage = SkillStorage(
        filesDir = application.filesDir,
        cacheDir = application.cacheDir,
    )

    val schedulerCoordinator = SchedulerCoordinator(
        application = application,
        clock = clock,
        taskRepository = taskRepository,
        eventLogRepository = eventLogRepository,
    )

    val toolRegistry = createBuiltInToolRegistry(
        application = application,
        settingsDataStore = settingsDataStore,
        sessionRepository = sessionRepository,
        taskRepository = taskRepository,
        schedulerCoordinator = schedulerCoordinator,
        bundledSkillsProvider = { skillManagerRef.refreshSkills() },
        eventLogRepository = eventLogRepository,
    )

    private val bundledSkillLoader = BundledSkillLoader(
        assetManager = application.assets,
        rootPath = "skills",
        parser = skillParser,
    )
    private val fileSkillLoader = FileSkillLoader(
        parser = skillParser,
    )
    private val skillSourceScanner = SkillSourceScanner(
        bundledSkillLoader = bundledSkillLoader,
        fileSkillLoader = fileSkillLoader,
        skillStorage = skillStorage,
    )
    private val localSkillImporter = LocalSkillImporter(
        contentResolver = application.contentResolver,
        skillStorage = skillStorage,
        parser = skillParser,
    )

    val skillManager = SkillManager(
        skillSourceScanner = skillSourceScanner,
        localSkillImporter = localSkillImporter,
        skillRepository = skillRepository,
        skillConfigStore = skillConfigStore,
        skillSecretStore = skillSecretStore,
        toolDescriptor = toolRegistry::findDescriptor,
    ).also { skillManagerRef = it }

    val providerRegistry = ProviderRegistry(
        providers = listOf(
            ProviderRegistry.RegisteredProviderEntry(
                type = ProviderType.Fake,
                displayName = ProviderType.Fake.displayName,
                provider = FakeProvider(clock = clock),
            ),
        ) + ProviderType.configurableProviders.map { providerType ->
            ProviderRegistry.RegisteredProviderEntry(
                type = providerType,
                displayName = providerType.displayName,
                provider = when (providerType) {
                    ProviderType.Anthropic -> AnthropicProvider(
                        settingsDataStore = settingsDataStore,
                        providerSecretStore = providerSecretStore,
                        baseHttpClient = providerHttpClient,
                        json = json,
                    )

                    else -> OpenAiCompatibleProvider(
                        providerType = providerType,
                        settingsDataStore = settingsDataStore,
                        providerSecretStore = providerSecretStore,
                        baseHttpClient = providerHttpClient,
                        json = json,
                    )
                },
            )
        },
    )

    val agentRunner = AgentRunner(
        providerRegistry = providerRegistry,
        settingsDataStore = settingsDataStore,
        messageRepository = messageRepository,
        skillManager = skillManager,
        toolRegistry = toolRegistry,
        sessionLaneCoordinator = sessionLaneCoordinator,
        promptAssembler = promptAssembler,
    )

    val taskRuntimeExecutor = TaskRuntimeExecutor(
        sessionRepository = sessionRepository,
        messageRepository = messageRepository,
        agentRunner = agentRunner,
        sessionLaneCoordinator = sessionLaneCoordinator,
    )
    val startupMaintenance = StartupMaintenance(
        clock = clock,
        taskRepository = taskRepository,
        eventLogRepository = eventLogRepository,
        ensureMainSession = ::ensureMainSession,
        rescheduleAll = schedulerCoordinator::rescheduleAll,
    )
    val workerFactory = AppWorkerFactory { this }

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
            sessionRepository = sessionRepository,
        )

    val skillsDependencies: SkillsDependencies
        get() = SkillsDependencies(
            skillManager = skillManager,
        )

    val settingsDependencies: SettingsDependencies
        get() = SettingsDependencies(
            providerRegistry = providerRegistry,
            settingsDataStore = settingsDataStore,
            providerSecretStore = providerSecretStore,
            networkStatusProvider = networkStatusProvider,
        )

    val healthDependencies: HealthDependencies
        get() = HealthDependencies(
            schedulerCoordinator = schedulerCoordinator,
            toolRegistry = toolRegistry,
            providerRegistry = providerRegistry,
            settingsDataStore = settingsDataStore,
            eventLogRepository = eventLogRepository,
            networkStatusProvider = networkStatusProvider,
        )

    suspend fun ensureMainSession() {
        suspend fun ensureReadyMessage(sessionId: String) {
            if (messageRepository.getRecentMessages(sessionId, limit = 1).isNotEmpty()) {
                return
            }
            messageRepository.addMessage(
                sessionId = sessionId,
                role = MessageRole.System,
                content = "AndroidClaw is ready.",
            )
        }

        val mainSession = sessionRepository.getOrCreateMainSession()
        val sessionId = sessionRepository.getSession(mainSession.id)?.id
            ?: sessionRepository.getOrCreateMainSession().id
        try {
            ensureReadyMessage(sessionId)
        } catch (_: Exception) {
            val recoveredSessionId = sessionRepository.getOrCreateMainSession().id
            ensureReadyMessage(recoveredSessionId)
        }
    }
}
