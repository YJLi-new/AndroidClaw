package ai.androidclaw.runtime.tools

import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillSnapshot
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val SKILL_INSTRUCTIONS_MAX_CHARS = 8_000
private const val SKILL_DESCRIPTION_MAX_CHARS = 160

internal fun skillToolEntries(
    skillInventoryRefresher: suspend (sessionId: String?, forceRefresh: Boolean) -> List<SkillSnapshot>,
    skillEnabledUpdater: suspend (skillId: String, enabled: Boolean) -> Unit,
): List<ToolRegistry.Entry> =
    listOf(
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "skills_list",
                    aliases = listOf("skills.list"),
                    description = "List installed skills with enabled state, availability and slash command.",
                    riskTier = ToolRiskTier.ReadOnly,
                    allowedOrigins = INTERACTIVE_AND_SCHEDULED_ORIGINS,
                ),
        ) { context, _ ->
            val skills = skillInventoryRefresher(context.sessionId, false)
            if (skills.isEmpty()) {
                return@Entry toolSuccess("No skills installed.")
            }
            toolSuccess(
                summary =
                    buildString {
                        append("${skills.size} skill(s):")
                        skills.forEach { skill -> append('\n').append(skill.describeLine()) }
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "skills_get",
                    aliases = listOf("skills.get"),
                    description = "Read one skill's SKILL.md instructions by name.",
                    riskTier = ToolRiskTier.ReadOnly,
                    allowedOrigins = INTERACTIVE_AND_SCHEDULED_ORIGINS,
                    arguments = listOf(stringArg("name", "Skill name or id.", required = true)),
                ),
        ) { context, arguments ->
            val query = arguments.requireText("name", "skills_get")
            val skill =
                skillInventoryRefresher(context.sessionId, false).findSkill(query)
                    ?: return@Entry skillNotFound(query)
            toolSuccess(
                summary = skill.describeLine().removePrefix("- "),
                payload =
                    buildJsonObject {
                        put("id", skill.id)
                        put("instructions", skill.instructionsMd.trim().clip(SKILL_INSTRUCTIONS_MAX_CHARS))
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "skills_set_enabled",
                    aliases = listOf("skills.enable", "skills.disable", "skills.set_enabled"),
                    description = "Enable or disable a skill by name.",
                    riskTier = ToolRiskTier.WritesLocalState,
                    allowedOrigins = INTERACTIVE_ORIGINS,
                    arguments =
                        listOf(
                            stringArg("name", "Skill name or id.", required = true),
                            boolArg("enabled", "true to enable, false to disable."),
                        ),
                ),
        ) { context, arguments ->
            val query = arguments.requireText("name", "skills_set_enabled")
            val enabled =
                when (context.requestedName) {
                    "skills.enable" -> true
                    "skills.disable" -> false
                    else ->
                        arguments.optionalBooleanOrNull("enabled")
                            ?: throw IllegalArgumentException("skills_set_enabled requires enabled=true or false.")
                }
            val skill =
                skillInventoryRefresher(context.sessionId, false).findSkill(query)
                    ?: return@Entry skillNotFound(query)
            skillEnabledUpdater(skill.id, enabled)
            toolSuccess(
                summary = "${if (enabled) "Enabled" else "Disabled"} skill ${skill.displayName}.",
                payload =
                    buildJsonObject {
                        put("id", skill.id)
                        put("enabled", enabled)
                    },
            )
        },
    )

private fun List<SkillSnapshot>.findSkill(query: String): SkillSnapshot? {
    val normalized = query.trim().removePrefix("/").lowercase()
    return firstOrNull { it.id.lowercase() == normalized }
        ?: firstOrNull { it.frontmatter?.name?.lowercase() == normalized }
        ?: firstOrNull { it.skillKey.lowercase() == normalized }
        ?: firstOrNull { it.displayName.lowercase() == normalized }
}

private fun SkillSnapshot.describeLine(): String =
    buildString {
        append("- ").append(displayName)
        frontmatter?.takeIf { it.userInvocable }?.let { append(" (/").append(it.name).append(')') }
        append(" · ").append(if (enabled) "enabled" else "disabled")
        if (eligibility.status != SkillEligibilityStatus.Eligible) {
            append(" · unavailable")
            eligibility.reasons.firstOrNull()?.let { append(": ").append(it.clip(120)) }
        }
        frontmatter
            ?.description
            ?.takeIf(String::isNotBlank)
            ?.let { append(" · ").append(it.singleLine().clip(SKILL_DESCRIPTION_MAX_CHARS)) }
    }

private fun skillNotFound(query: String): ToolExecutionResult =
    toolFailure(
        errorCode = "SKILL_NOT_FOUND",
        summary = "Skill $query was not found. Use skills_list to see installed skills.",
    )
