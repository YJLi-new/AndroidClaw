package ai.androidclaw.runtime.orchestrator

import ai.androidclaw.data.model.ChatMessage
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.runtime.providers.ModelMessage
import ai.androidclaw.runtime.providers.ModelMessageRole
import ai.androidclaw.runtime.providers.ModelRunMode
import ai.androidclaw.runtime.skills.SkillSnapshot
import ai.androidclaw.runtime.tools.ToolAvailabilityStatus
import ai.androidclaw.runtime.tools.ToolDescriptor

data class PromptAssembly(
    val systemPrompt: String,
    val messageHistory: List<ModelMessage>,
)

class PromptAssembler {
    fun assemble(
        persistedMessages: List<ChatMessage>,
        selectedSkills: List<SkillSnapshot>,
        toolDescriptors: List<ToolDescriptor>,
        runMode: ModelRunMode,
    ): PromptAssembly {
        return PromptAssembly(
            systemPrompt = buildSystemPrompt(selectedSkills, toolDescriptors, runMode),
            messageHistory = persistedMessages.mapNotNull(ChatMessage::toModelMessage),
        )
    }
}

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
        MessageRole.ToolCall -> ModelMessage(
            role = ModelMessageRole.Tool,
            content = content,
            toolCallId = toolCallId,
        )
        MessageRole.ToolResult -> ModelMessage(
            role = ModelMessageRole.Tool,
            content = content,
            toolCallId = toolCallId,
        )
    }
}
