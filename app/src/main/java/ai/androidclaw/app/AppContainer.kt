package ai.androidclaw.app

import ai.androidclaw.data.AndroidProviderSecretStore
import ai.androidclaw.data.AndroidSkillConfigStore
import ai.androidclaw.data.AndroidSkillSecretStore
import ai.androidclaw.data.AndroidWebSearchKeyStore
import ai.androidclaw.data.OnboardingDataStore
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.WorkspaceStore
import ai.androidclaw.data.attachments.AndroidImageImporter
import ai.androidclaw.data.attachments.AttachmentImporter
import ai.androidclaw.data.attachments.AttachmentStore
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.repository.AttachmentRepository
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.MemoryRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.SkillRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.device.AppForegroundState
import ai.androidclaw.runtime.media.AndroidVideoNotifier
import ai.androidclaw.runtime.media.FakeImageBackend
import ai.androidclaw.runtime.media.GeneratedMediaStore
import ai.androidclaw.runtime.media.MediaGenerationPolicy
import ai.androidclaw.runtime.media.OpenAiImageBackend
import ai.androidclaw.runtime.media.OpenAiMediaClient
import ai.androidclaw.runtime.media.WorkManagerVideoJobScheduler
import ai.androidclaw.runtime.memory.MemoryCoordinator
import ai.androidclaw.runtime.orchestrator.AgentRunner
import ai.androidclaw.runtime.orchestrator.PromptAssembler
import ai.androidclaw.runtime.orchestrator.SessionLaneCoordinator
import ai.androidclaw.runtime.orchestrator.SessionSummaryCoordinator
import ai.androidclaw.runtime.providers.AndroidNetworkStatusProvider
import ai.androidclaw.runtime.providers.AnthropicProvider
import ai.androidclaw.runtime.providers.FakeProvider
import ai.androidclaw.runtime.providers.HttpOpenAiCodexOAuthClient
import ai.androidclaw.runtime.providers.NetworkStatusProvider
import ai.androidclaw.runtime.providers.OpenAiCodexResponsesProvider
import ai.androidclaw.runtime.providers.OpenAiCompatibleProvider
import ai.androidclaw.runtime.providers.ProviderRegistry
import ai.androidclaw.runtime.providers.createProviderBaseHttpClient
import ai.androidclaw.runtime.scheduler.AndroidTaskNotifier
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.scheduler.TaskRuntimeExecutor
import ai.androidclaw.runtime.skills.BundledSkillLoader
import ai.androidclaw.runtime.skills.FileSkillLoader
import ai.androidclaw.runtime.skills.LocalSkillImporter
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.skills.SkillParser
import ai.androidclaw.runtime.skills.SkillSourceScanner
import ai.androidclaw.runtime.skills.SkillStorage
import ai.androidclaw.runtime.tools.BraveSearchClient
import ai.androidclaw.runtime.tools.MediaToolDependencies
import ai.androidclaw.runtime.tools.WebSearchPolicy
import ai.androidclaw.runtime.tools.createBuiltInToolRegistry
import android.app.Application
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Clock

