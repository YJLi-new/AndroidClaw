package ai.androidclaw.app

import android.app.Application
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
import ai.androidclaw.runtime.providers.ProviderRegistry
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.skills.BundledSkillLoader
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.skills.SkillParser
import ai.androidclaw.runtime.tools.ToolDescriptor
import ai.androidclaw.runtime.tools.ToolExecutionResult
import ai.androidclaw.runtime.tools.ToolRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock

class AppContainer(application: Application) {
    private val clock: Clock = Clock.systemDefaultZone()
    val database = AndroidClawDatabase.build(application)
    val settingsDataStore = SettingsDataStore(application)
    val sessionRepository = SessionRepository(database.sessionDao())
    val messageRepository = MessageRepository(database.messageDao())
    val taskRepository = TaskRepository(database.taskDao(), database.taskRunDao())
    val skillRepository = SkillRepository(database.skillRecordDao())
    val eventLogRepository = EventLogRepository(database.eventLogDao())

    val toolRegistry = ToolRegistry(
        tools = listOf(
            ToolRegistry.Entry(
                descriptor = ToolDescriptor(
                    name = "tasks.list",
                    description = "List known automation capabilities and any saved tasks.",
                ),
            ) { _ ->
                ToolExecutionResult(
                    summary = "No persisted tasks yet. Scheduler supports once, interval, and cron execution.",
                    payload = buildJsonObject {
                        put("supportsOnce", true)
                        put("supportsInterval", true)
                        put("supportsCron", true)
                        put("taskCount", 0)
                    },
                )
            },
            ToolRegistry.Entry(
                descriptor = ToolDescriptor(
                    name = "health.status",
                    description = "Return lightweight runtime health information.",
                ),
            ) { _ ->
                ToolExecutionResult(
                    summary = "Runtime bootstrapped with FakeProvider, bundled skills, and scheduler preview support.",
                    payload = buildJsonObject {
                        put("provider", "fake")
                        put("schedulerReady", true)
                        put("skillsReady", true)
                    },
                )
            },
        ),
    )

    private val bundledSkillLoader = BundledSkillLoader(
        assetManager = application.assets,
        rootPath = "skills",
        parser = SkillParser(),
    )

    val skillManager = SkillManager(
        bundledSkillLoader = bundledSkillLoader,
        toolExists = toolRegistry::hasTool,
    )

    val providerRegistry = ProviderRegistry(
        defaultProvider = FakeProvider(clock = clock),
    )

    val schedulerCoordinator = SchedulerCoordinator(
        application = application,
        clock = clock,
    )

    val agentRunner = AgentRunner(
        providerRegistry = providerRegistry,
        skillManager = skillManager,
        toolRegistry = toolRegistry,
    )

    val chatDependencies: ChatDependencies
        get() = ChatDependencies(
            sessionRepository = sessionRepository,
            messageRepository = messageRepository,
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
        )

    val healthDependencies: HealthDependencies
        get() = HealthDependencies(
            schedulerCoordinator = schedulerCoordinator,
            toolRegistry = toolRegistry,
            providerRegistry = providerRegistry,
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
