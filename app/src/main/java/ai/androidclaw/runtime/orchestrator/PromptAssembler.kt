package ai.androidclaw.runtime.orchestrator

import ai.androidclaw.data.model.ChatMessage
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.runtime.providers.ModelMessage
import ai.androidclaw.runtime.providers.ModelMessageRole
import ai.androidclaw.runtime.providers.ModelRunMode
import ai.androidclaw.runtime.providers.ProviderToolCall
import ai.androidclaw.runtime.skills.SkillSnapshot
import ai.androidclaw.runtime.tools.ToolAvailabilityStatus
import ai.androidclaw.runtime.tools.ToolDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

data class PromptAssembly(
    val systemPrompt: String,
    val messageHistory: List<ModelMessage>,
    val truncated: Boolean = false,
    val summaryInserted: Boolean = false,
    val diagnostics: ContextWindowDiagnostics? = null,
)

class PromptAssembler(
    private val contextWindowManager: ContextWindowManager = ContextWindowManager(),
) {
    fun assemble(
        persistedMessages: List<ChatMessage>,
        selectedSkills: List<SkillSnapshot>,
        toolDescriptors: List<ToolDescriptor>,
        runMode: ModelRunMode,
        sessionSummary: String? = null,
    ): PromptAssembly {
        val systemPrompt = buildSystemPrompt(selectedSkills, toolDescriptors, runMode)
        val contextSelection = contextWindowManager.select(
            systemPrompt = systemPrompt,
            persistedHistory = persistedMessages.mapNotNull(ChatMessage::toModelMessage),
            summaryText = sessionSummary,
        )
        return PromptAssembly(
            systemPrompt = systemPrompt,
            messageHistory = contextSelection.messageHistory,
            truncated = contextSelection.truncated,
            summaryInserted = contextSelection.summaryInserted,
            diagnostics = contextSelection.diagnostics,
        )
    }
}

private val toolCallJson = Json { ignoreUnknownKeys = true }

private fun buildSystemPrompt(
    selectedSkills: List<SkillSnapshot>,
    toolDescriptors: List<ToolDescriptor>,
    runMode: ModelRunMode,
): String {
    return buildString {
        appendLine("You are AndroidClaw, a lightweight Android-native assistant host.")
        appendLine("Use concise, direct responses unless the user asks for depth.")
        appendLine("Run mode: ${runMode.name.lowercase()}.")
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
                    ToolAvailabilityStatus.Available -> Unit
                    ToolAvailabilityStatus.Unavailable -> append(" (currently unavailable)")
                    ToolAvailabilityStatus.PermissionRequired -> append(" (permission required)")
                    ToolAvailabilityStatus.ForegroundRequired -> append(" (open app to use)")
                    ToolAvailabilityStatus.DisabledByConfig -> append(" (disabled by config)")
                }
                appendLine()
            }
        }
    }.trim()
}

private fun ChatMessage.toModelMessage(): ModelMessage? {
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
        MessageRole.ToolCall -> toPersistedToolCallMessage()
        MessageRole.ToolResult -> toolCallId?.let { persistedToolCallId ->
            ModelMessage(
                role = ModelMessageRole.Tool,
                content = content.removePrefix("Tool result: ").trim(),
                toolCallId = persistedToolCallId,
            )
        }
    }
}

private fun ChatMessage.toPersistedToolCallMessage(): ModelMessage {
    val parsedToolCall = parsePersistedToolCallContent(
        content = content,
        toolCallId = toolCallId,
    )
    return if (parsedToolCall != null) {
        ModelMessage(
            role = ModelMessageRole.Assistant,
            content = "",
            toolCalls = listOf(parsedToolCall),
        )
    } else {
        ModelMessage(
            role = ModelMessageRole.Assistant,
            content = content,
        )
    }
}

private fun parsePersistedToolCallContent(
    content: String,
    toolCallId: String?,
): ProviderToolCall? {
    val persistedToolCallId = toolCallId ?: return null
    if (!content.startsWith("Tool request: ")) {
        return null
    }
    val body = content.removePrefix("Tool request: ").trim()
    val delimiterIndex = body.indexOf(' ')
    if (delimiterIndex <= 0 || delimiterIndex == body.lastIndex) {
        return null
    }
    val toolName = body.substring(0, delimiterIndex)
    val rawArguments = body.substring(delimiterIndex + 1).trim()
    val arguments = try {
        toolCallJson.parseToJsonElement(rawArguments).jsonObject
    } catch (_: Exception) {
        return null
    }
    return ProviderToolCall(
        id = persistedToolCallId,
        name = toolName,
        argumentsJson = arguments,
    )
}
