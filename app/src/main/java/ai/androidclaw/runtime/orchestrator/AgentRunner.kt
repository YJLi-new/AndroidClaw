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
import ai.androidclaw.runtime.providers.ProviderToolCall
import ai.androidclaw.runtime.skills.SkillCommandDispatch
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.skills.SkillSnapshot
import ai.androidclaw.runtime.tools.ToolExecutionResult
import ai.androidclaw.runtime.tools.ToolExecutionContext
import ai.androidclaw.runtime.tools.ToolInvocationOrigin
import ai.androidclaw.runtime.tools.ToolRegistry
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AgentTurnRequest(
    val sessionId: String,
    val userMessage: String,
    val taskRunId: String? = null,
)

enum class AgentTurnExitReason {
    Completed,
    DirectToolDispatch,
    ToolLoopExhausted,
}

data class AgentTurnResult(
    val assistantMessage: String,
    val assistantMessageId: String? = null,
    val selectedSkills: List<SkillSnapshot>,
    val directToolResult: ToolExecutionResult? = null,
    val providerRequestId: String? = null,
    val exitReason: AgentTurnExitReason = AgentTurnExitReason.Completed,
)

class AgentRunner(
    private val providerRegistry: ProviderRegistry,
    private val settingsDataStore: SettingsDataStore,
    private val messageRepository: MessageRepository,
    private val skillManager: SkillManager,
    private val toolRegistry: ToolRegistry,
    private val sessionLaneCoordinator: SessionLaneCoordinator,
    private val promptAssembler: PromptAssembler,
) {
    suspend fun runInteractiveTurn(request: AgentTurnRequest): AgentTurnResult {
        return runTurn(
            sessionId = request.sessionId,
            userMessage = request.userMessage,
            runMode = ModelRunMode.Interactive,
            taskRunId = request.taskRunId,
        )
    }

    suspend fun runScheduledTurn(
        sessionId: String,
        userMessage: String,
        taskRunId: String? = null,
    ): AgentTurnResult {
        return runTurn(
            sessionId = sessionId,
            userMessage = userMessage,
            runMode = ModelRunMode.Scheduled,
            taskRunId = taskRunId,
        )
    }

    private suspend fun runTurn(
        sessionId: String,
        userMessage: String,
        runMode: ModelRunMode,
        taskRunId: String?,
    ): AgentTurnResult {
        return sessionLaneCoordinator.withLane(sessionId) {
            val normalizedUserMessage = userMessage.trim()
            val availableSkills = skillManager.refreshSkillInventory(sessionId = sessionId)
            val slashCommand = SlashCommand.parse(normalizedUserMessage)
            messageRepository.addMessage(
                sessionId = sessionId,
                role = MessageRole.User,
                content = normalizedUserMessage,
                taskRunId = taskRunId,
            )
            try {
                if (slashCommand != null) {
                    val slashSkill = skillManager.findSlashSkill(slashCommand.name, availableSkills)
                    if (slashSkill == null) {
                        return@withLane persistAssistantResponse(
                            sessionId = sessionId,
                            assistantText = "No enabled skill named /${slashCommand.name} is available.",
                            selectedSkills = emptyList(),
                            taskRunId = taskRunId,
                        )
                    }
                    if (slashSkill.eligibility.status != SkillEligibilityStatus.Eligible) {
                        val reasons = slashSkill.eligibility.reasons
                            .takeIf { it.isNotEmpty() }
                            ?.joinToString(separator = " ")
                            ?: "This skill is not currently available."
                        return@withLane persistAssistantResponse(
                            sessionId = sessionId,
                            assistantText = "Skill /${slashCommand.name} is unavailable. $reasons",
                            selectedSkills = listOf(slashSkill),
                            taskRunId = taskRunId,
                        )
                    }

                    val frontmatter = slashSkill.frontmatter
                    if (
                        frontmatter != null &&
                        frontmatter.commandDispatch == SkillCommandDispatch.Tool &&
                        frontmatter.commandTool != null
                    ) {
                        val toolResult = executeDirectToolDispatch(
                            sessionId = sessionId,
                            slashCommand = slashCommand,
                            slashSkill = slashSkill,
                            toolName = frontmatter.commandTool,
                            runMode = runMode,
                            taskRunId = taskRunId,
                        )
                        return@withLane persistAssistantResponse(
                            sessionId = sessionId,
                            assistantText = toolResult.summary,
                            selectedSkills = listOf(slashSkill),
                            directToolResult = toolResult,
                            taskRunId = taskRunId,
                            exitReason = AgentTurnExitReason.DirectToolDispatch,
                        )
                    }
                }

                val selectedSkills = if (slashCommand != null) {
                    skillManager.findSlashSkill(slashCommand.name, availableSkills)
                        ?.takeIf { it.eligibility.status == SkillEligibilityStatus.Eligible }
                        ?.let(::listOf)
                        ?: emptyList()
                } else {
                    skillManager.selectModelSkills(availableSkills)
                }
                val toolDescriptors = toolRegistry.descriptors()
                val providerSettings = settingsDataStore.settings.first()
                val provider = providerRegistry.require(providerSettings.providerType)
                val persistedMessages = messageRepository.getRecentMessages(sessionId, limit = 32).asReversed()
                val promptAssembly = promptAssembler.assemble(
                    persistedMessages = persistedMessages,
                    selectedSkills = selectedSkills,
                    toolDescriptors = toolDescriptors,
                    runMode = runMode,
                )
                var messageHistory = promptAssembly.messageHistory
                var providerRequestId: String? = null

                repeat(MAX_TOOL_ROUNDS) { round ->
                    val response = withContext(Dispatchers.IO) {
                        provider.generate(
                            ModelRequest(
                                sessionId = sessionId,
                                requestId = UUID.randomUUID().toString(),
                                messageHistory = messageHistory,
                                systemPrompt = promptAssembly.systemPrompt,
                                enabledSkills = selectedSkills.map { skill ->
                                    ModelSkillMetadata(
                                        id = skill.id,
                                        name = skill.displayName,
                                        description = skill.frontmatter?.description.orEmpty(),
                                        instructions = skill.instructionsMd,
                                    )
                                },
                                toolDescriptors = toolDescriptors,
                                runMode = runMode,
                            ),
                        )
                    }
                    providerRequestId = response.providerRequestId
                    if (response.finishReason != TOOL_USE_FINISH_REASON) {
                        return@withLane persistAssistantResponse(
                            sessionId = sessionId,
                            assistantText = response.text,
                            selectedSkills = selectedSkills,
                            providerRequestId = response.providerRequestId,
                            taskRunId = taskRunId,
                        )
                    }

                    if (response.toolCalls.isEmpty()) {
                        return@withLane persistAssistantResponse(
                            sessionId = sessionId,
                            assistantText = "Provider requested tool use without specifying a tool call.",
                            selectedSkills = selectedSkills,
                            providerRequestId = response.providerRequestId,
                            taskRunId = taskRunId,
                        )
                    }

                    val toolResultMessages = executeProviderToolCalls(
                        sessionId = sessionId,
                        toolCalls = response.toolCalls,
                        runMode = runMode,
                        requestId = response.providerRequestId,
                        taskRunId = taskRunId,
                    )
                    messageHistory = messageHistory +
                        ModelMessage(
                            role = ModelMessageRole.Assistant,
                            content = response.text,
                            toolCalls = response.toolCalls,
                        ) +
                        toolResultMessages

                    if (round == MAX_TOOL_ROUNDS - 1) {
                        return@withLane persistAssistantResponse(
                            sessionId = sessionId,
                            assistantText = "Tool-call limit reached before the turn could complete.",
                            selectedSkills = selectedSkills,
                            providerRequestId = providerRequestId,
                            taskRunId = taskRunId,
                            exitReason = AgentTurnExitReason.ToolLoopExhausted,
                        )
                    }
                }

                error("Unreachable: tool-call loop should return before exhausting repeat.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (runMode == ModelRunMode.Interactive) {
                    runCatching {
                        messageRepository.addMessage(
                            sessionId = sessionId,
                            role = MessageRole.System,
                            content = "Turn failed: ${error.message ?: "Turn failed."}",
                            taskRunId = taskRunId,
                        )
                    }
                }
                throw error
            }
        }
    }

    private suspend fun executeDirectToolDispatch(
        sessionId: String,
        slashCommand: SlashCommand,
        slashSkill: SkillSnapshot,
        toolName: String,
        runMode: ModelRunMode,
        taskRunId: String?,
    ): ToolExecutionResult {
        val toolCallId = UUID.randomUUID().toString()
        val toolArguments = buildJsonObject {
            put("command", slashCommand.arguments)
            put("commandName", slashCommand.name)
            put("skillName", slashSkill.displayName)
        }
        messageRepository.addMessage(
            sessionId = sessionId,
            role = MessageRole.ToolCall,
            content = "Tool request: $toolName $toolArguments",
            toolCallId = toolCallId,
            taskRunId = taskRunId,
        )
        val toolResult = toolRegistry.execute(
            context = ToolExecutionContext(
                sessionId = sessionId,
                taskRunId = taskRunId,
                origin = ToolInvocationOrigin.SlashCommand,
                runMode = runMode,
                requestedName = toolName,
                canonicalName = toolName,
                requestId = toolCallId,
                activeSkillId = slashSkill.id,
            ),
            arguments = toolArguments,
        )
        messageRepository.addMessage(
            sessionId = sessionId,
            role = MessageRole.ToolResult,
            content = "Tool result: ${toolResult.summary}",
            toolCallId = toolCallId,
            taskRunId = taskRunId,
        )
        return toolResult
    }

    private suspend fun executeProviderToolCalls(
        sessionId: String,
        toolCalls: List<ProviderToolCall>,
        runMode: ModelRunMode,
        requestId: String?,
        taskRunId: String?,
    ): List<ModelMessage> {
        return buildList {
            toolCalls.forEach { toolCall ->
                messageRepository.addMessage(
                    sessionId = sessionId,
                    role = MessageRole.ToolCall,
                    content = "Tool request: ${toolCall.name} ${toolCall.argumentsJson}",
                    toolCallId = toolCall.id,
                    taskRunId = taskRunId,
                )
                val toolResult = toolRegistry.execute(
                    context = ToolExecutionContext(
                        sessionId = sessionId,
                        taskRunId = taskRunId,
                        origin = if (runMode == ModelRunMode.Scheduled) {
                            ToolInvocationOrigin.ScheduledModel
                        } else {
                            ToolInvocationOrigin.Model
                        },
                        runMode = runMode,
                        requestedName = toolCall.name,
                        canonicalName = toolCall.name,
                        requestId = requestId ?: toolCall.id,
                    ),
                    arguments = toolCall.argumentsJson,
                )
                messageRepository.addMessage(
                    sessionId = sessionId,
                    role = MessageRole.ToolResult,
                    content = "Tool result: ${toolResult.summary}",
                    toolCallId = toolCall.id,
                    taskRunId = taskRunId,
                )
                add(
                    ModelMessage(
                        role = ModelMessageRole.Tool,
                        content = toolResult.summary,
                        toolCallId = toolCall.id,
                        toolName = toolCall.name,
                    ),
                )
            }
        }
    }

    private suspend fun persistAssistantResponse(
        sessionId: String,
        assistantText: String,
        selectedSkills: List<SkillSnapshot>,
        directToolResult: ToolExecutionResult? = null,
        providerRequestId: String? = null,
        taskRunId: String?,
        exitReason: AgentTurnExitReason = AgentTurnExitReason.Completed,
    ): AgentTurnResult {
        val persistedText = assistantText.withActiveSkills(selectedSkills)
        val assistantMessage = messageRepository.addMessage(
            sessionId = sessionId,
            role = MessageRole.Assistant,
            content = persistedText,
            providerMeta = providerRequestId,
            taskRunId = taskRunId,
        )
        return AgentTurnResult(
            assistantMessage = persistedText,
            assistantMessageId = assistantMessage.id,
            selectedSkills = selectedSkills,
            directToolResult = directToolResult,
            providerRequestId = providerRequestId,
            exitReason = exitReason,
        )
    }

    companion object {
        private const val MAX_TOOL_ROUNDS = 6
        private const val TOOL_USE_FINISH_REASON = "tool_use"
    }
}

private fun String.withActiveSkills(selectedSkills: List<SkillSnapshot>): String {
    if (selectedSkills.isEmpty()) {
        return this
    }
    return buildString {
        append(this@withActiveSkills)
        append("\n\nActive skills: ")
        append(selectedSkills.joinToString { it.displayName })
    }
}