class AppContainer(
    application: Application,
) {
    private val clock: Clock = Clock.systemDefaultZone()
    private lateinit var skillManagerRef: SkillManager
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json =
        Json {
            ignoreUnknownKeys = true
        }
    private val providerHttpClient = createProviderBaseHttpClient()
    private val openAiCodexOAuthClient =
        HttpOpenAiCodexOAuthClient(
            httpClient = providerHttpClient,
            json = json,
            clock = clock,
        )
    val database = AndroidClawDatabase.build(application)
    val crashMarkerStore = CrashMarkerStore(application)
    val settingsDataStore = SettingsDataStore(application)
    val onboardingDataStore = OnboardingDataStore(application)
    val providerSecretStore = AndroidProviderSecretStore(application)
    val skillConfigStore = AndroidSkillConfigStore(application)
    val skillSecretStore = AndroidSkillSecretStore(application)
    val webSearchKeyStore = AndroidWebSearchKeyStore(application)
    val braveSearchClient = BraveSearchClient(providerHttpClient)
    val webSearchPolicy = WebSearchPolicy(settingsDataStore, webSearchKeyStore)
    val networkStatusProvider: NetworkStatusProvider = AndroidNetworkStatusProvider(application)
    val attachmentRepository =
        AttachmentRepository(
            dao = database.messageAttachmentDao(),
            store = AttachmentStore(File(application.filesDir, AttachmentStore.DIRECTORY_NAME)),
            clock = clock,
        )
    val attachmentImporter: AttachmentImporter = AndroidImageImporter(application.contentResolver, attachmentRepository.store)
    val sessionRepository = SessionRepository(database.sessionDao(), attachmentRepository)
    val messageRepository = MessageRepository(database.messageDao(), attachmentRepository)
    val taskRepository = TaskRepository(database.taskDao(), database.taskRunDao())
    val skillRepository = SkillRepository(database.skillRecordDao())
    val eventLogRepository = EventLogRepository(database.eventLogDao())
    val memoryRepository = MemoryRepository(database.memoryItemDao(), clock = clock)
    val memoryCoordinator = MemoryCoordinator(settingsDataStore, memoryRepository)
    val sessionLaneCoordinator = SessionLaneCoordinator()
    val promptAssembler = PromptAssembler()
    private val skillParser = SkillParser()
    private val skillStorage =
        SkillStorage(
            filesDir = application.filesDir,
            cacheDir = application.cacheDir,
        )

    val schedulerCoordinator =
        SchedulerCoordinator(
            application = application,
            clock = clock,
            taskRepository = taskRepository,
            eventLogRepository = eventLogRepository,
        )
    val taskNotifier = AndroidTaskNotifier(application)

    // PLANv13 gen: image and video generation (OpenAI key; debug fake image backend).
    val mediaGenerationPolicy = MediaGenerationPolicy(settingsDataStore, providerSecretStore)
    val openAiMediaClient = OpenAiMediaClient(providerHttpClient)
    val generatedMediaStore = GeneratedMediaStore(attachmentRepository.store)
    val videoNotifier = AndroidVideoNotifier(application)
    private val mediaToolDependencies =
        MediaToolDependencies(
            policy = mediaGenerationPolicy,
            attachmentRepository = attachmentRepository,
            mediaStore = generatedMediaStore,
            openAiImageBackend = OpenAiImageBackend(openAiMediaClient, mediaGenerationPolicy),
            fakeImageBackend = FakeImageBackend(),
            client = openAiMediaClient,
            videoJobScheduler = WorkManagerVideoJobScheduler { WorkManager.getInstance(application) },
            clock = clock,
        )

    /** App-private persona/workspace files (AGENTS.md, SOUL.md, USER.md, MEMORY.md, notes). */
    val workspaceStore = WorkspaceStore(File(application.filesDir, WorkspaceStore.DIRECTORY_NAME))

    val toolRegistry =
        createBuiltInToolRegistry(
            application = application,
            settingsDataStore = settingsDataStore,
            sessionRepository = sessionRepository,
            taskRepository = taskRepository,
            schedulerCoordinator = schedulerCoordinator,
            bundledSkillsProvider = { skillManagerRef.refreshSkillInventory() },
            skillEnabledUpdater = { skillId, enabled -> skillManagerRef.setEnabled(skillId, enabled) },
            skillInventoryRefresher = { sessionId, forceRefresh ->
                skillManagerRef.refreshSkillInventory(
                    sessionId = sessionId,
                    forceRefresh = forceRefresh,
                )
            },
            messageRepository = messageRepository,
            memoryRepository = memoryRepository,
            eventLogRepository = eventLogRepository,
            workspaceStore = workspaceStore,
            webHttpClient = providerHttpClient,
            networkStatusProvider = networkStatusProvider,
            isAppInForeground = AppForegroundState::isInForeground,
            clock = clock,
            braveSearchClient = braveSearchClient,
            braveApiKey = webSearchKeyStore::readBraveApiKey,
            mediaTools = mediaToolDependencies,
        )

    private val bundledSkillLoader =
        BundledSkillLoader(
            assetManager = application.assets,
            rootPath = "skills",
            parser = skillParser,
        )
    private val fileSkillLoader =
        FileSkillLoader(
            parser = skillParser,
        )
    private val skillSourceScanner =
        SkillSourceScanner(
            bundledSkillLoader = bundledSkillLoader,
            fileSkillLoader = fileSkillLoader,
            skillStorage = skillStorage,
        )
    private val localSkillImporter =
        LocalSkillImporter(
            contentResolver = application.contentResolver,
            skillStorage = skillStorage,
            parser = skillParser,
        )

    val skillManager =
        SkillManager(
            skillSourceScanner = skillSourceScanner,
            localSkillImporter = localSkillImporter,
            skillRepository = skillRepository,
            skillConfigStore = skillConfigStore,
            skillSecretStore = skillSecretStore,
            toolDescriptor = toolRegistry::findDescriptor,
        ).also { skillManagerRef = it }

    val providerRegistry =
        ProviderRegistry(
            providers =
                listOf(
                    ProviderRegistry.RegisteredProviderEntry(
                        type = ProviderType.Fake,
                        displayName = ProviderType.Fake.displayName,
                        provider = FakeProvider(clock = clock),
                    ),
                ) +
                    ProviderType.configurableProviders.map { providerType ->
                        ProviderRegistry.RegisteredProviderEntry(
                            type = providerType,
                            displayName = providerType.displayName,
                            provider =
                                when (providerType) {
                                    ProviderType.Anthropic ->
                                        AnthropicProvider(
                                            settingsDataStore = settingsDataStore,
                                            providerSecretStore = providerSecretStore,
                                            baseHttpClient = providerHttpClient,
                                            json = json,
                                        )

                                    ProviderType.OpenAiCodex ->
                                        OpenAiCodexResponsesProvider(
                                            settingsDataStore = settingsDataStore,
                                            providerSecretStore = providerSecretStore,
                                            oAuthClient = openAiCodexOAuthClient,
                                            baseHttpClient = providerHttpClient,
                                            json = json,
                                            clock = clock,
                                        )

                                    else ->
                                        OpenAiCompatibleProvider(
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
    val sessionSummaryCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        SessionSummaryCoordinator(
            applicationScope = applicationScope,
            providerRegistry = providerRegistry,
            settingsDataStore = settingsDataStore,
            sessionRepository = sessionRepository,
            messageRepository = messageRepository,
            sessionLaneCoordinator = sessionLaneCoordinator,
        )
    }

    val agentRunner =
        AgentRunner(
            providerRegistry = providerRegistry,
            settingsDataStore = settingsDataStore,
            messageRepository = messageRepository,
            skillManager = skillManager,
            toolRegistry = toolRegistry,
            sessionLaneCoordinator = sessionLaneCoordinator,
            promptAssembler = promptAssembler,
            sessionSummaryCoordinator = sessionSummaryCoordinator,
            memoryCoordinator = memoryCoordinator,
            loadSessionSummary = { sessionId -> sessionRepository.getSession(sessionId)?.summaryText },
            loadSessionCompactionBoundary = { sessionId -> sessionRepository.getSession(sessionId)?.compactedUntilMessageId },
            networkStatusProvider = networkStatusProvider,
            workspaceContextProvider = { workspaceStore.promptContext() },
            webSearchPolicy = webSearchPolicy,
            attachmentRepository = attachmentRepository,
            mediaGenerationPolicy = mediaGenerationPolicy,
        )

    val taskRuntimeExecutor =
        TaskRuntimeExecutor(
            sessionRepository = sessionRepository,
            messageRepository = messageRepository,
            agentRunner = agentRunner,
            sessionLaneCoordinator = sessionLaneCoordinator,
        )
    val startupMaintenance =
        StartupMaintenance(
            clock = clock,
            sessionRepository = sessionRepository,
            messageRepository = messageRepository,
            taskRepository = taskRepository,
            eventLogRepository = eventLogRepository,
            ensureMainSession = ::ensureMainSession,
            rescheduleAll = schedulerCoordinator::rescheduleAll,
            memoryRepository = memoryRepository,
        )
    val workerFactory = AppWorkerFactory { this }

    val chatDependencies: ChatDependencies
        get() =
            ChatDependencies(
                sessionRepository = sessionRepository,
                messageRepository = messageRepository,
                eventLogRepository = eventLogRepository,
                agentRunner = agentRunner,
                skillManager = skillManager,
                settingsDataStore = settingsDataStore,
                attachmentRepository = attachmentRepository,
                attachmentImporter = attachmentImporter,
            )

    val tasksDependencies: TasksDependencies
        get() =
            TasksDependencies(
                taskRepository = taskRepository,
                schedulerCoordinator = schedulerCoordinator,
                sessionRepository = sessionRepository,
                messageRepository = messageRepository,
            )

    val skillsDependencies: SkillsDependencies
        get() =
            SkillsDependencies(
                skillManager = skillManager,
            )

    val settingsDependencies: SettingsDependencies
        get() =
            SettingsDependencies(
                providerRegistry = providerRegistry,
                settingsDataStore = settingsDataStore,
                providerSecretStore = providerSecretStore,
                openAiCodexOAuthClient = openAiCodexOAuthClient,
                networkStatusProvider = networkStatusProvider,
                memoryRepository = memoryRepository,
                webSearchKeyStore = webSearchKeyStore,
                braveSearchClient = braveSearchClient,
            )

    val onboardingDependencies: OnboardingDependencies
        get() =
            OnboardingDependencies(
                onboardingDataStore = onboardingDataStore,
                settingsDataStore = settingsDataStore,
            )

    val healthDependencies: HealthDependencies
        get() =
            HealthDependencies(
                schedulerCoordinator = schedulerCoordinator,
                toolRegistry = toolRegistry,
                providerRegistry = providerRegistry,
                settingsDataStore = settingsDataStore,
                eventLogRepository = eventLogRepository,
                networkStatusProvider = networkStatusProvider,
                crashMarkerStore = crashMarkerStore,
            )

    val workspaceDependencies: WorkspaceDependencies
        get() = WorkspaceDependencies(workspaceStore = workspaceStore)

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
        val sessionId =
            sessionRepository.getSession(mainSession.id)?.id
                ?: sessionRepository.getOrCreateMainSession().id
        try {
            ensureReadyMessage(sessionId)
        } catch (_: Exception) {
            val recoveredSessionId = sessionRepository.getOrCreateMainSession().id
            ensureReadyMessage(recoveredSessionId)
        }
    }
}
