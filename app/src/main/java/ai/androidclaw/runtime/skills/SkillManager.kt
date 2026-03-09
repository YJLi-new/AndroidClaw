package ai.androidclaw.runtime.skills

import ai.androidclaw.runtime.tools.ToolAvailabilityStatus
import ai.androidclaw.runtime.tools.ToolDescriptor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class SkillManager(
    private val bundledSkillLoader: BundledSkillLoader,
    private val toolDescriptor: (String) -> ToolDescriptor?,
) {
    private val cacheMutex = Mutex()
    private var cachedBundledSkills: List<SkillSnapshot>? = null

    suspend fun refreshBundledSkills(forceRefresh: Boolean = false): List<SkillSnapshot> {
        return cacheMutex.withLock {
            if (!forceRefresh) {
                cachedBundledSkills?.let { return@withLock it }
            }
            bundledSkillLoader.load()
                .map(::applyEligibility)
                .also { cachedBundledSkills = it }
        }
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

        val eligibilityReasons = requiredTools.mapNotNull { requiredTool ->
            val descriptor = toolDescriptor(requiredTool)
            when {
                descriptor == null -> "Missing tool: $requiredTool"
                descriptor.availability.status == ToolAvailabilityStatus.Available -> null
                else -> descriptor.toEligibilityReason()
            }
        }
        return if (eligibilityReasons.isEmpty()) {
            skill.copy(
                eligibility = SkillEligibility(status = SkillEligibilityStatus.Eligible),
            )
        } else {
            skill.copy(
                eligibility = SkillEligibility(
                    status = SkillEligibilityStatus.MissingTool,
                    reasons = eligibilityReasons,
                ),
            )
        }
    }
}

private fun ToolDescriptor.toEligibilityReason(): String {
    return when (availability.status) {
        ToolAvailabilityStatus.Available -> "Tool available: $name"
        ToolAvailabilityStatus.Unavailable -> "Tool blocked: $name (${availability.reason ?: "unavailable"})"
        ToolAvailabilityStatus.PermissionRequired -> {
            val permissionSummary = requiredPermissions
                .map { it.displayName }
                .ifEmpty { listOf("permission required") }
                .joinToString()
            "Tool blocked: $name ($permissionSummary)"
        }
        ToolAvailabilityStatus.ForegroundRequired -> {
            "Tool blocked: $name (${availability.reason ?: "foreground required"})"
        }
        ToolAvailabilityStatus.DisabledByConfig -> {
            "Tool blocked: $name (${availability.reason ?: "disabled by config"})"
        }
    }
}
