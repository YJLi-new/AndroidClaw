package ai.androidclaw.runtime.tools

import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.WorkspaceStore
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.MemoryRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.providers.NetworkStatusProvider
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.skills.SkillSnapshot
import android.app.Application
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.io.File
import java.time.Clock

/**
 * Builds the curated built-in tool set. Model-facing tools use snake_case names (several
 * providers reject dots in function names); legacy dotted names stay as aliases so persisted
 * transcripts and imported skills keep resolving.
 */
internal fun createBuiltInToolRegistry(
    application: Application,
    settingsDataStore: SettingsDataStore,
    sessionRepository: SessionRepository,
    taskRepository: TaskRepository,
    schedulerCoordinator: SchedulerCoordinator,
    bundledSkillsProvider: suspend () -> List<SkillSnapshot>,
    skillEnabledUpdater: suspend (skillId: String, enabled: Boolean) -> Unit = { _, _ -> },
    skillInventoryRefresher: suspend (sessionId: String?, forceRefresh: Boolean) -> List<SkillSnapshot> = { _, _ ->
        bundledSkillsProvider()
    },
    messageRepository: MessageRepository,
    memoryRepository: MemoryRepository? = null,
    eventLogRepository: EventLogRepository? = null,
    workspaceStore: WorkspaceStore = WorkspaceStore(File(application.filesDir, WorkspaceStore.DIRECTORY_NAME)),
    webHttpClient: OkHttpClient? = null,
    webFetchAllowPrivateNetwork: Boolean = false,
    networkStatusProvider: NetworkStatusProvider? = null,
    isAppInForeground: () -> Boolean = { true },
    providerLabel: suspend () -> String = { settingsDataStore.currentProviderLabel() },
    clock: Clock = Clock.systemDefaultZone(),
): ToolRegistry {
    lateinit var registry: ToolRegistry
    registry =
        ToolRegistry(
            eventLogger = { level, message, details ->
                eventLogRepository?.log(
                    category = EventCategory.Tool,
                    level = level,
                    message = message,
                    details = details,
                )
            },
            tools =
                buildList {
                    addAll(sessionToolEntries(sessionRepository, messageRepository))
                    memoryRepository?.let { repository ->
                        addAll(memoryToolEntries(settingsDataStore, repository))
                    }
                    addAll(taskToolEntries(taskRepository, sessionRepository, schedulerCoordinator, clock))
                    addAll(skillToolEntries(skillInventoryRefresher, skillEnabledUpdater))
                    addAll(workspaceToolEntries(workspaceStore))
                    add(webFetchToolEntry(webHttpClient, allowPrivateNetwork = webFetchAllowPrivateNetwork))
                    addAll(deviceToolEntries(application, networkStatusProvider, isAppInForeground, clock))
                    add(notifyToolEntry(application))
                    add(
                        healthStatusToolEntry(
                            schedulerCoordinator = schedulerCoordinator,
                            settingsDataStore = settingsDataStore,
                            networkStatusProvider = networkStatusProvider,
                            providerLabel = providerLabel,
                            toolCount = { registry.descriptors().size },
                        ),
                    )
                    addAll(
                        slashCommandToolEntries(
                            sessionRepository = sessionRepository,
                            messageRepository = messageRepository,
                            settingsDataStore = settingsDataStore,
                            memoryRepository = memoryRepository,
                        ),
                    )
                },
        )
    return registry
}

private suspend fun SettingsDataStore.currentProviderLabel(): String = settings.first().providerType.displayName
