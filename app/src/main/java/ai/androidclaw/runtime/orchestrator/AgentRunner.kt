package ai.androidclaw.runtime.orchestrator

import ai.androidclaw.runtime.providers.ModelRequest
import ai.androidclaw.runtime.providers.ProviderRegistry
import ai.androidclaw.runtime.skills.SkillCommandDispatch
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.skills.SkillSnapshot
import ai.androidclaw.runtime.tools.ToolExecutionResult
import ai.androidclaw.runtime.tools.ToolRegistry
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
)

class AgentRunner(
    private val providerRegistry: ProviderRegistry,
    private val skillManager: SkillManager,
    private val toolRegistry: ToolRegistry,
) {
    suspend fun runInteractiveTurn(request: AgentTurnRequest): AgentTurnResult {
        val skills = skillManager.refreshBundledSkills()
        val slashCommand = SlashCommand.parse(request.userMessage)
        if (slashCommand != null) {
            val slashSkill = skillManager.findSlashSkill(slashCommand.name, skills)
            if (slashSkill == null) {
                return AgentTurnResult(
                    assistantMessage = "No enabled skill named /${slashCommand.name} is available.",
                    selectedSkills = emptyList(),
                )
            }

            val frontmatter = slashSkill.frontmatter
            if (
                frontmatter != null &&
                slashSkill.eligibility.status == SkillEligibilityStatus.Eligible &&
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

        val response = providerRegistry.defaultProvider.generate(
            ModelRequest(
                sessionId = request.sessionId,
                userMessage = slashCommand?.arguments ?: request.userMessage,
                enabledSkillNames = selectedSkills.mapNotNull { it.frontmatter?.name },
                toolDescriptors = toolRegistry.descriptors(),
                interactive = true,
            ),
        )

        return AgentTurnResult(
            assistantMessage = response.text,
            selectedSkills = selectedSkills,
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

