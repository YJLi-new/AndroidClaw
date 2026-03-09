package ai.androidclaw.runtime.orchestrator

import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.runtime.providers.ModelMessage
import ai.androidclaw.runtime.providers.ModelMessageRole
import ai.androidclaw.runtime.providers.ModelRequest
import ai.androidclaw.runtime.providers.ModelRunMode
import ai.androidclaw.runtime.providers.ModelSkillMetadata
import ai.androidclaw.runtime.providers.ProviderRegistry
import ai.androidclaw.runtime.skills.SkillCommandDispatch
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.skills.SkillSnapshot
import ai.androidclaw.runtime.tools.ToolExecutionResult
import ai.androidclaw.runtime.tools.ToolRegistry
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AgentTurnRequest(
    val sessionId: String,
    val userMessage: String,
)

data class AgentTurnResult(
    val assistantMessage: String,
    val selectedSkills: List<SkillSnapshot>,
    val directToolResult: ToolExecutionResult? = null,
    val providerRequestId: String? = null,
)

class AgentRunner(
    private val providerRegistry: ProviderRegistry,
    private val settingsDataStore: SettingsDataStore,
    private val messageRepository: MessageRepository,
    private val skillManager: SkillManager,
    private val toolRegistry: ToolRegistry,
) {
    suspend fun runInteractiveTurn(request: AgentTurnRequest): AgentTurnResult {
        return runTurn(
            sessionId = request.sessionId,
            userMessage = request.userMessage,
            runMode = ModelRunMode.Interactive,
        )
    }

    suspend fun runScheduledTurn(sessionId: String, userMessage: String): AgentTurnResult {
        return runTurn(
            sessionId = sessionId,
            userMessage = userMessage,
            runMode = ModelRunMode.Scheduled,
        )
    }

    private suspend fun runTurn(
        sessionId: String,
        userMessage: String,
        runMode: ModelRunMode,
    ): AgentTurnResult {
        val skills = skillManager.refreshBundledSkills()
        val slashCommand = SlashCommand.parse(userMessage)
        if (slashCommand != null) {
            val slashSkill = skillManager.findSlashSkill(slashCommand.name, skills)
            if (slashSkill == null) {
                return AgentTurnResult(
                    assistantMessage = "No enabled skill named /${slashCommand.name} is available.",
                    selectedSkills = emptyList(),
                )
            }
            if (slashSkill.eligibility.status != SkillEligibilityStatus.Eligible) {
                val reasons = slashSkill.eligibility.reasons
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(separator = " ")
                    ?: "This skill is not currently available."
                return AgentTurnResult(
                    assistantMessage = "Skill /${slashCommand.name} is unavailable. $reasons",
                    selectedSkills = listOf(slashSkill),
                )
            }

            val frontmatter = slashSkill.frontmatter
            if (
                frontmatter != null &&
                frontmatter.commandDispatch == SkillCommandDispatch.Tool &&
                frontmatter.commandTool != null
            ) {
                val toolResult = toolRegistry.execute(
                    name = frontmatter.commandTool,
                    arguments = buildJsonObject {
                        put("command", slashCommand.arguments)
                        put("commandName", slashCommand.name)
                        put("skillName", frontmatter.name)
                    },
                )
                return AgentTurnResult(
                    assistantMessage = toolResult.summary,
                    selectedSkills = listOf(slashSkill),
                    directToolResult = toolResult,
                )
            }
        }

        val selectedSkills = if (slashCommand != null) {
            skillManager.findSlashSkill(slashCommand.name, skills)
                ?.takeIf { it.eligibility.status == SkillEligibilityStatus.Eligible }
                ?.let(::listOf)
                ?: emptyList()
        } else {
            skillManager.selectModelSkills(skills)
        }
        val providerSettings = settingsDataStore.settings.first()
        val provider = providerRegistry.require(providerSettings.providerType)
        val systemPrompt = buildSystemPrompt(selectedSkills, toolRegistry.descriptors())
        val persistedMessageHistory = messageRepository.getRecentMessages(sessionId, limit = 20)
            .asReversed()
            .mapNotNull { message -> message.toModelMessage() }
        val messageHistory = persistedMessageHistory.ensureLatestUserMessage(userMessage)

        val response = withContext(Dispatchers.IO) {
            provider.generate(
                ModelRequest(
                    sessionId = sessionId,
                    requestId = UUID.randomUUID().toString(),
                    messageHistory = messageHistory,
                    systemPrompt = systemPrompt,
                    enabledSkills = selectedSkills.map { skill ->
                        ModelSkillMetadata(
                            id = skill.id,
                            name = skill.displayName,
                            description = skill.frontmatter?.description.orEmpty(),
                            instructions = skill.instructionsMd,
                        )
                    },
                    toolDescriptors = toolRegistry.descriptors(),
                    runMode = runMode,
                ),
            )
        }

        return AgentTurnResult(
            assistantMessage = response.text,
            selectedSkills = selectedSkills,
            providerRequestId = response.providerRequestId,
        )
    }
}

