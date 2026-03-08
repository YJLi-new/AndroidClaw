package ai.androidclaw.runtime.skills

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class SkillManager(
    private val bundledSkillLoader: BundledSkillLoader,
    private val toolExists: (String) -> Boolean,
) {
    suspend fun refreshBundledSkills(): List<SkillSnapshot> {
        return bundledSkillLoader.load().map(::applyEligibility)
    }

    fun selectModelSkills(skills: List<SkillSnapshot>): List<SkillSnapshot> {
        return skills.filter { skill ->
            val frontmatter = skill.frontmatter ?: return@filter false
            skill.enabled &&
                skill.eligibility.status == SkillEligibilityStatus.Eligible &&
                !frontmatter.disableModelInvocation &&
                frontmatter.commandDispatch == SkillCommandDispatch.Model
        }
    }

    fun findSlashSkill(commandName: String, skills: List<SkillSnapshot>): SkillSnapshot? {
        return skills.firstOrNull { skill ->
            val frontmatter = skill.frontmatter ?: return@firstOrNull false
            skill.enabled &&
                frontmatter.userInvocable &&
                frontmatter.name == commandName
        }
    }

    private fun applyEligibility(skill: SkillSnapshot): SkillSnapshot {
        val frontmatter = skill.frontmatter ?: return skill
        val metadata = frontmatter.metadata as? JsonObject
        val android = metadata?.get("android") as? JsonObject
        val bridgeOnly = android?.get("bridgeOnly")?.jsonPrimitive?.booleanOrNull == true
        if (bridgeOnly) {
            return skill.copy(
                eligibility = SkillEligibility(
                    status = SkillEligibilityStatus.BridgeOnly,
                    reasons = listOf("This skill is marked bridgeOnly and is not runnable locally."),
                ),
            )
        }

        val requiredTools = mutableSetOf<String>()
        frontmatter.commandTool?.let(requiredTools::add)
        android?.get("requiresTools")
            ?.let { element ->
                runCatching { element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrDefault(emptyList())
            }
            ?.let(requiredTools::addAll)

        val missingTools = requiredTools.filterNot(toolExists)
        return if (missingTools.isEmpty()) {
            skill.copy(
                eligibility = SkillEligibility(status = SkillEligibilityStatus.Eligible),
            )
        } else {
            skill.copy(
                eligibility = SkillEligibility(
                    status = SkillEligibilityStatus.MissingTool,
                    reasons = missingTools.map { "Missing tool: $it" },
                ),
            )
        }
    }
}
