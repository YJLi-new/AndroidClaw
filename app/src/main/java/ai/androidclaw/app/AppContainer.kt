package ai.androidclaw.app

import android.app.Application
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
}