private fun List<ModelMessage>.ensureLatestUserMessage(userMessage: String): List<ModelMessage> {
    val normalizedMessage = userMessage.trim()
    if (normalizedMessage.isBlank()) {
        return this
    }

    val lastMessage = lastOrNull()
    return if (
        lastMessage?.role == ModelMessageRole.User &&
        lastMessage.content.trim() == normalizedMessage
    ) {
        this
    } else {
        this + ModelMessage(
            role = ModelMessageRole.User,
            content = normalizedMessage,
        )
    }
}

private fun buildSystemPrompt(
    selectedSkills: List<SkillSnapshot>,
    toolDescriptors: List<ai.androidclaw.runtime.tools.ToolDescriptor>,
): String {
    return buildString {
        appendLine("You are AndroidClaw, a lightweight Android-native assistant host.")
        appendLine("Use concise, direct responses unless the user asks for depth.")
        if (selectedSkills.isNotEmpty()) {
            appendLine()
            appendLine("Enabled skills:")
            selectedSkills.forEach { skill ->
                appendLine("- ${skill.displayName}: ${skill.frontmatter?.description.orEmpty()}")
                val instructions = skill.instructionsMd.trim()
                if (instructions.isNotBlank()) {
                    appendLine(instructions)
                }
            }
        }
        if (toolDescriptors.isNotEmpty()) {
            appendLine()
            appendLine("Available tools:")
            toolDescriptors.forEach { tool ->
                append("- ${tool.name}: ${tool.description}")
                if (tool.aliases.isNotEmpty()) {
                    append(" [aliases: ${tool.aliases.joinToString()}]")
                }
                if (tool.foregroundRequired) {
                    append(" (foreground required)")
                }
                when (tool.availability.status) {
                    ai.androidclaw.runtime.tools.ToolAvailabilityStatus.Available -> Unit
                    ai.androidclaw.runtime.tools.ToolAvailabilityStatus.Unavailable -> append(" (currently unavailable)")
                    ai.androidclaw.runtime.tools.ToolAvailabilityStatus.PermissionRequired -> append(" (permission required)")
                    ai.androidclaw.runtime.tools.ToolAvailabilityStatus.ForegroundRequired -> append(" (open app to use)")
                    ai.androidclaw.runtime.tools.ToolAvailabilityStatus.DisabledByConfig -> append(" (disabled by config)")
                }
                appendLine()
            }
        }
    }.trim()
}

private fun ai.androidclaw.data.model.ChatMessage.toModelMessage(): ModelMessage? {
    return when (role) {
        MessageRole.User -> ModelMessage(
            role = ModelMessageRole.User,
            content = content,
        )
        MessageRole.Assistant -> ModelMessage(
            role = ModelMessageRole.Assistant,
            content = content,
        )
        MessageRole.System -> ModelMessage(
            role = ModelMessageRole.System,
            content = content,
        )
        MessageRole.ToolCall -> ModelMessage(
            role = ModelMessageRole.System,
            content = "Tool call recorded: $content",
        )
        MessageRole.ToolResult -> ModelMessage(
            role = ModelMessageRole.System,
            content = "Tool result recorded: $content",
        )
    }
}

private data class SlashCommand(
    val name: String,
    val arguments: String,
) {
    companion object {
        fun parse(text: String): SlashCommand? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("/")) return null
            val spaceIndex = trimmed.indexOf(' ')
            return if (spaceIndex == -1) {
                SlashCommand(name = trimmed.removePrefix("/"), arguments = "")
            } else {
                SlashCommand(
                    name = trimmed.substring(1, spaceIndex),
                    arguments = trimmed.substring(spaceIndex + 1).trim(),
                )
            }
        }
    }
}
